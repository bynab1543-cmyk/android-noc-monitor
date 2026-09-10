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

@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val siteId: String,
    val note: String,
    val host: String,
    val port: Int,
    val kindId: String,
    val username: String,
    val credentialId: String,
    val snmpCredentialId: String?,
    val createdAt: Long,
    val lastSeenAt: Long?,
    val status: String,
    val lastError: String?,
    val snapshotJson: String?,
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

@Dao
interface SiteDao {
    @Query("SELECT * FROM sites ORDER BY name")
    fun observe(): Flow<List<SiteEntity>>

    @Query("SELECT * FROM sites ORDER BY name")
    suspend fun all(): List<SiteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: SiteEntity)
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY note")
    fun observe(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices ORDER BY note")
    suspend fun all(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun byId(id: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE devices SET status = :status, lastError = :error, lastSeenAt = :seen, snapshotJson = :snapshot, lastRxBps = :rx, lastTxBps = :tx WHERE id = :id")
    suspend fun updateLive(id: String, status: String, error: String?, seen: Long?, snapshot: String?, rx: Long, tx: Long)
}

@Dao
interface TrafficDao {
    @Insert
    suspend fun insert(sample: TrafficSampleEntity)

    @Query("SELECT * FROM traffic_samples WHERE deviceId = :deviceId ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latest(deviceId: String): TrafficSampleEntity?

    @Query("SELECT * FROM traffic_samples WHERE deviceId = :deviceId AND timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun samplesSince(deviceId: String, fromMs: Long): List<TrafficSampleEntity>

    @Query("DELETE FROM traffic_samples WHERE timestampMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM traffic_samples WHERE deviceId = :deviceId")
    suspend fun clearDevice(deviceId: String)
}

@Database(
    entities = [SiteEntity::class, DeviceEntity::class, TrafficSampleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class NocDatabase : RoomDatabase() {
    abstract fun sites(): SiteDao
    abstract fun devices(): DeviceDao
    abstract fun traffic(): TrafficDao
}
