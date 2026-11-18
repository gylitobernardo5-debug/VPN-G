package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.GVpnDatabase
import com.example.data.GVpnRepository
import com.example.data.ServerProfileEntity
import com.example.data.VpnConfigStore
import com.example.model.BenchmarkSnapshot
import com.example.model.SmartEngineMode
import com.example.model.SshConfig
import com.example.model.TlsSniConfig
import com.example.model.VpnConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name resource is G-VPN`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("G-VPN", context.getString(R.string.app_name))
    }

    @Test
    fun `room database persists benchmark snapshots and server profiles`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, GVpnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = GVpnRepository(db.benchmarkDao(), db.serverProfileDao())

            val savedId = repo.saveBenchmark(
                BenchmarkSnapshot(
                    downloadMbps = 42.5,
                    uploadMbps = 18.2,
                    speedTestSource = "Internet Real (HTTPS)",
                    pingMs = 28.4,
                    jitterMs = 3.1,
                    packetLossPercent = 0.0,
                    connectionLatencyMs = 29.0,
                    sshSetupMs = 64.0,
                    sshSetupStatus = "Banner OK",
                    tlsSetupMs = 82.0,
                    tlsSetupStatus = "Gateway verificado (TLSv1.3)",
                    tlsNegotiatedProtocol = "TLSv1.3",
                    loopbackTcpMbps = 950.0,
                    loopbackRttMs = 0.25,
                    packetCodecPps = 180_000L,
                    packetCodecMbps = 1800.0,
                    packetCodecAvgUs = 5.5,
                    ramUsedMb = 1400.0,
                    ramTotalMb = 4096.0,
                    ramDeltaMb = 2.1,
                    stabilityPercent = 97.5,
                    performanceScore = 88,
                    primaryBottleneck = "Negociação TLS/SNI",
                    recommendations = listOf("Manter Smart Engine em AUTO")
                )
            )
            assertTrue(savedId > 0L)

            val history = repo.benchmarkHistory.first()
            assertEquals(1, history.size)
            assertEquals(88, history.first().performanceScore)
            assertEquals("TLSv1.3", history.first().tlsNegotiatedProtocol)

            repo.saveProfile(
                ServerProfileEntity(
                    name = "Gateway EU",
                    sshHost = "198.51.100.10",
                    sshPort = 22,
                    sshUsername = "user",
                    sshPassword = "secret",
                    sshUseTls = true,
                    sshTlsSni = "eu.example.net",
                    tlsHost = "eu.example.net",
                    tlsPort = 443,
                    tlsSni = "eu.example.net",
                    tlsGatewayMode = "HTTPS_CONNECT",
                    smartMode = SmartEngineMode.AUTO.name
                )
            )
            val profiles = repo.serverProfiles.first()
            assertEquals(1, profiles.size)
            assertEquals("Gateway EU", profiles.first().name)
        } finally {
            db.close()
        }
    }

    @Test
    fun `config store saves and reloads SSH and TLS settings`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VpnConfigStore(context)
        val input = VpnConfig(
            smartMode = SmartEngineMode.TLS_PRIORITY,
            ssh = SshConfig(host = "203.0.113.5", port = 2222, username = "admin", password = "pw"),
            tls = TlsSniConfig(gatewayHost = "gw.example.org", port = 443, sniHostname = "gw.example.org")
        )
        store.saveConfig(input)
        val loaded = store.getConfig()
        assertEquals(SmartEngineMode.TLS_PRIORITY, loaded.smartMode)
        assertEquals("203.0.113.5", loaded.ssh.host)
        assertEquals(2222, loaded.ssh.port)
        assertEquals("gw.example.org", loaded.tls.gatewayHost)
        assertEquals("gw.example.org", loaded.tls.effectiveSni())
    }
}
