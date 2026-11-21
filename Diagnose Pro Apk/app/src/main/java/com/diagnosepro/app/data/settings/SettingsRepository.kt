package com.diagnosepro.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.diagnosepro.app.domain.AlertRule
import com.diagnosepro.app.domain.EconomyUnit
import com.diagnosepro.app.domain.PressureUnit
import com.diagnosepro.app.domain.SpeedUnit
import com.diagnosepro.app.domain.TempUnit
import com.diagnosepro.app.domain.UnitPrefs
import com.diagnosepro.app.transport.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class PollingSpeed(val delayMs: Long) { FAST(50), NORMAL(250), SLOW(800) }

data class LastAdapter(val address: String, val name: String, val type: TransportType)

data class AppSettings(
    val units: UnitPrefs = UnitPrefs(),
    val theme: ThemeMode = ThemeMode.DARK,
    val polling: PollingSpeed = PollingSpeed.NORMAL,
    val autoConnect: Boolean = true,
    val keepScreenOn: Boolean = true,
    val developerConsole: Boolean = false,
    val onboardingDone: Boolean = false,
    val lastAdapter: LastAdapter? = null,
    val activeVehicleId: Long? = null,
    val graphPids: List<String> = listOf("rpm", "speed"),
    val logPids: List<String> = listOf("rpm", "speed", "coolant_temp", "maf", "throttle", "engine_load"),
    val alerts: List<AlertRule> = AlertRule.defaults,
    val alertSound: Boolean = true,
    /** Fuel price per litre, for trip cost. 0 = not set. */
    val fuelPrice: Double = 0.0,
    val currency: String = "₪",
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object K {
        val speed = stringPreferencesKey("unit_speed")
        val temp = stringPreferencesKey("unit_temp")
        val pressure = stringPreferencesKey("unit_pressure")
        val economy = stringPreferencesKey("unit_economy")
        val theme = stringPreferencesKey("theme")
        val polling = stringPreferencesKey("polling")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val devConsole = booleanPreferencesKey("dev_console")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val lastAddr = stringPreferencesKey("last_adapter_address")
        val lastName = stringPreferencesKey("last_adapter_name")
        val lastType = stringPreferencesKey("last_adapter_type")
        val vehicle = longPreferencesKey("active_vehicle")
        val graphPids = stringPreferencesKey("graph_pids")
        val logPids = stringPreferencesKey("log_pids")
        val alerts = stringPreferencesKey("alerts")
        val alertSound = booleanPreferencesKey("alert_sound")
        val fuelPrice = stringPreferencesKey("fuel_price")
        val currency = stringPreferencesKey("currency")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, def: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: def

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                units = UnitPrefs(
                    p.enum(K.speed, SpeedUnit.KMH), p.enum(K.temp, TempUnit.C),
                    p.enum(K.pressure, PressureUnit.KPA), p.enum(K.economy, EconomyUnit.L_PER_100KM),
                ),
                theme = p.enum(K.theme, ThemeMode.DARK),
                polling = p.enum(K.polling, PollingSpeed.NORMAL),
                autoConnect = p[K.autoConnect] ?: d.autoConnect,
                keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn,
                developerConsole = p[K.devConsole] ?: false,
                onboardingDone = p[K.onboarding] ?: false,
                lastAdapter = p[K.lastAddr]?.let { addr ->
                    LastAdapter(addr, p[K.lastName] ?: addr, p.enum(K.lastType, TransportType.CLASSIC))
                },
                activeVehicleId = p[K.vehicle],
                graphPids = p[K.graphPids]?.split(',')?.filter { it.isNotBlank() } ?: d.graphPids,
                logPids = p[K.logPids]?.split(',')?.filter { it.isNotBlank() } ?: d.logPids,
                alerts = p[K.alerts]?.split(';')?.mapNotNull { AlertRule.decode(it) } ?: d.alerts,
                alertSound = p[K.alertSound] ?: true,
                fuelPrice = p[K.fuelPrice]?.toDoubleOrNull() ?: 0.0,
                currency = p[K.currency] ?: d.currency,
            )
        }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setUnits(u: UnitPrefs) = edit {
        it[K.speed] = u.speed.name; it[K.temp] = u.temp.name; it[K.pressure] = u.pressure.name; it[K.economy] = u.economy.name
    }
    suspend fun setTheme(t: ThemeMode) = edit { it[K.theme] = t.name }
    suspend fun setPolling(p: PollingSpeed) = edit { it[K.polling] = p.name }
    suspend fun setAutoConnect(v: Boolean) = edit { it[K.autoConnect] = v }
    suspend fun setKeepScreenOn(v: Boolean) = edit { it[K.keepScreenOn] = v }
    suspend fun setDeveloperConsole(v: Boolean) = edit { it[K.devConsole] = v }
    suspend fun setOnboardingDone() = edit { it[K.onboarding] = true }
    suspend fun setActiveVehicle(id: Long?) = edit { if (id == null) it.remove(K.vehicle) else it[K.vehicle] = id }
    suspend fun setGraphPids(keys: List<String>) = edit { it[K.graphPids] = keys.joinToString(",") }
    suspend fun setAlerts(rules: List<AlertRule>) = edit { it[K.alerts] = rules.joinToString(";") { r -> r.encode() } }
    suspend fun setAlertSound(v: Boolean) = edit { it[K.alertSound] = v }
    suspend fun setFuelPrice(price: Double, currency: String) = edit { it[K.fuelPrice] = price.toString(); it[K.currency] = currency }
    suspend fun setLogPids(keys: List<String>) = edit { it[K.logPids] = keys.joinToString(",") }
    suspend fun setLastAdapter(a: LastAdapter?) = edit {
        if (a == null) { it.remove(K.lastAddr); it.remove(K.lastName); it.remove(K.lastType) }
        else { it[K.lastAddr] = a.address; it[K.lastName] = a.name; it[K.lastType] = a.type.name }
    }
}
