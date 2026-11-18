package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.VpnConfigStore
import com.example.data.VpnRuntimeStore
import com.example.engine.ExtremeSpeedTunEngine
import com.example.engine.SmartConnectionEngine
import com.example.engine.SocketProtector
import com.example.engine.TunnelTransport
import com.example.model.LogLevel
import com.example.model.VpnConfig
import com.example.model.VpnConnectionState
import com.example.network.MobileNetworkEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Background Engine (VpnService):
 * - Runs as an Android 14+ compliant Foreground Service (FOREGROUND_SERVICE_TYPE_SPECIAL_USE).
 * - Displays permanent live notification with Disconnect action button.
 * - Survives UI closure and automatically recovers from mobile network oscillations/IP changes.
 * - Declares CONNECTED strictly AFTER verifying auth/TLS, TUN setup, and real data transfer.
 */
class GVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.gvpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.gvpn.ACTION_DISCONNECT"

        private const val NOTIFICATION_CHANNEL_ID = "gvpn_v6_tunnel_channel"
        private const val NOTIFICATION_ID = 6001
        private const val MAX_RECONNECT_ATTEMPTS = 5

        fun startConnect(context: Context) {
            val intent = Intent(context, GVpnService::class.java).apply {
                action = ACTION_CONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startDisconnect(context: Context) {
            val intent = Intent(context, GVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var configStore: VpnConfigStore
    private lateinit var smartEngine: SmartConnectionEngine
    private lateinit var networkEngine: MobileNetworkEngine

    private val isDisconnecting = AtomicBoolean(false)
    private var connectionJob: Job? = null
    private var notificationTickerJob: Job? = null

    @Volatile
    private var activeTransport: TunnelTransport? = null

    @Volatile
    private var activeTunInterface: ParcelFileDescriptor? = null

    @Volatile
    private var activeTunEngine: ExtremeSpeedTunEngine? = null

    override fun onCreate() {
        super.onCreate()
        configStore = VpnConfigStore(applicationContext)
        smartEngine = SmartConnectionEngine(
            configStore = configStore,
            socketProtector = SocketProtector { socket -> protect(socket) }
        )
        networkEngine = MobileNetworkEngine(
            context = applicationContext,
            onTransportChanged = { reason -> handleNetworkOscillation(reason) }
        )
        createNotificationChannel()
        networkEngine.startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectAndStop("Desligado pelo utilizador")
                return START_NOT_STICKY
            }

            ACTION_CONNECT, null -> {
                isDisconnecting.set(false)
                promoteToForeground(
                    title = "G-VPN V6 • A conectar…",
                    content = "A inicializar Smart Engine V6"
                )
                initiateConnectionSequence(isReconnect = false)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun initiateConnectionSequence(isReconnect: Boolean) {
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            val config = configStore.getConfig()
            val nextAttempt = if (isReconnect) {
                VpnRuntimeStore.telemetry.value.reconnectAttempts + 1
            } else {
                0
            }

            teardownActiveTunnelOnly()

            VpnRuntimeStore.updateTelemetry {
                it.copy(
                    state = if (isReconnect) VpnConnectionState.RECONNECTING else VpnConnectionState.CONNECTING,
                    statusStep = if (isReconnect) {
                        "A recuperar túnel (tentativa $nextAttempt/$MAX_RECONNECT_ATTEMPTS)…"
                    } else {
                        "A validar configuração ${config.smartMode.shortLabel}…"
                    },
                    lastError = null,
                    reconnectAttempts = nextAttempt,
                    downloadBytesPerSec = 0L,
                    uploadBytesPerSec = 0L
                )
            }

            updateNotification(
                title = "G-VPN V6 • ${if (isReconnect) "A reconectar" else "A conectar"}",
                content = "Smart Engine (${config.smartMode.shortLabel}) em curso…"
            )

            try {
                // Step 1: Establish & cryptographically verify tunnel + real data transfer
                val result = smartEngine.establishBestTunnel(config) { step ->
                    VpnRuntimeStore.updateTelemetry { it.copy(statusStep = step) }
                    updateNotification("G-VPN V6 • A conectar", step)
                }

                // Step 2: Establish Android VpnService TUN interface
                VpnRuntimeStore.updateTelemetry {
                    it.copy(statusStep = "A configurar interface TUN Extreme Speed (MTU ${config.speed.mtu})…")
                }
                val tunFd = establishTunInterface(config)
                    ?: throw IllegalStateException(
                        "Permissão VPN revogada ou indisponível no sistema Android."
                    )

                activeTransport = result.transport
                activeTunInterface = tunFd

                // Step 3: Start Extreme Speed TUN packet forwarding loop
                val tunEngine = ExtremeSpeedTunEngine(
                    tunFd = tunFd,
                    transport = result.transport,
                    speedConfig = config.speed,
                    onTransportLost = { reason ->
                        if (!isDisconnecting.get()) {
                            handleNetworkOscillation(reason)
                        }
                    }
                )
                activeTunEngine = tunEngine
                tunEngine.start()

                val now = System.currentTimeMillis()
                VpnRuntimeStore.updateTelemetry {
                    it.copy(
                        state = VpnConnectionState.CONNECTED,
                        statusStep = "Túnel ativo • Transferência real verificada (${result.sessionInfo.verifiedTransferBytes} B)",
                        activeProtocol = result.sessionInfo.protocol,
                        activeServer = result.sessionInfo.endpoint,
                        pingMs = result.sessionInfo.handshakeLatencyMs,
                        connectedSinceEpochMs = now,
                        lastError = null,
                        tlsProtocolVersion = result.sessionInfo.tlsProtocolVersion,
                        tlsCipherSuite = result.sessionInfo.tlsCipherSuite,
                        tlsCertSubject = result.sessionInfo.tlsCertSubject,
                        sshHostFingerprint = result.sessionInfo.sshHostFingerprint,
                        reconnectAttempts = 0
                    )
                }

                VpnRuntimeStore.log(
                    LogLevel.SUCCESS,
                    "VPN",
                    "CONECTADO via ${result.sessionInfo.protocol.displayName} (${result.sessionInfo.endpoint}) • RTT=${result.sessionInfo.handshakeLatencyMs}ms"
                )

                // Query real public IP asynchronously
                serviceScope.launch { networkEngine.fetchPublicIp() }

                startNotificationUpdater()
            } catch (e: Exception) {
                val message = e.message ?: "Erro desconhecido ao estabelecer túnel"
                VpnRuntimeStore.log(LogLevel.ERROR, "VPN", "Falha na ligação: $message")
                teardownActiveTunnelOnly()

                if (isReconnect && config.speed.autoReconnect && nextAttempt < MAX_RECONNECT_ATTEMPTS && !isDisconnecting.get()) {
                    val backoffMs = (2_000L * nextAttempt).coerceAtMost(12_000L)
                    VpnRuntimeStore.log(
                        LogLevel.WARN,
                        "VPN",
                        "Nova tentativa automática em ${backoffMs / 1000}s…"
                    )
                    delay(backoffMs)
                    if (!isDisconnecting.get()) {
                        initiateConnectionSequence(isReconnect = true)
                    }
                } else {
                    VpnRuntimeStore.updateTelemetry {
                        it.copy(
                            state = VpnConnectionState.ERROR,
                            statusStep = "Ligação falhou",
                            lastError = message,
                            connectedSinceEpochMs = null,
                            downloadBytesPerSec = 0L,
                            uploadBytesPerSec = 0L
                        )
                    }
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun establishTunInterface(config: VpnConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("G-VPN Smart Engine V6")
            .setMtu(config.speed.mtu.coerceIn(1280, 1500))
            .addAddress("10.88.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        // Exclude our own app package from TUN to guarantee zero routing loops on protected/management calls
        runCatching { builder.addDisallowedApplication(packageName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder.establish()
    }

    private fun handleNetworkOscillation(reason: String) {
        val currentState = VpnRuntimeStore.telemetry.value.state
        if (isDisconnecting.get()) return
        if (currentState != VpnConnectionState.CONNECTED) return

        serviceScope.launch {
            val config = configStore.getConfig()
            if (!config.speed.autoReconnect) {
                VpnRuntimeStore.log(
                    LogLevel.WARN,
                    "VPN",
                    "Oscilação detetada ($reason) mas reconexão automática está desativada."
                )
                return@launch
            }
            VpnRuntimeStore.log(
                LogLevel.WARN,
                "VPN",
                "Oscilação de rede ($reason) — a iniciar recuperação controlada do túnel…"
            )
            delay(1_500L)
            if (!isDisconnecting.get()) {
                initiateConnectionSequence(isReconnect = true)
            }
        }
    }

    private fun startNotificationUpdater() {
        notificationTickerJob?.cancel()
        notificationTickerJob = serviceScope.launch {
            while (isActive && !isDisconnecting.get()) {
                val t = VpnRuntimeStore.telemetry.value
                if (t.state != VpnConnectionState.CONNECTED) break
                val durationStr = formatDuration(t.connectedSinceEpochMs)
                val proto = t.activeProtocol?.displayName ?: "G-VPN"
                val down = formatRate(t.downloadBytesPerSec)
                val up = formatRate(t.uploadBytesPerSec)
                updateNotification(
                    title = "G-VPN CONECTADO • $proto ($durationStr)",
                    content = "↓ $down  ↑ $up  •  ${t.activeServer}"
                )
                delay(2_000L)
            }
        }
    }

    private fun disconnectAndStop(reason: String) {
        isDisconnecting.set(true)
        connectionJob?.cancel()
        notificationTickerJob?.cancel()
        teardownActiveTunnelOnly()

        VpnRuntimeStore.updateTelemetry {
            it.copy(
                state = VpnConnectionState.DISCONNECTED,
                statusStep = reason,
                activeProtocol = null,
                activeServer = "—",
                downloadBytesPerSec = 0L,
                uploadBytesPerSec = 0L,
                connectedSinceEpochMs = null,
                activeStreams = 0,
                reconnectAttempts = 0,
                lastError = null
            )
        }
        VpnRuntimeStore.log(LogLevel.INFO, "VPN", "DESCONECTADO ($reason)")
        stopForegroundCompat()
        stopSelf()
    }

    private fun teardownActiveTunnelOnly() {
        runCatching { activeTunEngine?.stop() }
        activeTunEngine = null
        runCatching { activeTransport?.disconnect() }
        activeTransport = null
        runCatching { activeTunInterface?.close() }
        activeTunInterface = null
    }

    override fun onRevoke() {
        VpnRuntimeStore.log(LogLevel.WARN, "VPN", "Permissão VPN revogada pelo Android.")
        disconnectAndStop("Permissão VPN revogada")
        super.onRevoke()
    }

    override fun onDestroy() {
        networkEngine.stopMonitoring()
        teardownActiveTunnelOnly()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun promoteToForeground(title: String, content: String) {
        val notification = buildNotification(title, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_disconnect),
                disconnectIntent
            )
            .build()
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun formatDuration(sinceMs: Long?): String {
        if (sinceMs == null) return "00:00:00"
        val sec = ((System.currentTimeMillis() - sinceMs) / 1000L).coerceAtLeast(0L)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun formatRate(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576L -> "%.2f MB/s".format(bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024L -> "%.1f KB/s".format(bytesPerSec / 1_024.0)
        else -> "$bytesPerSec B/s"
    }
}
