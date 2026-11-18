package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.SpeedEngineConfig
import com.example.model.TlsSniConfig
import com.example.model.VpnConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gvpnDataStore by preferencesDataStore(name = "gvpn_v6_tls_config")

class VpnConfigStore(private val context: Context) {
    private object Keys {
        val TLS_HOST = stringPreferencesKey("tls_host")
        val TLS_PORT = intPreferencesKey("tls_port")
        val TLS_SNI = stringPreferencesKey("tls_sni")
        val SPEED_BUFFER_KB = intPreferencesKey("speed_buffer_kb")
        val SPEED_TCP_NODELAY = stringPreferencesKey("speed_tcp_nodelay")
        val SPEED_KEEPALIVE = stringPreferencesKey("speed_keepalive")
        val SPEED_MTU = intPreferencesKey("speed_mtu")
        val SPEED_MAX_STREAMS = intPreferencesKey("speed_max_streams")
        val SPEED_AUTO_RECONNECT = stringPreferencesKey("speed_auto_reconnect")
    }

    val configFlow: Flow<VpnConfig> = context.gvpnDataStore.data.map { prefs ->
        VpnConfig(
            tls = TlsSniConfig(
                gatewayHost = prefs[Keys.TLS_HOST]?.trim().orEmpty(),
                port = prefs[Keys.TLS_PORT] ?: 443,
                sniHostname = prefs[Keys.TLS_SNI]?.trim().orEmpty()
            ),
            speed = SpeedEngineConfig(
                bufferSizeKb = prefs[Keys.SPEED_BUFFER_KB] ?: 64,
                tcpNoDelay = prefs[Keys.SPEED_TCP_NODELAY] != "false",
                keepAliveEnabled = prefs[Keys.SPEED_KEEPALIVE] != "false",
                mtu = prefs[Keys.SPEED_MTU] ?: 1500,
                maxConcurrentStreams = prefs[Keys.SPEED_MAX_STREAMS] ?: 64,
                autoReconnect = prefs[Keys.SPEED_AUTO_RECONNECT] != "false"
            )
        )
    }

    suspend fun getConfig(): VpnConfig = configFlow.first()

    suspend fun saveConfig(config: VpnConfig) {
        context.gvpnDataStore.edit { prefs ->
            prefs[Keys.TLS_HOST] = config.tls.gatewayHost.trim()
            prefs[Keys.TLS_PORT] = config.tls.port.coerceIn(1, 65535)
            prefs[Keys.TLS_SNI] = config.tls.sniHostname.trim()
            prefs[Keys.SPEED_BUFFER_KB] = config.speed.bufferSizeKb.coerceIn(8, 256)
            prefs[Keys.SPEED_TCP_NODELAY] = config.speed.tcpNoDelay.toString()
            prefs[Keys.SPEED_KEEPALIVE] = config.speed.keepAliveEnabled.toString()
            prefs[Keys.SPEED_MTU] = config.speed.mtu.coerceIn(1280, 1500)
            prefs[Keys.SPEED_MAX_STREAMS] = config.speed.maxConcurrentStreams.coerceIn(8, 256)
            prefs[Keys.SPEED_AUTO_RECONNECT] = config.speed.autoReconnect.toString()
        }
    }
}
