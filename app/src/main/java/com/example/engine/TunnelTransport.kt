package com.example.engine

import com.example.model.TunnelProtocol
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Callback allowing tunnel engines to protect underlying outbound sockets with Android's
 * VpnService.protect(socket) so encrypted tunnel traffic bypasses the TUN routing loop.
 */
fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

/**
 * Bidirectional stream opened through an authenticated SSH or verified TLS/SNI tunnel.
 */
interface TunnelStream : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val isConnected: Boolean
}

data class TunnelSessionInfo(
    val protocol: TunnelProtocol,
    val endpoint: String,
    val handshakeLatencyMs: Long,
    val verifiedTransferBytes: Int,
    val tlsProtocolVersion: String? = null,
    val tlsCipherSuite: String? = null,
    val tlsCertSubject: String? = null,
    val sshHostFingerprint: String? = null
)

/**
 * Contract for independent VPN tunnel transports in Dual Connection Engine.
 */
interface TunnelTransport {
    val protocol: TunnelProtocol

    /**
     * Connects, performs real authentication or TLS 1.2/1.3 certificate-validated handshake,
     * AND verifies real bidirectional data transfer through the tunnel before returning.
     * Throws an exception if any step fails — never fakes success.
     */
    suspend fun connectAndVerify(
        onProgress: (String) -> Unit
    ): TunnelSessionInfo

    /**
     * Opens a forwarded TCP stream to [destHost]:[destPort] over this active tunnel.
     */
    suspend fun openStream(destHost: String, destPort: Int): TunnelStream

    /**
     * Checks live health of the underlying tunnel session.
     */
    fun isAlive(): Boolean

    /**
     * Cleanly closes all sockets and channels.
     */
    fun disconnect()
}
