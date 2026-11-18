package com.example.engine

import com.example.data.VpnRuntimeStore
import com.example.model.LogLevel
import com.example.model.SpeedEngineConfig
import com.example.model.TlsGatewayMode
import com.example.model.TlsSniConfig
import com.example.model.TunnelProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Independent TLS/SNI Tunnel Engine (does NOT require an SSH account).
 * Security & Honesty Guarantees:
 * - Enforces TLS 1.2 and TLS 1.3 only.
 * - Uses system default X509TrustManager and strict HTTPS hostname verification
 *   (endpointIdentificationAlgorithm = "HTTPS"). Certificate validation is NEVER disabled.
 * - Protects outbound sockets via VpnService.protect() to avoid TUN loops.
 * - Requires a real reachable TLS gateway and verifies real data transfer before declaring CONNECTED.
 */
class TlsSniTunnelEngine(
    private val tlsConfig: TlsSniConfig,
    private val speedConfig: SpeedEngineConfig,
    private val socketProtector: SocketProtector
) : TunnelTransport {

    override val protocol: TunnelProtocol = TunnelProtocol.TLS_SNI

    private val active = AtomicBoolean(false)

    @Volatile
    private var controlSocket: SSLSocket? = null

    override suspend fun connectAndVerify(
        onProgress: (String) -> Unit
    ): TunnelSessionInfo = withContext(Dispatchers.IO) {
        if (!tlsConfig.isConfigured()) {
            throw IllegalStateException(
                "Configuração TLS/SNI incompleta. Indique um Gateway TLS real (Host/IP e Porta)."
            )
        }

        val startNs = System.nanoTime()
        val sniHost = tlsConfig.effectiveSni()
        onProgress("A negociar TLS 1.2/1.3 com ${tlsConfig.endpointLabel()} (SNI: $sniHost)…")

        val probeSocket = openVerifiedTlsSocket(sniHost)
        try {
            val sslSession = probeSocket.session
            val tlsProto = sslSession.protocol
            val cipher = sslSession.cipherSuite
            val certSubject = (sslSession.peerCertificates.firstOrNull() as? X509Certificate)
                ?.subjectX500Principal?.name ?: "Certificado X.509 validado"

            val handshakeMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            VpnRuntimeStore.log(
                LogLevel.SUCCESS,
                "TLS/SNI",
                "Handshake TLS válido em ${handshakeMs}ms • $tlsProto • $cipher • SNI=$sniHost"
            )

            onProgress("A verificar gateway TLS e transferência real de dados…")
            val verifiedBytes = verifyGatewayTransfer(probeSocket, sniHost)

            controlSocket = probeSocket
            active.set(true)

            VpnRuntimeStore.log(
                LogLevel.SUCCESS,
                "TLS/SNI",
                "Gateway TLS confirmado com transferência real ($verifiedBytes bytes recebidos)."
            )

            TunnelSessionInfo(
                protocol = TunnelProtocol.TLS_SNI,
                endpoint = tlsConfig.endpointLabel(),
                handshakeLatencyMs = handshakeMs,
                verifiedTransferBytes = verifiedBytes,
                tlsProtocolVersion = tlsProto,
                tlsCipherSuite = cipher,
                tlsCertSubject = certSubject
            )
        } catch (e: Exception) {
            runCatching { probeSocket.close() }
            active.set(false)
            throw e
        }
    }

    private fun openVerifiedTlsSocket(sniHost: String): SSLSocket {
        val rawSocket = Socket()
        socketProtector.protect(rawSocket)
        rawSocket.tcpNoDelay = speedConfig.tcpNoDelay
        rawSocket.keepAlive = speedConfig.keepAliveEnabled
        rawSocket.receiveBufferSize = speedConfig.bufferSizeBytes
        rawSocket.sendBufferSize = speedConfig.bufferSizeBytes
        rawSocket.soTimeout = 10_000
        rawSocket.connect(InetSocketAddress(tlsConfig.gatewayHost.trim(), tlsConfig.port), 10_000)

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = sslFactory.createSocket(
            rawSocket,
            sniHost,
            tlsConfig.port,
            true
        ) as SSLSocket

        // Enforce TLS 1.3 / TLS 1.2 exclusively
        val supported = sslSocket.supportedProtocols.toSet()
        val enabled = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        if (enabled.isEmpty()) {
            rawSocket.close()
            throw IOException("Dispositivo não suporta TLS 1.2/1.3.")
        }
        sslSocket.enabledProtocols = enabled.toTypedArray()

        // Strict SNI + HTTPS certificate hostname verification (never disabled)
        val params = SSLParameters().apply {
            serverNames = listOf(SNIHostName(sniHost))
            endpointIdentificationAlgorithm = "HTTPS"
        }
        sslSocket.sslParameters = params
        sslSocket.tcpNoDelay = speedConfig.tcpNoDelay
        sslSocket.keepAlive = speedConfig.keepAliveEnabled
        sslSocket.soTimeout = 10_000
        sslSocket.startHandshake()

        // Ensure certificate chain was actually verified by default TrustManager
        if (sslSocket.session.peerCertificates.isEmpty()) {
            sslSocket.close()
            throw IOException("Validação TLS falhou: servidor não apresentou certificado X.509.")
        }
        return sslSocket
    }

    private fun verifyGatewayTransfer(socket: SSLSocket, sniHost: String): Int {
        val out = socket.outputStream
        val input = BufferedInputStream(socket.inputStream, 4096)

        return when (tlsConfig.gatewayMode) {
            TlsGatewayMode.HTTPS_CONNECT -> {
                // Verify the endpoint is a genuine HTTPS CONNECT TLS gateway capable of tunneling
                val connectReq = buildString {
                    append("CONNECT 1.1.1.1:80 HTTP/1.1\r\n")
                    append("Host: 1.1.1.1:80\r\n")
                    append("User-Agent: G-VPN-V6\r\n")
                    append("Proxy-Connection: Keep-Alive\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                out.write(connectReq)
                out.flush()

                val statusLine = readHttpResponseHeader(input)
                if (!statusLine.startsWith("HTTP/1.1 200") && !statusLine.startsWith("HTTP/1.0 200")) {
                    throw IOException(
                        "Gateway TLS recusou túnel CONNECT ($statusLine). " +
                            "Verifique se o servidor é um gateway HTTPS/TLS compatível (o SNI sozinho não cria túnel)."
                    )
                }

                // Now push real HTTP HEAD bytes through the established CONNECT tunnel
                val innerReq = "HEAD / HTTP/1.1\r\nHost: 1.1.1.1\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
                out.write(innerReq)
                out.flush()

                val responseBuf = ByteArray(512)
                val read = input.read(responseBuf)
                if (read <= 0) {
                    throw IOException("Túnel TLS CONNECT aberto mas sem transferência real de dados.")
                }
                read
            }

            TlsGatewayMode.RAW_TLS_STREAM -> {
                val path = tlsConfig.customPath.ifBlank { "/" }
                val probeReq = buildString {
                    append("HEAD $path HTTP/1.1\r\n")
                    append("Host: $sniHost\r\n")
                    append("Connection: keep-alive\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                out.write(probeReq)
                out.flush()

                val buf = ByteArray(512)
                val read = input.read(buf)
                if (read <= 0) {
                    throw IOException("Gateway TLS Stream não respondeu a dados reais.")
                }
                read
            }
        }
    }

    override suspend fun openStream(destHost: String, destPort: Int): TunnelStream =
        withContext(Dispatchers.IO) {
            if (!active.get()) {
                throw IOException("Motor TLS/SNI não está conectado.")
            }

            val sniHost = tlsConfig.effectiveSni()
            val streamSocket = openVerifiedTlsSocket(sniHost)
            try {
                streamSocket.soTimeout = 30_000
                val out = streamSocket.outputStream
                val input = BufferedInputStream(streamSocket.inputStream, speedConfig.bufferSizeBytes)

                if (tlsConfig.gatewayMode == TlsGatewayMode.HTTPS_CONNECT) {
                    val req = buildString {
                        append("CONNECT $destHost:$destPort HTTP/1.1\r\n")
                        append("Host: $destHost:$destPort\r\n")
                        append("Proxy-Connection: Keep-Alive\r\n\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    out.write(req)
                    out.flush()

                    val statusLine = readHttpResponseHeader(input)
                    if (!statusLine.startsWith("HTTP/1.1 200") && !statusLine.startsWith("HTTP/1.0 200")) {
                        throw IOException("Gateway TLS recusou encaminhamento para $destHost:$destPort ($statusLine)")
                    }
                }

                object : TunnelStream {
                    override val inputStream: InputStream = input
                    override val outputStream: OutputStream = out
                    override val isConnected: Boolean
                        get() = !streamSocket.isClosed && streamSocket.isConnected

                    override fun close() {
                        runCatching { streamSocket.close() }
                    }
                }
            } catch (e: Exception) {
                runCatching { streamSocket.close() }
                throw e
            }
        }

    override fun isAlive(): Boolean = active.get()

    override fun disconnect() {
        active.set(false)
        runCatching { controlSocket?.close() }
        controlSocket = null
    }

    private fun readHttpResponseHeader(input: InputStream): String {
        val headerBytes = ByteArray(2048)
        var pos = 0
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        while (pos < headerBytes.size) {
            val b = input.read()
            if (b == -1) break
            headerBytes[pos++] = b.toByte()
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                break
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        val text = String(headerBytes, 0, pos, Charsets.US_ASCII)
        return text.lineSequence().firstOrNull()?.trim().orEmpty()
            .ifEmpty { "Sem resposta HTTP do gateway" }
    }
}
