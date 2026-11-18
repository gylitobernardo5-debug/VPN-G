package com.example.data

import com.example.model.BenchmarkProgress
import com.example.model.BenchmarkSnapshot
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.MobileNetworkState
import com.example.model.VpnTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-scoped runtime state shared between GVpnService (background VpnService),
 * MobileNetworkEngine, BenchmarkV6Engine, and the Compose UI.
 * Allows the tunnel and benchmarks to continue running seamlessly when the UI is closed
 * and instantaneously re-synchronize upon reopening.
 */
object VpnRuntimeStore {
    private const val MAX_LOGS = 250
    private val logIdGen = AtomicLong(1L)

    private val _telemetry = MutableStateFlow(VpnTelemetry())
    val telemetry: StateFlow<VpnTelemetry> = _telemetry.asStateFlow()

    private val _networkState = MutableStateFlow(MobileNetworkState())
    val networkState: StateFlow<MobileNetworkState> = _networkState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _benchmarkProgress = MutableStateFlow(BenchmarkProgress())
    val benchmarkProgress: StateFlow<BenchmarkProgress> = _benchmarkProgress.asStateFlow()

    private val _latestBenchmark = MutableStateFlow<BenchmarkSnapshot?>(null)
    val latestBenchmark: StateFlow<BenchmarkSnapshot?> = _latestBenchmark.asStateFlow()

    fun updateTelemetry(transform: (VpnTelemetry) -> VpnTelemetry) {
        _telemetry.update(transform)
    }

    fun updateNetworkState(transform: (MobileNetworkState) -> MobileNetworkState) {
        _networkState.update(transform)
    }

    fun updateBenchmarkProgress(transform: (BenchmarkProgress) -> BenchmarkProgress) {
        _benchmarkProgress.update(transform)
    }

    fun setLatestBenchmark(snapshot: BenchmarkSnapshot?) {
        _latestBenchmark.value = snapshot
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            id = logIdGen.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        _logs.update { current ->
            val updated = current + entry
            if (updated.size > MAX_LOGS) updated.takeLast(MAX_LOGS) else updated
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
