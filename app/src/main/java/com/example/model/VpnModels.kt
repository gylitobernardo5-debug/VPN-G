package com.example.model

enum class SmartEngineMode(val label: String, val shortLabel: String) {
    AUTO("AUTO (Smart Engine)", "AUTO"),
    SSH_PRIORITY("SSH Priority", "SSH"),
    TLS_PRIORITY("TLS/SNI Priority", "TLS")
}

enum class TunnelProtocol(val displayName: String) {
    SSH("SSH Tunnel"),
    SSH_OVER_TLS("SSH sobre TLS"),
    TLS_SNI("TLS/SNI Tunnel")
}

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

enum class TlsGatewayMode(val label: String) {
    HTTPS_CONNECT("Gateway HTTPS CONNECT (Proxy TLS)"),
    RAW_TLS_STREAM("Gateway TLS Stream Direto")
}

data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val useTlsWrapper: Boolean = false,
    val tlsSniHost: String = "",
    val keepAliveSeconds: Int = 25,
    val verifyHostKeyTofu: Boolean = true
) {
    fun isConfigured(): Boolean =
        host.isNotBlank() && port in 1..65535 && username.isNotBlank() && password.isNotEmpty()

    fun endpointLabel(): String = if (host.isBlank()) "—" else "$host:$port"
}

data class TlsSniConfig(
    val gatewayHost: String = "",
    val port: Int = 443,
    val sniHostname: String = "",
    val gatewayMode: TlsGatewayMode = TlsGatewayMode.HTTPS_CONNECT,
    val customPath: String = "/",
    val alpnProtocol: String = "http/1.1"
) {
    fun isConfigured(): Boolean =
        gatewayHost.isNotBlank() && port in 1..65535

    fun effectiveSni(): String = sniHostname.trim().ifBlank { gatewayHost.trim() }

    fun endpointLabel(): String = if (gatewayHost.isBlank()) "—" else "$gatewayHost:$port"
}

data class SpeedEngineConfig(
    val bufferSizeKb: Int = 64,
    val tcpNoDelay: Boolean = true,
    val keepAliveEnabled: Boolean = true,
    val keepAliveIntervalSec: Int = 25,
    val mtu: Int = 1500,
    val maxConcurrentStreams: Int = 64,
    val autoReconnect: Boolean = true
) {
    val bufferSizeBytes: Int get() = bufferSizeKb.coerceIn(8, 256) * 1024
}

data class VpnConfig(
    val smartMode: SmartEngineMode = SmartEngineMode.AUTO,
    val ssh: SshConfig = SshConfig(),
    val tls: TlsSniConfig = TlsSniConfig(),
    val speed: SpeedEngineConfig = SpeedEngineConfig()
)

data class MobileNetworkState(
    val isConnected: Boolean = false,
    val isCellular: Boolean = false,
    val transportLabel: String = "Sem rede",
    val localIp: String = "—",
    val dnsServers: List<String> = emptyList(),
    val latencyMs: Long? = null,
    val packetLossPercent: Float = 0f,
    val downstreamKbps: Int = 0,
    val upstreamKbps: Int = 0,
    val ipChangeCount: Int = 0,
    val lastUpdatedEpochMs: Long = 0L
)

data class VpnTelemetry(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val statusStep: String = "Pronto para iniciar",
    val activeProtocol: TunnelProtocol? = null,
    val activeServer: String = "—",
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val pingMs: Long? = null,
    val publicIp: String = "—",
    val connectedSinceEpochMs: Long? = null,
    val lastError: String? = null,
    val tlsProtocolVersion: String? = null,
    val tlsCipherSuite: String? = null,
    val tlsCertSubject: String? = null,
    val sshHostFingerprint: String? = null,
    val activeStreams: Int = 0,
    val reconnectAttempts: Int = 0
)

enum class LogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

data class BenchmarkProgress(
    val isRunning: Boolean = false,
    val progressFraction: Float = 0f,
    val currentStage: String = "Aguardando início",
    val completedStages: Int = 0,
    val totalStages: Int = 8
)

data class BenchmarkSnapshot(
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val speedTestSource: String = "—",
    val pingMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val packetLossPercent: Double = 0.0,
    val connectionLatencyMs: Double = 0.0,
    val sshSetupMs: Double? = null,
    val sshSetupStatus: String = "Não testado",
    val tlsSetupMs: Double? = null,
    val tlsSetupStatus: String = "Não testado",
    val tlsNegotiatedProtocol: String = "—",
    val loopbackTcpMbps: Double = 0.0,
    val loopbackRttMs: Double = 0.0,
    val packetCodecPps: Long = 0L,
    val packetCodecMbps: Double = 0.0,
    val packetCodecAvgUs: Double = 0.0,
    val ramUsedMb: Double = 0.0,
    val ramTotalMb: Double = 0.0,
    val ramDeltaMb: Double = 0.0,
    val stabilityPercent: Double = 0.0,
    val performanceScore: Int = 0,
    val primaryBottleneck: String = "—",
    val recommendations: List<String> = emptyList()
)
