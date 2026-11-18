package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.BenchmarkSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "benchmark_records")
data class BenchmarkRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMs: Long,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val speedTestSource: String,
    val pingMs: Double,
    val jitterMs: Double,
    val packetLossPercent: Double,
    val connectionLatencyMs: Double,
    val sshSetupMs: Double?,
    val sshSetupStatus: String,
    val tlsSetupMs: Double?,
    val tlsSetupStatus: String,
    val tlsNegotiatedProtocol: String,
    val loopbackTcpMbps: Double,
    val loopbackRttMs: Double,
    val packetCodecPps: Long,
    val packetCodecMbps: Double,
    val packetCodecAvgUs: Double,
    val ramUsedMb: Double,
    val ramTotalMb: Double,
    val ramDeltaMb: Double,
    val stabilityPercent: Double,
    val performanceScore: Int,
    val primaryBottleneck: String,
    val recommendationsSerialized: String
)

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sshHost: String,
    val sshPort: Int,
    val sshUsername: String,
    val sshPassword: String,
    val sshUseTls: Boolean,
    val sshTlsSni: String,
    val tlsHost: String,
    val tlsPort: Int,
    val tlsSni: String,
    val tlsGatewayMode: String,
    val smartMode: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface BenchmarkDao {
    @Query("SELECT * FROM benchmark_records ORDER BY timestampMs DESC LIMIT 20")
    fun observeRecent(): Flow<List<BenchmarkRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BenchmarkRecordEntity): Long

    @Query("DELETE FROM benchmark_records")
    suspend fun clearAll()
}

@Dao
interface ServerProfileDao {
    @Query("SELECT * FROM server_profiles ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<ServerProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ServerProfileEntity): Long

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [BenchmarkRecordEntity::class, ServerProfileEntity::class],
    version = 1,
    exportSchema = false
)
abstract class GVpnDatabase : RoomDatabase() {
    abstract fun benchmarkDao(): BenchmarkDao
    abstract fun serverProfileDao(): ServerProfileDao

    companion object {
        @Volatile
        private var INSTANCE: GVpnDatabase? = null

        fun getInstance(context: Context): GVpnDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GVpnDatabase::class.java,
                    "gvpn_v6_local.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class GVpnRepository(
    private val benchmarkDao: BenchmarkDao,
    private val serverProfileDao: ServerProfileDao
) {
    val benchmarkHistory: Flow<List<BenchmarkSnapshot>> =
        benchmarkDao.observeRecent().map { list -> list.map { it.toSnapshot() } }

    val serverProfiles: Flow<List<ServerProfileEntity>> =
        serverProfileDao.observeAll()

    suspend fun saveBenchmark(snapshot: BenchmarkSnapshot): Long =
        benchmarkDao.insert(snapshot.toEntity())

    suspend fun clearBenchmarkHistory() = benchmarkDao.clearAll()

    suspend fun saveProfile(profile: ServerProfileEntity): Long =
        serverProfileDao.upsert(profile)

    suspend fun deleteProfile(id: Long) = serverProfileDao.deleteById(id)

    private fun BenchmarkRecordEntity.toSnapshot(): BenchmarkSnapshot = BenchmarkSnapshot(
        id = id,
        timestampMs = timestampMs,
        downloadMbps = downloadMbps,
        uploadMbps = uploadMbps,
        speedTestSource = speedTestSource,
        pingMs = pingMs,
        jitterMs = jitterMs,
        packetLossPercent = packetLossPercent,
        connectionLatencyMs = connectionLatencyMs,
        sshSetupMs = sshSetupMs,
        sshSetupStatus = sshSetupStatus,
        tlsSetupMs = tlsSetupMs,
        tlsSetupStatus = tlsSetupStatus,
        tlsNegotiatedProtocol = tlsNegotiatedProtocol,
        loopbackTcpMbps = loopbackTcpMbps,
        loopbackRttMs = loopbackRttMs,
        packetCodecPps = packetCodecPps,
        packetCodecMbps = packetCodecMbps,
        packetCodecAvgUs = packetCodecAvgUs,
        ramUsedMb = ramUsedMb,
        ramTotalMb = ramTotalMb,
        ramDeltaMb = ramDeltaMb,
        stabilityPercent = stabilityPercent,
        performanceScore = performanceScore,
        primaryBottleneck = primaryBottleneck,
        recommendations = recommendationsSerialized
            .split("||")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    )

    private fun BenchmarkSnapshot.toEntity(): BenchmarkRecordEntity = BenchmarkRecordEntity(
        id = id,
        timestampMs = timestampMs,
        downloadMbps = downloadMbps,
        uploadMbps = uploadMbps,
        speedTestSource = speedTestSource,
        pingMs = pingMs,
        jitterMs = jitterMs,
        packetLossPercent = packetLossPercent,
        connectionLatencyMs = connectionLatencyMs,
        sshSetupMs = sshSetupMs,
        sshSetupStatus = sshSetupStatus,
        tlsSetupMs = tlsSetupMs,
        tlsSetupStatus = tlsSetupStatus,
        tlsNegotiatedProtocol = tlsNegotiatedProtocol,
        loopbackTcpMbps = loopbackTcpMbps,
        loopbackRttMs = loopbackRttMs,
        packetCodecPps = packetCodecPps,
        packetCodecMbps = packetCodecMbps,
        packetCodecAvgUs = packetCodecAvgUs,
        ramUsedMb = ramUsedMb,
        ramTotalMb = ramTotalMb,
        ramDeltaMb = ramDeltaMb,
        stabilityPercent = stabilityPercent,
        performanceScore = performanceScore,
        primaryBottleneck = primaryBottleneck,
        recommendationsSerialized = recommendations.joinToString("||")
    )
}
