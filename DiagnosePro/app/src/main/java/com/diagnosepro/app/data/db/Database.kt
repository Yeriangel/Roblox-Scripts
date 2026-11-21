package com.diagnosepro.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vehicles", indices = [Index("vin", unique = true)])
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vin: String?,
    val name: String,
    val manufacturer: String?,
    val modelYear: Int?,
    val adapterAddress: String?,
    /** Comma separated PID keys, in display order. Empty = defaults. */
    val gauges: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** One DTC scan. [codesJson] holds a list of {code, kind, ecu}. */
@Entity(
    tableName = "scans",
    indices = [Index("vehicleId"), Index("timestamp")],
)
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long?,
    val timestamp: Long,
    val mileageKm: Double?,
    val milOn: Boolean,
    val codesJson: String,
    /** True when saved automatically right before a Mode 04 clear. */
    val beforeClear: Boolean = false,
    val isDemo: Boolean = false,
)

@Entity(tableName = "sessions", indices = [Index("vehicleId")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long?,
    val startTime: Long,
    val endTime: Long? = null,
    val pids: String,
    val durationS: Long = 0,
    val distanceKm: Double = 0.0,
    val maxSpeed: Double? = null,
    val avgRpm: Double? = null,
    val maxCoolant: Double? = null,
    val isDemo: Boolean = false,
)

@Entity(
    tableName = "samples",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index(value = ["sessionId", "pid"])],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val t: Long,
    val pid: String,
    val value: Double,
)

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY createdAt DESC") fun observeAll(): Flow<List<VehicleEntity>>
    @Query("SELECT * FROM vehicles WHERE id = :id") suspend fun get(id: Long): VehicleEntity?
    @Query("SELECT * FROM vehicles WHERE vin = :vin LIMIT 1") suspend fun byVin(vin: String): VehicleEntity?
    @Query("SELECT * FROM vehicles WHERE vin IS NULL AND adapterAddress = :address LIMIT 1") suspend fun byAdapterNoVin(address: String): VehicleEntity?
    @Insert suspend fun insert(v: VehicleEntity): Long
    @Update suspend fun update(v: VehicleEntity)
    @Query("DELETE FROM vehicles WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY timestamp DESC") fun observeAll(): Flow<List<ScanEntity>>
    @Query("SELECT * FROM scans WHERE id = :id") suspend fun get(id: Long): ScanEntity?
    @Insert suspend fun insert(s: ScanEntity): Long
    @Query("DELETE FROM scans WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM scans") suspend fun deleteAll()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startTime DESC") fun observeAll(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: Long): SessionEntity?
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: Long)
    @Insert suspend fun insertSamples(samples: List<SampleEntity>)
    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY t") suspend fun samples(sessionId: Long): List<SampleEntity>
    @Query("SELECT * FROM samples WHERE sessionId = :sessionId AND pid = :pid ORDER BY t") suspend fun samples(sessionId: Long, pid: String): List<SampleEntity>
}

@Database(entities = [VehicleEntity::class, ScanEntity::class, SessionEntity::class, SampleEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun scans(): ScanDao
    abstract fun sessions(): SessionDao
}
