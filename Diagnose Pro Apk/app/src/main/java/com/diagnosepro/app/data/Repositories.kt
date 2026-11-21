package com.diagnosepro.app.data

import android.content.Context
import androidx.room.Room
import com.diagnosepro.app.data.db.AppDatabase
import com.diagnosepro.app.data.db.SampleEntity
import com.diagnosepro.app.data.db.ScanEntity
import com.diagnosepro.app.data.db.SessionEntity
import com.diagnosepro.app.data.db.VehicleEntity
import com.diagnosepro.app.obd.Dtc
import com.diagnosepro.app.obd.DtcKind
import com.diagnosepro.app.obd.DtcReport
import com.diagnosepro.app.obd.VinDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "diagnosepro.db").build()
}

@Serializable
data class StoredDtc(val code: String, val kind: String, val ecu: String)

object DtcJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(StoredDtc.serializer())
    fun encode(list: List<Dtc>): String = json.encodeToString(ser, list.map { StoredDtc(it.code, it.kind.name, it.ecu) })
    fun decode(s: String): List<Dtc> = runCatching {
        json.decodeFromString(ser, s).map { Dtc(it.code, runCatching { DtcKind.valueOf(it.kind) }.getOrDefault(DtcKind.STORED), it.ecu) }
    }.getOrDefault(emptyList())
}

@Singleton
class HistoryRepository @Inject constructor(private val db: AppDatabase) {
    val scans: Flow<List<ScanEntity>> = db.scans().observeAll()
    val sessions: Flow<List<SessionEntity>> = db.sessions().observeAll()

    suspend fun saveScan(vehicleId: Long?, report: DtcReport, mileageKm: Double?, beforeClear: Boolean, demo: Boolean): Long =
        db.scans().insert(
            ScanEntity(
                vehicleId = vehicleId, timestamp = System.currentTimeMillis(), mileageKm = mileageKm,
                milOn = report.milOn, codesJson = DtcJson.encode(report.all), beforeClear = beforeClear, isDemo = demo,
            ),
        )

    suspend fun scan(id: Long) = db.scans().get(id)
    suspend fun deleteScan(id: Long) = db.scans().delete(id)

    suspend fun startSession(vehicleId: Long?, pids: List<String>, demo: Boolean): Long =
        db.sessions().insert(SessionEntity(vehicleId = vehicleId, startTime = System.currentTimeMillis(), pids = pids.joinToString(","), isDemo = demo))

    suspend fun addSamples(samples: List<SampleEntity>) { if (samples.isNotEmpty()) db.sessions().insertSamples(samples) }
    suspend fun updateSession(s: SessionEntity) = db.sessions().update(s)
    suspend fun session(id: Long) = db.sessions().get(id)
    suspend fun samples(id: Long) = db.sessions().samples(id)
    suspend fun samples(id: Long, pid: String) = db.sessions().samples(id, pid)
    suspend fun deleteSession(id: Long) = db.sessions().delete(id)
}

@Singleton
class VehicleRepository @Inject constructor(private val db: AppDatabase) {
    val vehicles: Flow<List<VehicleEntity>> = db.vehicles().observeAll()

    suspend fun get(id: Long) = db.vehicles().get(id)

    /** Finds the saved vehicle for this VIN (or adapter, for cars without VIN support), or creates one. */
    suspend fun resolve(vin: String?, adapterAddress: String?, fallbackName: String): VehicleEntity {
        if (vin != null) {
            db.vehicles().byVin(vin)?.let { return it }
            val info = VinDecoder.decode(vin)
            val name = listOfNotNull(info?.manufacturer, info?.modelYear?.toString()).joinToString(" ").ifBlank { vin }
            val v = VehicleEntity(vin = vin, name = name, manufacturer = info?.manufacturer, modelYear = info?.modelYear, adapterAddress = adapterAddress)
            return v.copy(id = db.vehicles().insert(v))
        }
        if (adapterAddress != null) db.vehicles().byAdapterNoVin(adapterAddress)?.let { return it }
        val v = VehicleEntity(vin = null, name = fallbackName, manufacturer = null, modelYear = null, adapterAddress = adapterAddress)
        return v.copy(id = db.vehicles().insert(v))
    }

    suspend fun setGauges(id: Long, keys: List<String>) {
        db.vehicles().get(id)?.let { db.vehicles().update(it.copy(gauges = keys.joinToString(","))) }
    }

    suspend fun rename(id: Long, name: String) {
        db.vehicles().get(id)?.let { db.vehicles().update(it.copy(name = name)) }
    }

    suspend fun delete(id: Long) = db.vehicles().delete(id)
}
