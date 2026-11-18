package com.example

import com.example.benchmark.BenchmarkV6Engine
import com.example.data.GVpnDatabase
import com.example.data.GVpnRepository
import com.example.data.VpnConfigStore
import com.example.engine.ByteBufferPool
import com.example.engine.PacketCodec
import com.example.engine.SmartConnectionEngine
import com.example.engine.SocketProtector
import com.example.engine.SshTunnelEngine
import com.example.engine.TlsSniTunnelEngine
import com.example.model.SmartEngineMode
import com.example.model.SpeedEngineConfig
import com.example.model.SshConfig
import com.example.model.TlsSniConfig
import com.example.model.TunnelProtocol
import com.example.model.VpnConfig
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GVpnEngineTests {

    @Test
    fun `packet codec builds parses and validates IPv4 TCP checksum`() {
        val out = ByteArray(1500)
        val srcIp = PacketCodec.ipStringToInt("10.88.0.2")
        val dstIp = PacketCodec.ipStringToInt("1.1.1.1")
        val payload = "G-VPN-V6-DATA".toByteArray(Charsets.UTF_8)

        val totalLen = PacketCodec.buildIpv4TcpPacket(
            out = out,
            srcIpInt = srcIp,
            dstIpInt = dstIp,
            srcPort = 51234,
            dstPort = 443,
            seqNum = 100_000L,
            ackNum = 200_000L,
            flags = PacketCodec.TCP_PSH or PacketCodec.TCP_ACK,
            payload = payload,
            payloadOffset = 0,
            payloadLength = payload.size,
            ipIdentification = 42
        )

        assertEquals(20 + 20 + payload.size, totalLen)
        assertTrue(PacketCodec.verifyIpv4HeaderChecksum(out, 20))

        val parsed = PacketCodec.parseIpv4(out, totalLen)
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(PacketCodec.PROTO_TCP, parsed.protocol)
        assertEquals("10.88.0.2", parsed.srcIpString)
        assertEquals("1.1.1.1", parsed.dstIpString)
        assertEquals(51234, parsed.srcPort)
        assertEquals(443, parsed.dstPort)
        assertEquals(100_000L, parsed.tcpSeq)
        assertEquals(200_000L, parsed.tcpAck)
        assertEquals(payload.size, parsed.payloadLength)
        assertArrayEquals(
            payload,
            out.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength)
        )

        // Corrupt a header byte and verify checksum catches it
        out[8] = (out[8].toInt() xor 0xFF).toByte()
        assertFalse(PacketCodec.verifyIpv4HeaderChecksum(out, 20))
    }

    @Test
    fun `packet codec builds and parses IPv4 UDP DNS packet`() {
        val out = ByteArray(512)
        val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01)
        val len = PacketCodec.buildIpv4UdpPacket(
            out = out,
            srcIpInt = PacketCodec.ipStringToInt("1.1.1.1"),
            dstIpInt = PacketCodec.ipStringToInt("10.88.0.2"),
            srcPort = 53,
            dstPort = 49152,
            payload = dnsPayload
        )
        assertTrue(PacketCodec.verifyIpv4HeaderChecksum(out, 20))
        val parsed = PacketCodec.parseIpv4(out, len)!!
        assertEquals(PacketCodec.PROTO_UDP, parsed.protocol)
        assertEquals(53, parsed.srcPort)
        assertEquals(49152, parsed.dstPort)
        assertArrayEquals(
            dnsPayload,
            out.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength)
        )
    }

    @Test
    fun `byte buffer pool recycles arrays and respects upper bound`() {
        val pool = ByteBufferPool(bufferSize = 2048, maxPoolSize = 4)
        val a = pool.acquire()
        val b = pool.acquire()
        assertEquals(2048, a.size)
        pool.release(a)
        pool.release(b)
        assertEquals(2, pool.currentPooledCount())
        val recycled = pool.acquire()
        assertTrue(recycled === a || recycled === b)
        assertEquals(1, pool.currentPooledCount())
    }

    @Test
    fun `smart engine refuses empty config without faking connection`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val smart = SmartConnectionEngine(VpnConfigStore(context), SocketProtector { true })
        var threw = false
        try {
            smart.establishBestTunnel(VpnConfig()) {}
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("SmartEngine must fail honestly when unconfigured", threw)
    }

    @Test
    fun `ssh and tls engines reject unconfigured endpoints honestly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ssh = SshTunnelEngine(SshConfig(), SpeedEngineConfig(), VpnConfigStore(context), SocketProtector { true })
        val tls = TlsSniTunnelEngine(TlsSniConfig(), SpeedEngineConfig(), SocketProtector { true })

        val sshFailed = runCatching { ssh.connectAndVerify {} }.isFailure
        val tlsFailed = runCatching { tls.connectAndVerify {} }.isFailure

        assertTrue(sshFailed)
        assertTrue(tlsFailed)
        assertFalse(ssh.isAlive())
        assertFalse(tls.isAlive())
    }

    @Test
    fun `smart engine candidate ordering respects SSH and TLS priority modes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val smart = SmartConnectionEngine(VpnConfigStore(context), SocketProtector { true })

        val sshFirstConfig = VpnConfig(
            smartMode = SmartEngineMode.SSH_PRIORITY,
            ssh = SshConfig(host = "198.51.100.1", port = 22, username = "u", password = "p"),
            tls = TlsSniConfig(gatewayHost = "gw.example.com", port = 443)
        )
        val sshOrder = smart.buildCandidateOrder(sshFirstConfig, sshReady = true, tlsReady = true) {}
        assertEquals(listOf(TunnelProtocol.SSH, TunnelProtocol.TLS_SNI), sshOrder)

        val tlsFirstConfig = sshFirstConfig.copy(smartMode = SmartEngineMode.TLS_PRIORITY)
        val tlsOrder = smart.buildCandidateOrder(tlsFirstConfig, sshReady = true, tlsReady = true) {}
        assertEquals(listOf(TunnelProtocol.TLS_SNI, TunnelProtocol.SSH), tlsOrder)
    }

    @Test
    fun `benchmark v6 executes real loopback TCP and PacketCodec benchmarks`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, GVpnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val bench = BenchmarkV6Engine(context, GVpnRepository(db.benchmarkDao(), db.serverProfileDao()))

            val codecMetrics = bench.measurePacketCodec(iterations = 10_000)
            assertTrue("Codec PPS must be > 0", codecMetrics.packetsPerSec > 0L)
            assertTrue("Codec Mbps must be > 0", codecMetrics.throughputMbps > 0.0)

            val loopback = bench.measureLoopbackTcp(
                bufferBytes = 32_768,
                tcpNoDelay = true,
                totalBytes = 2L * 1024L * 1024L
            )
            assertTrue("Loopback TCP Mbps must be > 0", loopback.throughputMbps > 0.0)
            assertTrue("Loopback RTT must be >= 0", loopback.rttMs >= 0.0)

            val score = bench.computeScoreAndBottlenecks(
                downloadMbps = 50.0,
                uploadMbps = 25.0,
                pingMs = 35.0,
                jitterMs = 4.0,
                packetLossPercent = 0.0,
                sshSetupMs = 80.0,
                tlsSetupMs = 95.0,
                loopbackMbps = loopback.throughputMbps,
                codecPps = codecMetrics.packetsPerSec,
                ramAvailableRatio = 0.65,
                stabilityPercent = 96.0
            )
            assertTrue("V6 score must be in 1..100", score.score in 1..100)
            assertTrue(score.recommendations.isNotEmpty())
        } finally {
            db.close()
        }
    }
}
