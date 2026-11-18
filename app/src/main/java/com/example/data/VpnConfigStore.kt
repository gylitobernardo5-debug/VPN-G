package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.SmartEngineMode
import com.example.model.SpeedEngineConfig
import com.example.model.SshConfig
import com.example.model.TlsGatewayMode
import com.example.model.TlsSniConfig
import com.example.model.VpnConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gvpnDataStore: DataStore<Preferences> by preferencesDataStore(name = "gvpn_v6_config")

class VpnConfigStore(private val context: Context) {

    private object Keys {
        val SMART_MODE = stringPreferencesKey("smart_mode")

        // SSH
        val SSH_HOST = stringPreferencesKey("ssh_host")
        val SSH_PORT = intPreferencesKey("ssh_port")
        val SSH_USERNAME = stringPreferencesKey("ssh_username")
        val SSH_PASSWORD = stringPreferencesKey("ssh_password")
        val SSH_USE_TLS = booleanPreferencesKey("ssh_use_tls")
        val SSH_TLS_SNI = stringPreferencesKey("ssh_tls_sni")
        val SSH_KEEPALIVE = intPreferencesKey("ssh_keepalive")
        val SSH_VERIFY_TOFU = booleanPreferencesKey("ssh_verify_tofu")

        // TLS/SNI
        val TLS_HOST = stringPreferencesKey("tls_host")
        val TLS_PORT = intPreferencesKey("tls_port")
        val TLS_SNI = stringPreferencesKey("tls_sni")
        val TLS_GATEWAY_MODE = stringPreferencesKey("tls_gateway_mode")
        val TLS_CUSTOM_PATH = stringPreferencesKey("tls_custom_path")
        val TLS_ALPN = stringPreferencesKey("tls_alpn")

        // Extreme Speed Engine
        val SPEED_BUFFER_KB = intPreferencesKey("speed_buffer_kb")
        val SPEED_TCP_NODELAY = booleanPreferencesKey("speed_tcp_nodelay")
        val SPEED_KEEPALIVE_ENABLED = booleanPreferencesKey("speed_keepalive_enabled")
        val SPEED_KEEPALIVE_SEC = intPreferencesKey("speed_keepalive_sec")
        val SPEED_MTU = intPreferencesKey("speed_mtu")
        val SPEED_MAX_STREAMS = intPreferencesKey("speed_max_streams")
        val SPEED_AUTO_RECONNECT = booleanPreferencesKey("speed_auto_reconnect")
    }

    val configFlow: Flow<VpnConfig> = context.gvpnDataStore.data.map { prefs ->
        VpnConfig(
            smartMode = prefs[Keys.SMART_MODE]
                ?.let { runCatching { SmartEngineMode.valueOf(it) }.getOrNull() }
                ?: SmartEngineMode.AUTO,
            ssh = SshConfig(
                host = prefs[Keys.SSH_HOST] ?: "",
                port = prefs[Keys.SSH_PORT] ?: 22,
                username = prefs[Keys.SSH_USERNAME] ?: "",
                password = prefs[Keys.SSH_PASSWORD] ?: "",
                useTlsWrapper = prefs[Keys.SSH_USE_TLS] ?: false,
                tlsSniHost = prefs[Keys.SSH_TLS_SNI] ?: "",
                keepAliveSeconds = prefs[Keys.SSH_KEEPALIVE] ?: 25,
                verifyHostKeyTofu = prefs[Keys.SSH_VERIFY_TOFU] ?: true
            ),
            tls = TlsSniConfig(
                gatewayHost = prefs[Keys.TLS_HOST] ?: "",
                port = prefs[Keys.TLS_PORT] ?: 443,
                sniHostname = prefs[Keys.TLS_SNI] ?: "",
                gatewayMode = prefs[Keys.TLS_GATEWAY_MODE]
                    ?.let { runCatching { TlsGatewayMode.valueOf(it) }.getOrNull() }
                    ?: TlsGatewayMode.HTTPS_CONNECT,
                customPath = prefs[Keys.TLS_CUSTOM_PATH] ?: "/",
                alpnProtocol = prefs[Keys.TLS_ALPN] ?: "http/1.1"
            ),
            speed = SpeedEngineConfig(
                bufferSizeKb = prefs[Keys.SPEED_BUFFER_KB] ?: 64,
                tcpNoDelay = prefs[Keys.SPEED_TCP_NODELAY] ?: true,
                keepAliveEnabled = prefs[Keys.SPEED_KEEPALIVE_ENABLED] ?: true,
                keepAliveIntervalSec = prefs[Keys.SPEED_KEEPALIVE_SEC] ?: 25,
                mtu = prefs[Keys.SPEED_MTU] ?: 1500,
                maxConcurrentStreams = prefs[Keys.SPEED_MAX_STREAMS] ?: 64,
                autoReconnect = prefs[Keys.SPEED_AUTO_RECONNECT] ?: true
            )
        )
    }

