package com.noc.monitor.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val vendor: String,
    val productFamily: String,
    val transport: String,
    val host: String,
    val port: Int,
    val username: String,
    val credentialId: String,
    val allowInsecureTls: Boolean,
    val createdAt: Long,
    val lastSeenAt: Long?,
    val status: String,
    val lastError: String?,
    val identity: String?,
    val model: String?,
    val version: String?,
    val lastRxBps: Long,
    val lastTxBps: Long,
)

@Entity(tableName = "traffic_samples")
data class TrafficSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val interfaceName: String,
    val timestampMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val txPackets: Long,
    val rxBps: Long,
    val txBps: Long,
)

@Entity(tableName = "traffic_peaks")
data class TrafficPeakEntity(
    @PrimaryKey val deviceId: String,
    val interfaceName: String,
    val peakRxBps: Long,
    val peakTxBps: Long,
    val peakTotalBps: Long,
    val peakAtMs: Long,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val severity: String,
    val title: String,
    val detail: String,
    val createdAtMs: Long,
    val acknowledged: Boolean,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY displayName")
    fun observe(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices ORDER BY displayName")
    suspend fun all(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun byId(id: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE devices SET status = :status, lastError = :error, lastSeenAt = :seen, identity = :identity, model = :model, version = :version, lastRxBps = :rx, lastTxBps = :tx WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        error: String?,
        seen: Long?,
        identity: String?,
        model: String?,
        version: String?,
        rx: Long,
        tx: Long,
    )
}

@Dao
interface TrafficDao {
    @Insert
    suspend fun insert(sample: TrafficSampleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeak(peak: TrafficPeakEntity)

    @Query("SELECT * FROM traffic_samples WHERE deviceId = :deviceId AND timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun samplesSince(deviceId: String, fromMs: Long): List<TrafficSampleEntity>

    @Query("SELECT * FROM traffic_samples WHERE deviceId = :deviceId AND timestampMs >= :fromMs AND interfaceName = :iface ORDER BY timestampMs ASC")
    suspend fun samplesSinceIface(deviceId: String, fromMs: Long, iface: String): List<TrafficSampleEntity>

    @Query("SELECT * FROM traffic_peaks")
    suspend fun allPeaks(): List<TrafficPeakEntity>

    @Query("SELECT * FROM traffic_peaks WHERE deviceId = :deviceId")
    suspend fun peak(deviceId: String): TrafficPeakEntity?

    @Query("DELETE FROM traffic_samples WHERE timestampMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM traffic_samples WHERE deviceId = :deviceId")
    suspend fun clearDevice(deviceId: String)

    @Query("DELETE FROM traffic_peaks WHERE deviceId = :deviceId")
    suspend fun clearPeak(deviceId: String)
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY createdAtMs DESC")
    fun observe(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE deviceId = :deviceId ORDER BY createdAtMs DESC")
    fun observeDevice(deviceId: String): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE acknowledged = 0")
    fun observeActiveCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alert: AlertEntity)

    @Query("UPDATE alerts SET acknowledged = 1 WHERE id = :id")
    suspend fun ack(id: String)

    @Query("DELETE FROM alerts WHERE deviceId = :deviceId")
    suspend fun clearDevice(deviceId: String)
}

@Database(
    entities = [DeviceEntity::class, TrafficSampleEntity::class, TrafficPeakEntity::class, AlertEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NocDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun traffic(): TrafficDao
    abstract fun alerts(): AlertDao
}
