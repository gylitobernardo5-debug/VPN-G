package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.data.VpnRuntimeStore
import com.example.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mobile Network Engine:
 * - Uses Android ConnectivityManager to observe 4G/LTE/5G cellular (and Wi-Fi fallback) transport.
 * - Detects network switches and local IP changes via LinkProperties.
 * - Performs real TCP RTT latency probes, sliding-window packet-loss estimation, and real public IP lookup.
 */
class MobileNetworkEngine(
    private val context: Context,
    private val onTransportChanged: ((reason: String) -> Unit)? = null
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private var probeJob: Job? = null
    private var lastObservedIp: String = ""
    private var lastTransportTag: String = ""

    private val probeWindow = ArrayDeque<Boolean>(10)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetworkSnapshot(network, reason = "Rede disponível")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            refreshNetworkSnapshot(network, caps = caps, reason = "Capacidades de rede atualizadas")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            refreshNetworkSnapshot(network, linkProps = linkProperties, reason = "Alteração de IP/Interface")
        }

        override fun onLost(network: Network) {
            val active = connectivityManager.activeNetwork
            if (active == null) {
                VpnRuntimeStore.updateNetworkState {
                    it.copy(
                        isConnected = false,
                        isCellular = false,
                        transportLabel = "Sem rede",
                        localIp = "—",
                        lastUpdatedEpochMs = System.currentTimeMillis()
                    )
                }
                VpnRuntimeStore.log(LogLevel.WARN, "NET", "Conectividade perdida no transporte.")
                onTransportChanged?.invoke("Rede perdida")
            } else {
                refreshNetworkSnapshot(active, reason = "Mudança de rede ativa")
            }
        }
    }

    fun startMonitoring() {
        if (!started.compareAndSet(false, true)) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }.onFailure { e ->
            VpnRuntimeStore.log(LogLevel.WARN, "NET", "Callback de rede indisponível: ${e.message}")
        }

        connectivityManager.activeNetwork?.let { active ->
            refreshNetworkSnapshot(active, reason = "Inicialização")
        }

        probeJob = scope.launch {
            while (isActive && started.get()) {
                val rtt = measureTcpPing("1.1.1.1", 443)
                synchronized(probeWindow) {
                    if (probeWindow.size >= 10) probeWindow.removeFirst()
                    probeWindow.addLast(rtt != null)
                }
                val lossPct = synchronized(probeWindow) {
                    if (probeWindow.isEmpty()) 0f
                    else (probeWindow.count { !it }.toFloat() / probeWindow.size) * 100f
                }

                VpnRuntimeStore.updateNetworkState { current ->
                    current.copy(
                        latencyMs = rtt ?: current.latencyMs,
                        packetLossPercent = lossPct,
                        lastUpdatedEpochMs = System.currentTimeMillis()
                    )
                }
                if (rtt != null) {
                    VpnRuntimeStore.updateTelemetry { current ->
                        current.copy(pingMs = rtt)
                    }
                }
                delay(8_000L)
            }
        }
    }

    fun stopMonitoring() {
        if (!started.compareAndSet(true, false)) return
        probeJob?.cancel()
        probeJob = null
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    private fun refreshNetworkSnapshot(
        network: Network,
        caps: NetworkCapabilities? = null,
        linkProps: LinkProperties? = null,
        reason: String
    ) {
        val resolvedCaps = caps ?: connectivityManager.getNetworkCapabilities(network)
        val resolvedLink = linkProps ?: connectivityManager.getLinkProperties(network)

        // Ignore our own VPN interface when reporting underlying physical mobile/Wi-Fi transport
        if (resolvedCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return
        }

        val isCellular = resolvedCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isWifi = resolvedCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = resolvedCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val downKbps = resolvedCaps?.linkDownstreamBandwidthKbps ?: 0
        val upKbps = resolvedCaps?.linkUpstreamBandwidthKbps ?: 0

        val transportLabel = when {
            isCellular -> inferCellularGenerationLabel(resolvedCaps, downKbps)
            isWifi -> "Wi-Fi"
            isEthernet -> "Ethernet"
            resolvedCaps != null -> "Rede Ativa"
            else -> "Sem rede"
        }

        val ipv4 = resolvedLink?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
            ?: "—"

        val dnsList = resolvedLink?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?: emptyList()

        val ipChanged = lastObservedIp.isNotEmpty() && ipv4 != "—" && ipv4 != lastObservedIp
        val transportSwitched = lastTransportTag.isNotEmpty() && transportLabel != lastTransportTag

        if (ipv4 != "—") lastObservedIp = ipv4
        if (transportLabel != "Sem rede") lastTransportTag = transportLabel

        VpnRuntimeStore.updateNetworkState { current ->
            current.copy(
                isConnected = resolvedCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                isCellular = isCellular,
                transportLabel = transportLabel,
                localIp = ipv4,
                dnsServers = dnsList,
                downstreamKbps = downKbps,
                upstreamKbps = upKbps,
                ipChangeCount = if (ipChanged) current.ipChangeCount + 1 else current.ipChangeCount,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
        }

        if (ipChanged || transportSwitched) {
            VpnRuntimeStore.log(
                LogLevel.INFO,
                "NET",
                "$reason • Transporte=$transportLabel • IP local=$ipv4"
            )
            onTransportChanged?.invoke("$transportLabel ($ipv4)")
        }
    }

    private fun inferCellularGenerationLabel(
        caps: NetworkCapabilities?,
        downstreamKbps: Int
    ): String {
        if (caps == null) return "Dados Móveis"
        val isTempUnmetered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        return when {
            isTempUnmetered || downstreamKbps >= 150_000 -> "5G NR"
            downstreamKbps >= 25_000 -> "4G LTE+"
            downstreamKbps >= 5_000 -> "4G LTE"
            else -> "Rede Móvel"
        }
    }

    suspend fun measureTcpPing(host: String, port: Int, timeoutMs: Int = 3_000): Long? =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                val startNs = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { socket.close() }
            }
        }

    suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://api.ipify.org?format=text",
            "https://ifconfig.me/ip"
        )
        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "G-VPN-V6")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val ip = response.body?.string()?.trim().orEmpty()
                        if (ip.isNotEmpty() && ip.length <= 45) {
                            VpnRuntimeStore.updateTelemetry { it.copy(publicIp = ip) }
                            return@withContext ip
                        }
                    }
                }
            } catch (_: Exception) {
                // Try next endpoint
            }
        }
        "—"
    }
}