    suspend fun getConfig(): VpnConfig = configFlow.first()

    suspend fun saveConfig(config: VpnConfig) {
        context.gvpnDataStore.edit { prefs ->
            prefs[Keys.SMART_MODE] = config.smartMode.name

            prefs[Keys.SSH_HOST] = config.ssh.host.trim()
            prefs[Keys.SSH_PORT] = config.ssh.port.coerceIn(1, 65535)
            prefs[Keys.SSH_USERNAME] = config.ssh.username.trim()
            prefs[Keys.SSH_PASSWORD] = config.ssh.password
            prefs[Keys.SSH_USE_TLS] = config.ssh.useTlsWrapper
            prefs[Keys.SSH_TLS_SNI] = config.ssh.tlsSniHost.trim()
            prefs[Keys.SSH_KEEPALIVE] = config.ssh.keepAliveSeconds.coerceIn(5, 300)
            prefs[Keys.SSH_VERIFY_TOFU] = config.ssh.verifyHostKeyTofu

            prefs[Keys.TLS_HOST] = config.tls.gatewayHost.trim()
            prefs[Keys.TLS_PORT] = config.tls.port.coerceIn(1, 65535)
            prefs[Keys.TLS_SNI] = config.tls.sniHostname.trim()
            prefs[Keys.TLS_GATEWAY_MODE] = config.tls.gatewayMode.name
            prefs[Keys.TLS_CUSTOM_PATH] = config.tls.customPath.ifBlank { "/" }
            prefs[Keys.TLS_ALPN] = config.tls.alpnProtocol.ifBlank { "http/1.1" }

            prefs[Keys.SPEED_BUFFER_KB] = config.speed.bufferSizeKb.coerceIn(8, 256)
            prefs[Keys.SPEED_TCP_NODELAY] = config.speed.tcpNoDelay
            prefs[Keys.SPEED_KEEPALIVE_ENABLED] = config.speed.keepAliveEnabled
            prefs[Keys.SPEED_KEEPALIVE_SEC] = config.speed.keepAliveIntervalSec.coerceIn(5, 300)
            prefs[Keys.SPEED_MTU] = config.speed.mtu.coerceIn(1280, 1500)
            prefs[Keys.SPEED_MAX_STREAMS] = config.speed.maxConcurrentStreams.coerceIn(8, 256)
            prefs[Keys.SPEED_AUTO_RECONNECT] = config.speed.autoReconnect
        }
    }

    suspend fun updateSmartMode(mode: SmartEngineMode) {
        context.gvpnDataStore.edit { prefs ->
            prefs[Keys.SMART_MODE] = mode.name
        }
    }

    suspend fun getTrustedHostKey(hostPortKey: String): String? {
        val prefKey = stringPreferencesKey("tofu_key_${sanitizeKey(hostPortKey)}")
        return context.gvpnDataStore.data.first()[prefKey]
    }

    suspend fun saveTrustedHostKey(hostPortKey: String, fingerprintSha256: String) {
        val prefKey = stringPreferencesKey("tofu_key_${sanitizeKey(hostPortKey)}")
        context.gvpnDataStore.edit { prefs ->
            prefs[prefKey] = fingerprintSha256
        }
    }

    private fun sanitizeKey(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_]"), "_")
}
