package com.example.engine

import android.os.ParcelFileDescriptor
import com.example.data.VpnRuntimeStore
import com.example.model.LogLevel
import com.example.model.SpeedEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Extreme Speed TUN Forwarding Engine:
 * - Reads raw IPv4 packets from Android's VpnService TUN ParcelFileDescriptor using ByteBufferPool.
 * - Parses IPv4/TCP/UDP with zero-copy PacketCodec.
 * - Multiplexes TCP streams and DNS-over-TCP queries across the active SSH or TLS/SNI TunnelTransport.
 * - Tracks accurate real-time rx/tx bytes per second for live dashboard & notification telemetry.
 */
class ExtremeSpeedTunEngine(
    private val tunFd: ParcelFileDescriptor,
    private val transport: TunnelTransport,
    private val speedConfig: SpeedEngineConfig,
    private val onTransportLost: (String) -> Unit
) {
    private data class FlowKey(
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int
    )

    private class TcpSession(
        val key: FlowKey,
        @Volatile var clientSeq: Long,
        @Volatile var serverSeq: Long,
        @Volatile var stream: TunnelStream? = null,
        @Volatile var closed: Boolean = false,
        val writeLock: Any = Any()
    )

    private val running = AtomicBoolean(false)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bufferPool = ByteBufferPool(
        bufferSize = maxOf(speedConfig.mtu + 128, 2048),
        maxPoolSize = 128
    )

    private val tunWriteLock = Any()
    private val tcpSessions = ConcurrentHashMap<FlowKey, TcpSession>()
    private val ipIdCounter = AtomicInteger(1)
    private val totalRxBytes = AtomicLong(0L)
    private val totalTxBytes = AtomicLong(0L)

    private var readerJob: Job? = null
    private var meterJob: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val tunInput = FileInputStream(tunFd.fileDescriptor)
        val tunOutput = FileOutputStream(tunFd.fileDescriptor)

        meterJob = engineScope.launch {
            var prevRx = 0L
            var prevTx = 0L
            while (isActive && running.get()) {
                delay(1_000L)
                val currRx = totalRxBytes.get()
                val currTx = totalTxBytes.get()
                val downBps = (currRx - prevRx).coerceAtLeast(0L)
                val upBps = (currTx - prevTx).coerceAtLeast(0L)
                prevRx = currRx
                prevTx = currTx

                VpnRuntimeStore.updateTelemetry { current ->
                    current.copy(
                        downloadBytesPerSec = downBps,
                        uploadBytesPerSec = upBps,
                        totalRxBytes = currRx,
                        totalTxBytes = currTx,
                        activeStreams = tcpSessions.size
                    )
                }

                if (!transport.isAlive()) {
                    onTransportLost("Túnel interrompido pelo servidor ou rede.")
                    break
                }
            }
        }

        readerJob = engineScope.launch {
            val packetBuf = bufferPool.acquire()
            try {
                while (isActive && running.get()) {
                    val length = tunInput.read(packetBuf)
                    if (length <= 0) {
                        delay(5L)
                        continue
                    }

                    val parsed = PacketCodec.parseIpv4(packetBuf, length) ?: continue
                    when (parsed.protocol) {
                        PacketCodec.PROTO_TCP -> handleTcpPacket(parsed, packetBuf, tunOutput)
                        PacketCodec.PROTO_UDP -> {
                            if (parsed.dstPort == 53 && parsed.payloadLength > 0) {
                                val dnsPayload = packetBuf.copyOfRange(
                                    parsed.payloadOffset,
                                    parsed.payloadOffset + parsed.payloadLength
                                )
                                handleDnsOverTunnel(parsed, dnsPayload, tunOutput)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    VpnRuntimeStore.log(
                        LogLevel.WARN,
                        "TUN",
                        "Loop TUN terminado: ${e.message}"
                    )
                    onTransportLost(e.message ?: "Erro de leitura TUN")
                }
            } finally {
                bufferPool.release(packetBuf)
            }
        }
    }

    private fun handleTcpPacket(
        pkt: PacketCodec.ParsedPacket,
        rawBuffer: ByteArray,
        tunOutput: FileOutputStream
    ) {
        val key = FlowKey(pkt.srcIpInt, pkt.srcPort, pkt.dstIpInt, pkt.dstPort)
        val isSyn = (pkt.tcpFlags and PacketCodec.TCP_SYN) != 0
        val isAck = (pkt.tcpFlags and PacketCodec.TCP_ACK) != 0
        val isFin = (pkt.tcpFlags and PacketCodec.TCP_FIN) != 0
        val isRst = (pkt.tcpFlags and PacketCodec.TCP_RST) != 0

        if (isRst) {
            closeTcpSession(key)
            return
        }

        if (isSyn && !isAck) {
            if (tcpSessions.size >= speedConfig.maxConcurrentStreams) {
                sendTcpReset(pkt, tunOutput)
                return
            }
            val initialServerSeq = (System.nanoTime() and 0x7FFFFFFFL)
            val session = TcpSession(
                key = key,
                clientSeq = (pkt.tcpSeq + 1L) and 0xFFFFFFFFL,
                serverSeq = initialServerSeq
            )
            tcpSessions[key] = session

            engineScope.launch {
                try {
                    val stream = transport.openStream(pkt.dstIpString, pkt.dstPort)
                    session.stream = stream
                    // Send SYN+ACK to local app via TUN
                    writeTcpToTun(
                        tunOutput = tunOutput,
                        session = session,
                        flags = PacketCodec.TCP_SYN or PacketCodec.TCP_ACK,
                        payload = null,
                        payloadLen = 0
                    )
                    session.serverSeq = (session.serverSeq + 1L) and 0xFFFFFFFFL
                    pumpDownstream(session, stream, tunOutput)
                } catch (_: Exception) {
                    closeTcpSession(key)
                    sendTcpReset(pkt, tunOutput)
                }
            }
            return
        }

        val session = tcpSessions[key] ?: return

        if (pkt.payloadLength > 0) {
            val payloadCopy = rawBuffer.copyOfRange(
                pkt.payloadOffset,
                pkt.payloadOffset + pkt.payloadLength
            )
            session.clientSeq = (pkt.tcpSeq + pkt.payloadLength) and 0xFFFFFFFFL
            totalTxBytes.addAndGet(pkt.payloadLength.toLong())

            engineScope.launch {
                try {
                    val activeStream = session.stream
                    if (activeStream != null && activeStream.isConnected) {
                        synchronized(session.writeLock) {
                            activeStream.outputStream.write(payloadCopy)
                            activeStream.outputStream.flush()
                        }
                        writeTcpToTun(
                            tunOutput = tunOutput,
                            session = session,
                            flags = PacketCodec.TCP_ACK,
                            payload = null,
                            payloadLen = 0
                        )
                    }
                } catch (_: Exception) {
                    closeTcpSession(key)
                    sendTcpReset(pkt, tunOutput)
                }
            }
        }

        if (isFin) {
            session.clientSeq = (pkt.tcpSeq + pkt.payloadLength + 1L) and 0xFFFFFFFFL
            writeTcpToTun(
                tunOutput = tunOutput,
                session = session,
                flags = PacketCodec.TCP_FIN or PacketCodec.TCP_ACK,
                payload = null,
                payloadLen = 0
            )
            closeTcpSession(key)
        }
    }

    private fun pumpDownstream(
        session: TcpSession,
        stream: TunnelStream,
        tunOutput: FileOutputStream
    ) {
        val maxSegment = (speedConfig.mtu - 40).coerceIn(512, 1460)
        val readBuf = ByteArray(maxSegment)
        try {
            while (running.get() && !session.closed && stream.isConnected) {
                val read = stream.inputStream.read(readBuf)
                if (read <= 0) break
                totalRxBytes.addAndGet(read.toLong())
                writeTcpToTun(
                    tunOutput = tunOutput,
                    session = session,
                    flags = PacketCodec.TCP_PSH or PacketCodec.TCP_ACK,
                    payload = readBuf,
                    payloadLen = read
                )
                session.serverSeq = (session.serverSeq + read.toLong()) and 0xFFFFFFFFL
            }
        } catch (_: Exception) {
            // Stream closed by remote end
        } finally {
            if (!session.closed) {
                writeTcpToTun(
                    tunOutput = tunOutput,
                    session = session,
                    flags = PacketCodec.TCP_FIN or PacketCodec.TCP_ACK,
                    payload = null,
                    payloadLen = 0
                )
            }
            closeTcpSession(session.key)
        }
    }

    /**
     * Forwards UDP port 53 DNS queries as RFC 1035 TCP DNS over the encrypted SSH/TLS tunnel.
     */
    private fun handleDnsOverTunnel(
        pkt: PacketCodec.ParsedPacket,
        dnsQuery: ByteArray,
        tunOutput: FileOutputStream
    ) {
        totalTxBytes.addAndGet(dnsQuery.size.toLong())
        engineScope.launch {
            var stream: TunnelStream? = null
            val outBuf = bufferPool.acquire()
            try {
                stream = transport.openStream(pkt.dstIpString, 53)
                val dos = DataOutputStream(stream.outputStream)
                dos.writeShort(dnsQuery.size)
                dos.write(dnsQuery)
                dos.flush()

                val dis = DataInputStream(stream.inputStream)
                val respLen = dis.readUnsignedShort()
                if (respLen in 1..4096) {
                    val dnsResp = ByteArray(respLen)
                    dis.readFully(dnsResp)
                    totalRxBytes.addAndGet(respLen.toLong())

                    if (outBuf.size >= respLen + 28) {
                        val totalLen = PacketCodec.buildIpv4UdpPacket(
                            out = outBuf,
                            srcIpInt = pkt.dstIpInt,
                            dstIpInt = pkt.srcIpInt,
                            srcPort = pkt.dstPort,
                            dstPort = pkt.srcPort,
                            payload = dnsResp,
                            payloadOffset = 0,
                            payloadLength = respLen,
                            ipIdentification = ipIdCounter.getAndIncrement()
                        )
                        synchronized(tunWriteLock) {
                            tunOutput.write(outBuf, 0, totalLen)
                        }
                    }
                }
            } catch (_: Exception) {
                // DNS timeout or transient tunnel error
            } finally {
                bufferPool.release(outBuf)
                runCatching { stream?.close() }
            }
        }
    }

    private fun writeTcpToTun(
        tunOutput: FileOutputStream,
        session: TcpSession,
        flags: Int,
        payload: ByteArray?,
        payloadLen: Int
    ) {
        val outBuf = bufferPool.acquire()
        try {
            val totalLen = PacketCodec.buildIpv4TcpPacket(
                out = outBuf,
                srcIpInt = session.key.dstIp,
                dstIpInt = session.key.srcIp,
                srcPort = session.key.dstPort,
                dstPort = session.key.srcPort,
                seqNum = session.serverSeq,
                ackNum = session.clientSeq,
                flags = flags,
                windowSize = 65535,
                payload = payload,
                payloadOffset = 0,
                payloadLength = payloadLen,
                ipIdentification = ipIdCounter.getAndIncrement()
            )
            synchronized(tunWriteLock) {
                if (running.get()) {
                    tunOutput.write(outBuf, 0, totalLen)
                }
            }
        } catch (_: Exception) {
            // TUN closed
        } finally {
            bufferPool.release(outBuf)
        }
    }

    private fun sendTcpReset(pkt: PacketCodec.ParsedPacket, tunOutput: FileOutputStream) {
        val outBuf = bufferPool.acquire()
        try {
            val totalLen = PacketCodec.buildIpv4TcpPacket(
                out = outBuf,
                srcIpInt = pkt.dstIpInt,
                dstIpInt = pkt.srcIpInt,
                srcPort = pkt.dstPort,
                dstPort = pkt.srcPort,
                seqNum = pkt.tcpAck,
                ackNum = (pkt.tcpSeq + 1L) and 0xFFFFFFFFL,
                flags = PacketCodec.TCP_RST or PacketCodec.TCP_ACK,
                ipIdentification = ipIdCounter.getAndIncrement()
            )
            synchronized(tunWriteLock) {
                if (running.get()) {
                    tunOutput.write(outBuf, 0, totalLen)
                }
            }
        } catch (_: Exception) {
            // Ignore
        } finally {
            bufferPool.release(outBuf)
        }
    }

    private fun closeTcpSession(key: FlowKey) {
        val removed = tcpSessions.remove(key) ?: return
        removed.closed = true
        runCatching { removed.stream?.close() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        readerJob?.cancel()
        meterJob?.cancel()
        tcpSessions.values.forEach {
            it.closed = true
            runCatching { it.stream?.close() }
        }
        tcpSessions.clear()
        bufferPool.clear()
        engineScope.cancel()
    }
}
