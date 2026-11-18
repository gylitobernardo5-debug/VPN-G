package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.benchmark.BenchmarkV6Engine
import com.example.data.GVpnDatabase
import com.example.data.GVpnRepository
import com.example.data.ServerProfileEntity
import com.example.data.VpnConfigStore
import com.example.data.VpnRuntimeStore
import com.example.model.BenchmarkProgress
import com.example.model.BenchmarkSnapshot
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.MobileNetworkState
import com.example.model.SmartEngineMode
import com.example.model.TlsGatewayMode
import com.example.model.VpnConfig
import com.example.model.VpnConnectionState
import com.example.model.VpnTelemetry
import com.example.network.MobileNetworkEngine
import com.example.service.GVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GVpnViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val configStore = VpnConfigStore(appContext)
    private val database = GVpnDatabase.getInstance(appContext)
    private val repository = GVpnRepository(database.benchmarkDao(), database.serverProfileDao())
    private val mobileNetworkEngine = MobileNetworkEngine(appContext)
    private val benchmarkEngine = BenchmarkV6Engine(appContext, repository)

    val config: StateFlow<VpnConfig> = configStore.configFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = VpnConfig()
    )

    val telemetry: StateFlow<VpnTelemetry> = VpnRuntimeStore.telemetry
    val networkState: StateFlow<MobileNetworkState> = VpnRuntimeStore.networkState
    val logs: StateFlow<List<LogEntry>> = VpnRuntimeStore.logs
    val benchmarkProgress: StateFlow<BenchmarkProgress> = VpnRuntimeStore.benchmarkProgress
    val latestBenchmark: StateFlow<BenchmarkSnapshot?> = VpnRuntimeStore.latestBenchmark

    val benchmarkHistory: StateFlow<List<BenchmarkSnapshot>> = repository.benchmarkHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val savedProfiles: StateFlow<List<ServerProfileEntity>> = repository.serverProfiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    init {
        mobileNetworkEngine.startMonitoring()
        viewModelScope.launch {
            mobileNetworkEngine.fetchPublicIp()
        }
        viewModelScope.launch {
            repository.benchmarkHistory.collect { history ->
                if (VpnRuntimeStore.latestBenchmark.value == null && history.isNotEmpty()) {
                    VpnRuntimeStore.setLatestBenchmark(history.first())
                }
            }
        }
        if (VpnRuntimeStore.logs.value.isEmpty()) {
            VpnRuntimeStore.log(
                LogLevel.INFO,
                "CORE",
                "G-VPN Smart Engine V6 inicializado • Dual Engine (SSH + TLS/SNI) pronto."
            )
        }
    }

    fun prepareVpnIntent(): Intent? = VpnService.prepare(appContext)

    fun startVpnConnection() {
        val currentConfig = config.value
        if (!currentConfig.ssh.isConfigured() && !currentConfig.tls.isConfigured()) {
            val msg = "Configure um servidor SSH ou Gateway TLS/SNI real antes de ligar."
            VpnRuntimeStore.log(LogLevel.WARN, "CORE", msg)
            VpnRuntimeStore.updateTelemetry {
                it.copy(
                    state = VpnConnectionState.ERROR,
                    statusStep = "Configuração necessária",
                    lastError = msg
                )
            }
            _uiMessage.value = msg
            return
        }
        GVpnService.startConnect(appContext)
    }

    fun stopVpnConnection() {
        GVpnService.startDisconnect(appContext)
    }

    fun setSmartMode(mode: SmartEngineMode) {
        viewModelScope.launch {
            configStore.updateSmartMode(mode)
            VpnRuntimeStore.log(LogLevel.INFO, "SMART", "Modo selecionado: ${mode.label}")
        }
    }

    fun saveConfig(updated: VpnConfig) {
        viewModelScope.launch {
            configStore.saveConfig(updated)
            VpnRuntimeStore.log(
                LogLevel.SUCCESS,
                "CONFIG",
                "Configuração guardada • Modo=${updated.smartMode.shortLabel} • Buffer=${updated.speed.bufferSizeKb}KB • TCP_NODELAY=${updated.speed.tcpNoDelay}"
            )
            _uiMessage.value = "Configuração guardada com sucesso."
        }
    }

    fun saveCurrentAsProfile(profileName: String, current: VpnConfig) {
        val trimmed = profileName.trim()
        if (trimmed.isEmpty()) {
            _uiMessage.value = "Indique um nome para o perfil."
            return
        }
        viewModelScope.launch {
            configStore.saveConfig(current)
            repository.saveProfile(
                ServerProfileEntity(
                    name = trimmed,
                    sshHost = current.ssh.host.trim(),
                    sshPort = current.ssh.port,
                    sshUsername = current.ssh.username.trim(),
                    sshPassword = current.ssh.password,
                    sshUseTls = current.ssh.useTlsWrapper,
                    sshTlsSni = current.ssh.tlsSniHost.trim(),
                    tlsHost = current.tls.gatewayHost.trim(),
                    tlsPort = current.tls.port,
                    tlsSni = current.tls.sniHostname.trim(),
                    tlsGatewayMode = current.tls.gatewayMode.name,
                    smartMode = current.smartMode.name
                )
            )
            VpnRuntimeStore.log(LogLevel.INFO, "PROFILE", "Perfil guardado: $trimmed")
            _uiMessage.value = "Perfil \"$trimmed\" guardado."
        }
    }

    fun applyProfile(profile: ServerProfileEntity) {
        viewModelScope.launch {
            val currentSpeed = config.value.speed
            val updated = VpnConfig(
                smartMode = runCatching { SmartEngineMode.valueOf(profile.smartMode) }
                    .getOrDefault(SmartEngineMode.AUTO),
                ssh = config.value.ssh.copy(
                    host = profile.sshHost,
                    port = profile.sshPort,
                    username = profile.sshUsername,
                    password = profile.sshPassword,
                    useTlsWrapper = profile.sshUseTls,
                    tlsSniHost = profile.sshTlsSni
                ),
                tls = config.value.tls.copy(
                    gatewayHost = profile.tlsHost,
                    port = profile.tlsPort,
                    sniHostname = profile.tlsSni,
                    gatewayMode = runCatching { TlsGatewayMode.valueOf(profile.tlsGatewayMode) }
                        .getOrDefault(TlsGatewayMode.HTTPS_CONNECT)
                ),
                speed = currentSpeed
            )
            configStore.saveConfig(updated)
            VpnRuntimeStore.log(LogLevel.INFO, "PROFILE", "Perfil aplicado: ${profile.name}")
            _uiMessage.value = "Perfil \"${profile.name}\" carregado."
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            repository.deleteProfile(id)
            _uiMessage.value = "Perfil removido."
        }
    }

    fun startBenchmarkV6() {
        benchmarkEngine.startBenchmark(config.value)
    }

    fun stopBenchmarkV6() {
        benchmarkEngine.cancelBenchmark()
    }

    fun selectBenchmarkSnapshot(snapshot: BenchmarkSnapshot) {
        VpnRuntimeStore.setLatestBenchmark(snapshot)
    }

    fun clearBenchmarkHistory() {
        viewModelScope.launch {
            repository.clearBenchmarkHistory()
            VpnRuntimeStore.setLatestBenchmark(null)
            _uiMessage.value = "Histórico de benchmarks limpo."
        }
    }

    fun refreshPublicIp() {
        viewModelScope.launch {
            val ip = mobileNetworkEngine.fetchPublicIp()
            VpnRuntimeStore.log(LogLevel.INFO, "NET", "IP público consultado: $ip")
        }
    }

    fun clearLogs() {
        VpnRuntimeStore.clearLogs()
    }

    fun clearUiMessage() {
        _uiMessage.value = null
    }

    override fun onCleared() {
        mobileNetworkEngine.stopMonitoring()
        super.onCleared()
    }
}
