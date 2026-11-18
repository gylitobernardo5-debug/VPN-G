package com.example.engine

import com.example.data.VpnConfigStore
import com.example.data.VpnRuntimeStore
import com.example.model.LogLevel
import com.example.model.SpeedEngineConfig
import com.example.model.SshConfig
import com.example.model.TunnelProtocol
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Real SSH-2 Tunnel Engine using mwiede/JSch.
 * Features:
 * - Real SSH-2 password authentication + modern OpenSSH ciphers
 * - Optional SSH-over-TLS wrapper (TLS 1.2 / 1.3 with SNI + strict X.509 certificate validation)
 * - VpnService.protect() socket protection against routing loops
 * - Extreme Speed socket tuning (TCP_NODELAY, SO_KEEPALIVE, configurable buffers)
 * - SHA-256 TOFU (Trust On First Use) SSH host key verification
 * - Real direct-tcpip data transfer verification before declaring CONNECTED
 */
class SshTunnelEngine(
    private val sshConfig: SshConfig,
    private val speedConfig: SpeedEngineConfig,
    private val configStore: VpnConfigStore,
    private val socketProtector: SocketProtector
) : TunnelTransport {

    override val protocol: TunnelProtocol
        get() = if (sshConfig.useTlsWrapper) TunnelProtocol.SSH_OVER_TLS else TunnelProtocol.SSH

    @Volatile
    private var session: Session? = null

    @Volatile
    private var negotiatedTlsVersion: String? = null

    @Volatile
    private var negotiatedTlsCipher: String? = null

    @Volatile
    private var verifiedTlsSubject: String? = null

    @Volatile
    private var verifiedSshFingerprint: String? = null

    override suspend fun connectAndVerify(
        onProgress: (String) -> Unit
    ): TunnelSessionInfo = withContext(Dispatchers.IO) {
        if (!sshConfig.isConfigured()) {
            throw IllegalStateException(
                "Configuração SSH incompleta. Indique Host/IP, Porta, Utilizador e Palavra-passe reais."
            )
        }

        val startNs = System.nanoTime()
        onProgress(
            if (sshConfig.useTlsWrapper) {
                "A negociar TLS 1.2/1.3 (SNI) para transporte SSH…"
            } else {
                "A estabelecer ligação TCP com ${sshConfig.endpointLabel()}…"
            }
        )

        val jsch = JSch()
        val hostPortKey = "${sshConfig.host.trim()}:${sshConfig.port}"

        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, key: ByteArray): Int {
                val sha256 = computeSha256Fingerprint(key)
                verifiedSshFingerprint = "SHA256:$sha256"
                if (!sshConfig.verifyHostKeyTofu) {
                    VpnRuntimeStore.log(
                        LogLevel.WARN,
                        "SSH",
                        "Host key aceite (TOFU desativado): SHA256:$sha256"
                    )
                    return HostKeyRepository.OK
                }
                val saved = runBlocking { configStore.getTrustedHostKey(hostPortKey) }
                return when {
                    saved == null -> {
                        runBlocking { configStore.saveTrustedHostKey(hostPortKey, sha256) }
                        VpnRuntimeStore.log(
                            LogLevel.INFO,
                            "SSH",
                            "Chave do servidor registada (TOFU): SHA256:$sha256"
                        )
                        HostKeyRepository.OK
                    }
                    saved == sha256 -> {
                        VpnRuntimeStore.log(
                            LogLevel.SUCCESS,
                            "SSH",
                            "Assinatura SSH verificada: SHA256:$sha256"
                        )
                        HostKeyRepository.OK
                    }
                    else -> {
                        VpnRuntimeStore.log(
                            LogLevel.ERROR,
                            "SSH",
                            "ALERTA DE SEGURANÇA: Chave SSH alterada para $hostPortKey! Esperada=$saved Recebida=$sha256"
                        )
                        HostKeyRepository.CHANGED
                    }
                }
            }

            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID(): String = "gvpn-tofu-store"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }

        val newSession = jsch.getSession(
            sshConfig.username.trim(),
            sshConfig.host.trim(),
            sshConfig.port
        )
        newSession.setPassword(sshConfig.password)
        newSession.setConfig("StrictHostKeyChecking", "yes")
        newSession.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        newSession.setConfig("compression.s2c", "none")
        newSession.setConfig("compression.c2s", "none")
        newSession.timeout = 12_000

        val keepAliveMs = if (speedConfig.keepAliveEnabled) {
            (sshConfig.keepAliveSeconds.coerceAtLeast(5)) * 1000
        } else {
            0
        }
        if (keepAliveMs > 0) {
            newSession.serverAliveInterval = keepAliveMs
            newSession.serverAliveCountMax = 3
        }

        newSession.setSocketFactory(createProtectedSocketFactory())

        try {
            onProgress("A autenticar sessão SSH-2 em ${sshConfig.endpointLabel()}…")
            newSession.connect(12_000)
            if (!newSession.isConnected) {
                throw IOException("Sessão SSH terminou inesperadamente após autenticação.")
            }
            session = newSession

            val handshakeMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            VpnRuntimeStore.log(
                LogLevel.SUCCESS,
                "SSH",
                "Autenticação SSH-2 concluída em ${handshakeMs}ms (${newSession.serverVersion})"
            )

            onProgress("A verificar encaminhamento real de dados no túnel SSH…")
            val verifiedBytes = verifyDirectTcpIpTransfer(newSession)
            VpnRuntimeStore.log(
                LogLevel.SUCCESS,
                "SSH",
                "Transferência real confirmada através do túnel SSH ($verifiedBytes bytes recebidos)."
            )

            TunnelSessionInfo(
                protocol = protocol,
                endpoint = sshConfig.endpointLabel(),
                handshakeLatencyMs = handshakeMs,
                verifiedTransferBytes = verifiedBytes,
                tlsProtocolVersion = negotiatedTlsVersion,
                tlsCipherSuite = negotiatedTlsCipher,
                tlsCertSubject = verifiedTlsSubject,
                sshHostFingerprint = verifiedSshFingerprint
            )
        } catch (e: Exception) {
            runCatching { newSession.disconnect() }
            session = null
            throw e
        }
    }

    private fun verifyDirectTcpIpTransfer(activeSession: Session): Int {
        // Real data transfer check via direct-tcpip port forwarding to verify the SSH server
        // actually allows TCP forwarding and moves live bytes end-to-end.
        val probeTargets = listOf(
            "1.1.1.1" to 80,
            "8.8.8.8" to 53
        )
        var lastError: Exception? = null
        for ((host, port) in probeTargets) {
            var channel: ChannelDirectTCPIP? = null
            try {
                channel = activeSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost(host)
                channel.setPort(port)
                channel.setOrgIPAddress("127.0.0.1")
                channel.setOrgPort(0)
                val input = channel.inputStream
                val output = channel.outputStream
                channel.connect(8_000)

                if (port == 80) {
                    val req = "HEAD / HTTP/1.1\r\nHost: 1.1.1.1\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                    output.write(req)
                    output.flush()
                    val buf = ByteArray(512)
                    val read = input.read(buf)
                    if (read > 0) {
                        return read
                    }
                    throw IOException("Canal SSH direct-tcpip não devolveu dados de resposta.")
                } else {
                    // Minimal TCP DNS query (2-byte length prefix + standard query for ".")
                    val dnsTcpQuery = byteArrayOf(
                        0x00, 0x11, // Length = 17
                        0x12, 0x34, // Tx ID
                        0x01, 0x00, // Standard recursive query
                        0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00,       // Root "."
                        0x00, 0x02, // NS
                        0x00, 0x01  // IN
                    )
                    output.write(dnsTcpQuery)
                    output.flush()
                    val buf = ByteArray(256)
                    val read = input.read(buf)
                    if (read > 0) return read
                    throw IOException("Consulta DNS via túnel SSH sem resposta.")
                }
            } catch (e: Exception) {
                lastError = e
            } finally {
                runCatching { channel?.disconnect() }
            }
        }
        throw IOException(
            "Autenticação SSH ok, mas o servidor recusou encaminhamento TCP (AllowTcpForwarding) ou falhou a transferência real: ${lastError?.message}"
        )
    }

    override suspend fun openStream(destHost: String, destPort: Int): TunnelStream =
        withContext(Dispatchers.IO) {
            val activeSession = session?.takeIf { it.isConnected }
                ?: throw IOException("Sessão SSH não está ativa.")

            val channel = activeSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(destHost)
            channel.setPort(destPort)
            channel.setOrgIPAddress("10.88.0.2")
            channel.setOrgPort(0)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(8_000)

            object : TunnelStream {
                override val inputStream: InputStream = input
                override val outputStream: OutputStream = output
                override val isConnected: Boolean
                    get() = channel.isConnected && !channel.isClosed

                override fun close() {
                    runCatching { channel.disconnect() }
                }
            }
        }

    override fun isAlive(): Boolean = session?.isConnected == true

    override fun disconnect() {
        runCatching { session?.disconnect() }
        session = null
    }

    private fun createProtectedSocketFactory(): SocketFactory = object : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val rawSocket = Socket()
            socketProtector.protect(rawSocket)
            rawSocket.tcpNoDelay = speedConfig.tcpNoDelay
            rawSocket.keepAlive = speedConfig.keepAliveEnabled
            rawSocket.receiveBufferSize = speedConfig.bufferSizeBytes
            rawSocket.sendBufferSize = speedConfig.bufferSizeBytes
            rawSocket.connect(InetSocketAddress(host, port), 10_000)

            if (!sshConfig.useTlsWrapper) {
                return rawSocket
            }

            // SSH over TLS 1.2 / 1.3 with strict certificate & hostname validation
            val sniHost = sshConfig.tlsSniHost.trim().ifBlank { host }
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(rawSocket, sniHost, port, true) as SSLSocket
            val supported = sslSocket.supportedProtocols.toSet()
            val desired = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
            if (desired.isNotEmpty()) {
                sslSocket.enabledProtocols = desired.toTypedArray()
            }
            val sslParams = SSLParameters().apply {
                serverNames = listOf(SNIHostName(sniHost))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            sslSocket.sslParameters = sslParams
            sslSocket.tcpNoDelay = speedConfig.tcpNoDelay
            sslSocket.keepAlive = speedConfig.keepAliveEnabled
            sslSocket.startHandshake()

            val sslSession = sslSocket.session
            negotiatedTlsVersion = sslSession.protocol
            negotiatedTlsCipher = sslSession.cipherSuite
            verifiedTlsSubject = (sslSession.peerCertificates.firstOrNull() as? X509Certificate)
                ?.subjectX500Principal?.name

            VpnRuntimeStore.log(
                LogLevel.SUCCESS,
                "SSH/TLS",
                "TLS validado: ${sslSession.protocol} • ${sslSession.cipherSuite} • SNI=$sniHost"
            )
            return sslSocket
        }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    private fun computeSha256Fingerprint(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        return Base64.getEncoder().encodeToString(digest).trimEnd('=')
    }
}
