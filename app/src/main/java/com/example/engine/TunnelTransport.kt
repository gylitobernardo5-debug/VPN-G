package com.example.engine

import com.example.model.TunnelProtocol
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

fun interface SocketProtector { fun protect(socket: Socket): Boolean }

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
    val tlsCertSubject: String? = null
)

interface TunnelTransport {
    val protocol: TunnelProtocol
    suspend fun connectAndVerify(onProgress: (String) -> Unit): TunnelSessionInfo
    suspend fun openStream(destHost: String, destPort: Int): TunnelStream
    fun isAlive(): Boolean
    fun disconnect()
}
