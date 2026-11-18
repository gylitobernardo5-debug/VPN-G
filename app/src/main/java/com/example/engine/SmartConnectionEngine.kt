package com.example.engine

import com.example.data.VpnConfigStore
import com.example.data.VpnRuntimeStore
import com.example.model.LogLevel
import com.example.model.SmartEngineMode
import com.example.model.TunnelProtocol
import com.example.model.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class SmartConnectionResult(
    val transport: TunnelTransport,
    val sessionInfo: TunnelSessionInfo
)

/**
 * SMART ENGINE V6:
 * - AUTO: probes configured SSH and TLS/SNI endpoints in parallel for reachability & RTT,
 *   selects the fastest available candidate, validates auth/TLS + real data transfer,
 *   and automatically fails over to the secondary method if the primary fails.
 * - SSH_PRIORITY: attempts SSH first, falling back to TLS/SNI if configured.
 * - TLS_PRIORITY: attempts TLS/SNI first, falling back to SSH if configured.
 */
class SmartConnectionEngine(
    private val configStore: VpnConfigStore,
    private val socketProtector: SocketProtector
) {
    suspend fun establishBestTunnel(
        config: VpnConfig,
        onProgress: (String) -> Unit
    ): SmartConnectionResult {
        val sshReady = config.ssh.isConfigured()
        val tlsReady = config.tls.isConfigured()

        if (!sshReady && !tlsReady) {
            throw IllegalStateException(
                "Nenhum servidor configurado. Aceda ao separador Config e configure um servidor SSH ou Gateway TLS/SNI real."
            )
        }

        val orderedCandidates = buildCandidateOrder(config, sshReady, tlsReady, onProgress)
        val failures = mutableListOf<String>()

        for (candidate in orderedCandidates) {
            val engine: TunnelTransport = when (candidate) {
                TunnelProtocol.SSH, TunnelProtocol.SSH_OVER_TLS ->
                    SshTunnelEngine(config.ssh, config.speed, configStore, socketProtector)

                TunnelProtocol.TLS_SNI ->
                    TlsSniTunnelEngine(config.tls, config.speed, socketProtector)
            }

            try {
                VpnRuntimeStore.log(
                    LogLevel.INFO,
                    "SMART",
                    "A iniciar motor ${engine.protocol.displayName} (modo ${config.smartMode.shortLabel})…"
                )
                val info = engine.connectAndVerify(onProgress)
                return SmartConnectionResult(transport = engine, sessionInfo = info)
            } catch (e: Exception) {
                engine.disconnect()
                val msg = "${engine.protocol.displayName}: ${e.message ?: e::class.java.simpleName}"
                failures += msg
                VpnRuntimeStore.log(LogLevel.WARN, "SMART", "Falha em $msg")
            }
        }

        throw IOException(
            "Não foi possível estabelecer túnel verificado. Detalhes: ${failures.joinToString(" | ")}"
        )
    }

    internal suspend fun buildCandidateOrder(
        config: VpnConfig,
        sshReady: Boolean,
        tlsReady: Boolean,
        onProgress: (String) -> Unit
    ): List<TunnelProtocol> {
        val sshProto = if (config.ssh.useTlsWrapper) TunnelProtocol.SSH_OVER_TLS else TunnelProtocol.SSH
        return when (config.smartMode) {
            SmartEngineMode.SSH_PRIORITY -> buildList {
                if (sshReady) add(sshProto)
                if (tlsReady) add(TunnelProtocol.TLS_SNI)
            }

            SmartEngineMode.TLS_PRIORITY -> buildList {
                if (tlsReady) add(TunnelProtocol.TLS_SNI)
                if (sshReady) add(sshProto)
            }

            SmartEngineMode.AUTO -> {
                if (sshReady && !tlsReady) return listOf(sshProto)
                if (tlsReady && !sshReady) return listOf(TunnelProtocol.TLS_SNI)

                onProgress("Smart Engine AUTO: a medir disponibilidade e latência SSH vs TLS…")
                coroutineScope {
                    val sshRttDeferred = async {
                        measureTcpConnectRtt(config.ssh.host.trim(), config.ssh.port)
                    }
                    val tlsRttDeferred = async {
                        measureTcpConnectRtt(config.tls.gatewayHost.trim(), config.tls.port)
                    }
                    val sshRtt = sshRttDeferred.await()
                    val tlsRtt = tlsRttDeferred.await()

                    VpnRuntimeStore.log(
                        LogLevel.INFO,
                        "SMART",
                        "Sonda AUTO • SSH=${sshRtt?.let { "${it}ms" } ?: "inacessível"} | TLS=${tlsRtt?.let { "${it}ms" } ?: "inacessível"}"
                    )

                    when {
                        sshRtt == null && tlsRtt != null -> listOf(TunnelProtocol.TLS_SNI, sshProto)
                        tlsRtt == null && sshRtt != null -> listOf(sshProto, TunnelProtocol.TLS_SNI)
                        sshRtt != null && tlsRtt != null && tlsRtt < sshRtt ->
                            listOf(TunnelProtocol.TLS_SNI, sshProto)
                        else -> listOf(sshProto, TunnelProtocol.TLS_SNI)
                    }
                }
            }
        }
    }

    private suspend fun measureTcpConnectRtt(host: String, port: Int): Long? =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socketProtector.protect(socket)
                val startNs = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), 3_500)
                ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { socket.close() }
            }
        }
}
