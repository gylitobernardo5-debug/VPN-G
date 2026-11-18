package com.example.benchmark

import android.app.ActivityManager
import android.content.Context
import com.example.data.GVpnRepository
import com.example.data.VpnRuntimeStore
import com.example.engine.ByteBufferPool
import com.example.engine.PacketCodec
import com.example.model.BenchmarkProgress
import com.example.model.BenchmarkSnapshot
import com.example.model.LogLevel
import com.example.model.VpnConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Benchmark & Gargalos V6 Engine:
 * Runs real device & network measurements asynchronously in the background and persists results.
 * Zero hardcoded or fabricated benchmark scores — every metric comes from real sockets, TLS/SSH
 * handshakes, PacketCodec CPU loops, and Android memory APIs.
 */
class BenchmarkV6Engine(
    private val context: Context,
    private val repository: GVpnRepository
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun startBenchmark(config: VpnConfig) {
        if (activeJob?.isActive == true) return
        activeJob = backgroundScope.launch {
            runFullSuite(config)
        }
    }

    fun cancelBenchmark() {
        activeJob?.cancel()
        activeJob = null
        VpnRuntimeStore.updateBenchmarkProgress {
            BenchmarkProgress(
                isRunning = false,
                progressFraction = 0f,
                currentStage = "Teste cancelado",
                completedStages = 0
            )
        }
    }

    suspend fun runFullSuite(config: VpnConfig): BenchmarkSnapshot = withContext(Dispatchers.IO) {
        val ramBeforeMb = getJvmUsedMemoryMb()
        VpnRuntimeStore.log(LogLevel.INFO, "BENCH", "Benchmark V6 iniciado em segundo plano.")

        try {
            // Stage 1: Ping, Connection Latency, Jitter & Packet Loss
            updateStage(0, "1/8 • A medir Ping, Latência e Estabilidade TCP…")
            val pingTarget = resolvePingTarget(config)
            val pingMetrics = measurePingAndStability(pingTarget.first, pingTarget.second, samples = 8)

            // Stage 2: Real Download & Upload Speed Test
            updateStage(1, "2/8 • A executar teste real de Download e Upload…")
            val speedMetrics = measureRealSpeed()

            // Stage 3: SSH Setup Benchmark
            updateStage(2, "3/8 • A testar SSH Setup (TCP + Banner SSH-2)…")
            val sshMetrics = measureSshSetup(config)

            // Stage 4: TLS/SNI Setup Benchmark (strict cert validation)
            updateStage(3, "4/8 • A testar TLS 1.2/1.3 + SNI Setup…")
            val tlsMetrics = measureTlsSetup(config)

            // Stage 5: Local Loopback TCP Engine Benchmark
            updateStage(4, "5/8 • A testar Loopback TCP (Throughput & RTT)…")
            val loopbackMetrics = measureLoopbackTcp(
                bufferBytes = config.speed.bufferSizeBytes,
                tcpNoDelay = config.speed.tcpNoDelay
            )

            // Stage 6: TUN / PacketCodec & BufferPool Benchmark
            updateStage(5, "6/8 • A perfilar TUN / PacketCodec (50.000 pacotes)…")
            val codecMetrics = measurePacketCodec(iterations = 50_000)

            // Stage 7: RAM Memory Profiling
            updateStage(6, "7/8 • A medir memória RAM e pegada do motor…")
            val ramMetrics = measureRamFootprint(ramBeforeMb)

            // Stage 8: V6 Score & Bottleneck Analysis
            updateStage(7, "8/8 • A calcular Score V6 e diagnóstico de gargalos…")
            val analysis = computeScoreAndBottlenecks(
                downloadMbps = speedMetrics.downloadMbps,
                uploadMbps = speedMetrics.uploadMbps,
                pingMs = pingMetrics.avgPingMs,
                jitterMs = pingMetrics.jitterMs,
                packetLossPercent = pingMetrics.lossPercent,
                sshSetupMs = sshMetrics.durationMs,
                tlsSetupMs = tlsMetrics.durationMs,
                loopbackMbps = loopbackMetrics.throughputMbps,
                codecPps = codecMetrics.packetsPerSec,
                ramAvailableRatio = if (ramMetrics.totalMb > 0) {
                    (ramMetrics.totalMb - ramMetrics.usedMb) / ramMetrics.totalMb
                } else 0.5,
                stabilityPercent = pingMetrics.stabilityPercent
            )

            val snapshot = BenchmarkSnapshot(
                timestampMs = System.currentTimeMillis(),
                downloadMbps = speedMetrics.downloadMbps,
                uploadMbps = speedMetrics.uploadMbps,
                speedTestSource = speedMetrics.sourceLabel,
                pingMs = pingMetrics.avgPingMs,
                jitterMs = pingMetrics.jitterMs,
                packetLossPercent = pingMetrics.lossPercent,
                connectionLatencyMs = pingMetrics.firstConnectMs,
                sshSetupMs = sshMetrics.durationMs,
                sshSetupStatus = sshMetrics.status,
                tlsSetupMs = tlsMetrics.durationMs,
                tlsSetupStatus = tlsMetrics.status,
                tlsNegotiatedProtocol = tlsMetrics.protocolVersion,
                loopbackTcpMbps = loopbackMetrics.throughputMbps,
                loopbackRttMs = loopbackMetrics.rttMs,
                packetCodecPps = codecMetrics.packetsPerSec,
                packetCodecMbps = codecMetrics.throughputMbps,
                packetCodecAvgUs = codecMetrics.avgMicrosPerPacket,
                ramUsedMb = ramMetrics.usedMb,
                ramTotalMb = ramMetrics.totalMb,
                ramDeltaMb = ramMetrics.deltaMb,
                stabilityPercent = pingMetrics.stabilityPercent,
                performanceScore = analysis.score,
                primaryBottleneck = analysis.bottleneck,
                recommendations = analysis.recommendations
            )

            val savedId = repository.saveBenchmark(snapshot)
            val persisted = snapshot.copy(id = savedId)
            VpnRuntimeStore.setLatestBenchmark(persisted)
            VpnRuntimeStore.updateBenchmarkProgress {
                BenchmarkProgress(
                    isRunning = false,
                    progressFraction = 1f,
                    currentStage = "Benchmark V6 concluído • Score ${persisted.performanceScore}/100",
                    completedStages = 8,
                    totalStages = 8
                )
            }
            VpnRuntimeStore.log(
                LogLevel.SUCCESS,
                "BENCH",
                "Concluído • Score=${persisted.performanceScore}/100 • Down=${"%.2f".format(persisted.downloadMbps)} Mbps • Up=${"%.2f".format(persisted.uploadMbps)} Mbps • Ping=${"%.1f".format(persisted.pingMs)} ms • Codec=${persisted.packetCodecPps} pkt/s"
            )
            persisted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VpnRuntimeStore.log(LogLevel.ERROR, "BENCH", "Erro no Benchmark V6: ${e.message}")
            VpnRuntimeStore.updateBenchmarkProgress {
                BenchmarkProgress(
                    isRunning = false,
                    progressFraction = 0f,
                    currentStage = "Erro: ${e.message}",
                    completedStages = 0
                )
            }
            throw e
        }
    }

    private fun updateStage(completed: Int, label: String) {
        VpnRuntimeStore.updateBenchmarkProgress {
            BenchmarkProgress(
                isRunning = true,
                progressFraction = (completed.toFloat() / 8f).coerceIn(0f, 0.98f),
                currentStage = label,
                completedStages = completed,
                totalStages = 8
            )
        }
    }

    private fun resolvePingTarget(config: VpnConfig): Pair<String, Int> = when {
        config.tls.isConfigured() -> config.tls.gatewayHost.trim() to config.tls.port
        config.ssh.isConfigured() -> config.ssh.host.trim() to config.ssh.port
        else -> "1.1.1.1" to 443
    }

    private data class PingMetrics(
        val avgPingMs: Double,
        val firstConnectMs: Double,
        val jitterMs: Double,
        val lossPercent: Double,
        val stabilityPercent: Double
    )

    private fun measurePingAndStability(host: String, port: Int, samples: Int): PingMetrics {
        val rtts = mutableListOf<Double>()
        var failures = 0
        for (i in 0 until samples) {
            val socket = Socket()
            try {
                val startNs = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), 3_000)
                val rttMs = (System.nanoTime() - startNs) / 1_000_000.0
                rtts += rttMs
            } catch (_: Exception) {
                failures++
            } finally {
                runCatching { socket.close() }
            }
        }

        if (rtts.isEmpty()) {
            return PingMetrics(
                avgPingMs = 0.0,
                firstConnectMs = 0.0,
                jitterMs = 0.0,
                lossPercent = 100.0,
                stabilityPercent = 0.0
            )
        }

        val avg = rtts.average()
        val first = rtts.first()
        val jitter = if (rtts.size > 1) {
            var diffs = 0.0
            for (i in 1 until rtts.size) {
                diffs += abs(rtts[i] - rtts[i - 1])
            }
            diffs / (rtts.size - 1)
        } else {
            0.0
        }

        val lossPct = (failures.toDouble() / samples.toDouble()) * 100.0
        val jitterPenalty = (jitter * 0.8).coerceAtMost(40.0)
        val stability = (100.0 - lossPct - jitterPenalty).coerceIn(0.0, 100.0)

        return PingMetrics(
            avgPingMs = avg,
            firstConnectMs = first,
            jitterMs = jitter,
            lossPercent = lossPct,
            stabilityPercent = stability
        )
    }

    private data class SpeedMetrics(
        val downloadMbps: Double,
        val uploadMbps: Double,
        val sourceLabel: String
    )

    private fun measureRealSpeed(): SpeedMetrics {
        // Real internet download & upload test via Cloudflare speed endpoints
        val downBytes = 2_000_000 // 2 MB real payload
        val upBytes = 512_000     // 512 KB real upload payload

        var downMbps = 0.0
        var upMbps = 0.0
        var onlineSuccess = false

        try {
            val downReq = Request.Builder()
                .url("https://speed.cloudflare.com/__down?bytes=$downBytes")
                .header("User-Agent", "G-VPN-V6-Benchmark")
                .build()
            val startDownNs = System.nanoTime()
            var totalRead = 0L
            httpClient.newCall(downReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val stream = resp.body?.byteStream()
                    val buf = ByteArray(32_768)
                    if (stream != null) {
                        while (true) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            totalRead += n
                        }
                    }
                }
            }
            val downSec = (System.nanoTime() - startDownNs) / 1_000_000_000.0
            if (totalRead > 0 && downSec > 0.0) {
                downMbps = (totalRead * 8.0) / (downSec * 1_000_000.0)
                onlineSuccess = true
            }
        } catch (_: Exception) {
            // Network may be offline or blocked
        }

        try {
            val payload = ByteArray(upBytes) { (it and 0xFF).toByte() }
            val body = payload.toRequestBody("application/octet-stream".toMediaType())
            val upReq = Request.Builder()
                .url("https://speed.cloudflare.com/__up")
                .post(body)
                .header("User-Agent", "G-VPN-V6-Benchmark")
                .build()
            val startUpNs = System.nanoTime()
            httpClient.newCall(upReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val upSec = (System.nanoTime() - startUpNs) / 1_000_000_000.0
                    if (upSec > 0.0) {
                        upMbps = (upBytes * 8.0) / (upSec * 1_000_000.0)
                        onlineSuccess = true
                    }
                }
            }
        } catch (_: Exception) {
            // Upload endpoint unreachable
        }

        return if (onlineSuccess) {
            SpeedMetrics(
                downloadMbps = downMbps,
                uploadMbps = upMbps,
                sourceLabel = "Internet Real (HTTPS)"
            )
        } else {
            SpeedMetrics(
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                sourceLabel = "Sem ligação externa"
            )
        }
    }

    private data class SshBenchMetrics(
        val durationMs: Double?,
        val status: String
    )

    private fun measureSshSetup(config: VpnConfig): SshBenchMetrics {
        if (config.ssh.host.isBlank()) {
            return SshBenchMetrics(null, "Não configurado")
        }
        val socket = Socket()
        return try {
            val startNs = System.nanoTime()
            socket.tcpNoDelay = config.speed.tcpNoDelay
            socket.soTimeout = 5_000
            socket.connect(InetSocketAddress(config.ssh.host.trim(), config.ssh.port), 5_000)

            val banner = if (config.ssh.useTlsWrapper) {
                val sni = config.ssh.tlsSniHost.ifBlank { config.ssh.host.trim() }
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = sslFactory.createSocket(socket, sni, config.ssh.port, true) as SSLSocket
                ssl.sslParameters = SSLParameters().apply {
                    serverNames = listOf(SNIHostName(sni))
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.startHandshake()
                readSshBannerLine(ssl.inputStream)
            } else {
                readSshBannerLine(socket.inputStream)
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            if (banner.startsWith("SSH-")) {
                SshBenchMetrics(elapsedMs, "Banner OK ($banner)")
            } else {
                SshBenchMetrics(elapsedMs, "TCP OK (sem banner SSH)")
            }
        } catch (e: Exception) {
            SshBenchMetrics(null, "Falha: ${e.message?.take(48) ?: "Inacessível"}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readSshBannerLine(input: java.io.InputStream): String {
        val bis = BufferedInputStream(input, 256)
        val sb = StringBuilder()
        repeat(128) {
            val b = bis.read()
            if (b == -1 || b == '\n'.code) return sb.toString().trim()
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString().trim()
    }

    private data class TlsBenchMetrics(
        val durationMs: Double?,
        val status: String,
        val protocolVersion: String
    )

    private fun measureTlsSetup(config: VpnConfig): TlsBenchMetrics {
        val isCustom = config.tls.isConfigured()
        val host = if (isCustom) config.tls.gatewayHost.trim() else "cloudflare.com"
        val port = if (isCustom) config.tls.port else 443
        val sni = if (isCustom) config.tls.effectiveSni() else "cloudflare.com"

        val raw = Socket()
        return try {
            val startNs = System.nanoTime()
            raw.tcpNoDelay = config.speed.tcpNoDelay
            raw.soTimeout = 6_000
            raw.connect(InetSocketAddress(host, port), 6_000)

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory.createSocket(raw, sni, port, true) as SSLSocket
            val supported = ssl.supportedProtocols.toSet()
            val enabled = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
            if (enabled.isNotEmpty()) ssl.enabledProtocols = enabled.toTypedArray()

            ssl.sslParameters = SSLParameters().apply {
                serverNames = listOf(SNIHostName(sni))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            val proto = ssl.session.protocol
            ssl.close()
            val label = if (isCustom) "Gateway verificado ($proto)" else "Base TLS dispositivo ($proto)"
            TlsBenchMetrics(elapsedMs, label, proto)
        } catch (e: Exception) {
            TlsBenchMetrics(
                null,
                "Falha TLS: ${e.message?.take(48) ?: "Certificado/rede"}",
                "—"
            )
        } finally {
            runCatching { raw.close() }
        }
    }

    data class LoopbackMetrics(
        val throughputMbps: Double,
        val rttMs: Double
    )

    suspend fun measureLoopbackTcp(
        bufferBytes: Int = 65_536,
        tcpNoDelay: Boolean = true,
        totalBytes: Long = 12L * 1024L * 1024L
    ): LoopbackMetrics = coroutineScope {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        try {
            val serverJob = async(Dispatchers.IO) {
                server.accept().use { accepted ->
                    accepted.tcpNoDelay = tcpNoDelay
                    accepted.receiveBufferSize = bufferBytes
                    val input = accepted.getInputStream()
                    val output = accepted.getOutputStream()
                    // 1-byte RTT ping echo first
                    val pingByte = input.read()
                    if (pingByte != -1) {
                        output.write(pingByte)
                        output.flush()
                    }
                    val buf = ByteArray(bufferBytes)
                    var remaining = totalBytes
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        remaining -= n
                    }
                }
            }

            var rttMs = 0.0
            var throughputMbps = 0.0

            Socket().use { client ->
                client.tcpNoDelay = tcpNoDelay
                client.sendBufferSize = bufferBytes
                client.connect(InetSocketAddress("127.0.0.1", port), 3_000)

                val out = client.getOutputStream()
                val input = client.getInputStream()

                val rttStartNs = System.nanoTime()
                out.write(0x5A)
                out.flush()
                input.read()
                rttMs = (System.nanoTime() - rttStartNs) / 1_000_000.0

                val chunk = ByteArray(bufferBytes) { 0x42 }
                val startNs = System.nanoTime()
                var sent = 0L
                while (sent < totalBytes) {
                    val toWrite = minOf(chunk.size.toLong(), totalBytes - sent).toInt()
                    out.write(chunk, 0, toWrite)
                    sent += toWrite
                }
                out.flush()
                serverJob.await()
                val sec = (System.nanoTime() - startNs) / 1_000_000_000.0
                if (sec > 0.0) {
                    throughputMbps = (totalBytes * 8.0) / (sec * 1_000_000.0)
                }
            }

            LoopbackMetrics(throughputMbps = throughputMbps, rttMs = rttMs)
        } finally {
            runCatching { server.close() }
        }
    }

    data class PacketCodecMetrics(
        val packetsPerSec: Long,
        val throughputMbps: Double,
        val avgMicrosPerPacket: Double
    )

    fun measurePacketCodec(iterations: Int = 50_000): PacketCodecMetrics {
        val pool = ByteBufferPool(bufferSize = 1600, maxPoolSize = 16)
        val payload = ByteArray(1200) { (it and 0xFF).toByte() }
        val srcIp = PacketCodec.ipStringToInt("10.88.0.2")
        val dstIp = PacketCodec.ipStringToInt("1.1.1.1")

        var totalBytesProcessed = 0L
        var validCount = 0
        val startNs = System.nanoTime()

        for (i in 0 until iterations) {
            val buf = pool.acquire()
            val len = PacketCodec.buildIpv4TcpPacket(
                out = buf,
                srcIpInt = srcIp,
                dstIpInt = dstIp,
                srcPort = 45000 + (i and 0x3FF),
                dstPort = 443,
                seqNum = 1000L + i * 1200L,
                ackNum = 1L,
                flags = PacketCodec.TCP_PSH or PacketCodec.TCP_ACK,
                payload = payload,
                payloadOffset = 0,
                payloadLength = payload.size,
                ipIdentification = i
            )
            val parsed = PacketCodec.parseIpv4(buf, len)
            if (parsed != null && PacketCodec.verifyIpv4HeaderChecksum(buf, parsed.ipHeaderLength)) {
                validCount++
                totalBytesProcessed += len
            }
            pool.release(buf)
        }

        val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1L)
        val elapsedSec = elapsedNs / 1_000_000_000.0
        val pps = (validCount / elapsedSec).toLong()
        val mbps = (totalBytesProcessed * 8.0) / (elapsedSec * 1_000_000.0)
        val avgUs = (elapsedNs / 1_000.0) / validCount.coerceAtLeast(1)

        return PacketCodecMetrics(
            packetsPerSec = pps,
            throughputMbps = mbps,
            avgMicrosPerPacket = avgUs
        )
    }

    private data class RamMetrics(
        val usedMb: Double,
        val totalMb: Double,
        val deltaMb: Double
    )

    private fun measureRamFootprint(ramBeforeMb: Double): RamMetrics {
        val jvmAfterMb = getJvmUsedMemoryMb()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        return if (am != null) {
            am.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / (1024.0 * 1024.0)
            val usedSysMb = (memInfo.totalMem - memInfo.availMem) / (1024.0 * 1024.0)
            RamMetrics(
                usedMb = usedSysMb,
                totalMb = totalMb,
                deltaMb = (jvmAfterMb - ramBeforeMb).coerceAtLeast(0.0)
            )
        } else {
            val rt = Runtime.getRuntime()
            RamMetrics(
                usedMb = jvmAfterMb,
                totalMb = rt.maxMemory() / (1024.0 * 1024.0),
                deltaMb = (jvmAfterMb - ramBeforeMb).coerceAtLeast(0.0)
            )
        }
    }

    private fun getJvmUsedMemoryMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
    }

    data class ScoreAnalysis(
        val score: Int,
        val bottleneck: String,
        val recommendations: List<String>
    )

    fun computeScoreAndBottlenecks(
        downloadMbps: Double,
        uploadMbps: Double,
        pingMs: Double,
        jitterMs: Double,
        packetLossPercent: Double,
        sshSetupMs: Double?,
        tlsSetupMs: Double?,
        loopbackMbps: Double,
        codecPps: Long,
        ramAvailableRatio: Double,
        stabilityPercent: Double
    ): ScoreAnalysis {
        // Weighted real metrics normalized to 0..100
        val speedScore = when {
            downloadMbps <= 0.0 -> 0.0
            else -> (sqrt(downloadMbps.coerceAtMost(100.0)) * 7.0 + sqrt(uploadMbps.coerceAtMost(50.0)) * 4.2)
                .coerceIn(0.0, 100.0)
        }
        val pingScore = when {
            pingMs <= 0.0 -> 0.0
            pingMs <= 30.0 -> 100.0
            pingMs >= 300.0 -> 15.0
            else -> (100.0 - ((pingMs - 30.0) / 270.0) * 85.0).coerceIn(15.0, 100.0)
        }
        val tlsScore = when {
            tlsSetupMs == null -> 40.0
            tlsSetupMs <= 120.0 -> 100.0
            tlsSetupMs >= 1200.0 -> 20.0
            else -> (100.0 - ((tlsSetupMs - 120.0) / 1080.0) * 80.0).coerceIn(20.0, 100.0)
        }
        val loopbackScore = (loopbackMbps / 800.0 * 100.0).coerceIn(10.0, 100.0)
        val codecScore = (codecPps / 120_000.0 * 100.0).coerceIn(10.0, 100.0)
        val ramScore = (ramAvailableRatio * 100.0).coerceIn(15.0, 100.0)

        val weighted = (
            speedScore * 0.25 +
                pingScore * 0.20 +
                stabilityPercent * 0.20 +
                tlsScore * 0.12 +
                loopbackScore * 0.10 +
                codecScore * 0.08 +
                ramScore * 0.05
            ).roundToInt().coerceIn(0, 100)

        val candidates = listOf(
            "Velocidade de Rede Externa" to speedScore,
            "Latência / Ping da Operadora" to pingScore,
            "Estabilidade & Perda de Pacotes" to stabilityPercent,
            "Negociação TLS/SNI" to tlsScore,
            "Loopback TCP do Dispositivo" to loopbackScore,
            "Processamento TUN/PacketCodec" to codecScore,
            "Memória RAM Disponível" to ramScore
        )
        val weakest = candidates.minByOrNull { it.second }
        val bottleneck = weakest?.first ?: "Equilibrado"

        val recs = buildList {
            if (downloadMbps <= 0.0) {
                add("Sem tráfego HTTPS externo durante o teste: verifique dados móveis 4G/5G ou Wi-Fi.")
            }
            if (packetLossPercent > 5.0 || jitterMs > 30.0) {
                add("Oscilação detetada (${"%.1f".format(packetLossPercent)}% perda): ative Reconexão Automática e KeepAlive ≤ 20s.")
            }
            if (pingMs > 120.0) {
                add("Latência elevada (${"%.0f".format(pingMs)} ms): escolha um servidor SSH/TLS geograficamente mais próximo e mantenha TCP_NODELAY ativo.")
            }
            if (sshSetupMs == null) {
                add("Motor SSH não configurado: insira um servidor SSH real em Config para ativar redundância Dual Engine.")
            }
            if (tlsSetupMs != null && tlsSetupMs > 400.0) {
                add("Handshake TLS demorado (${"%.0f".format(tlsSetupMs)} ms): prefira gateways com TLS 1.3 para reduzir round-trips.")
            }
            if (codecPps < 50_000L) {
                add("Aumente o buffer do Extreme Speed Engine para 64–128 KB para reduzir chamadas de sistema por pacote.")
            }
            if (isEmpty()) {
                add("Desempenho equilibrado: mantenha o Smart Engine em AUTO com buffers de 64 KB e TCP_NODELAY ativo.")
            }
        }

        return ScoreAnalysis(
            score = weighted,
            bottleneck = bottleneck,
            recommendations = recs
        )
    }
}
