package com.diagnosepro.app.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import com.diagnosepro.app.R
import com.diagnosepro.app.data.dtc.Severity
import com.diagnosepro.app.domain.ConnectionState
import com.diagnosepro.app.domain.ErrorReason
import com.diagnosepro.app.domain.UnitPrefs
import com.diagnosepro.app.domain.Units
import com.diagnosepro.app.elm327.EcuRole
import com.diagnosepro.app.elm327.InitStage
import com.diagnosepro.app.elm327.ObdFrames
import com.diagnosepro.app.i18n.Bidi
import com.diagnosepro.app.i18n.LocaleManager
import com.diagnosepro.app.obd.DtcKind
import com.diagnosepro.app.obd.ObdDecoders
import com.diagnosepro.app.obd.PidDef
import com.diagnosepro.app.obd.Pids
import com.diagnosepro.app.obd.Quantity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@StringRes
fun pidNameRes(key: String): Int = when (key) {
    "engine_load" -> R.string.pid_engine_load
    "coolant_temp" -> R.string.pid_coolant_temp
    "stft_b1" -> R.string.pid_stft_b1
    "ltft_b1" -> R.string.pid_ltft_b1
    "stft_b2" -> R.string.pid_stft_b2
    "ltft_b2" -> R.string.pid_ltft_b2
    "fuel_pressure" -> R.string.pid_fuel_pressure
    "map" -> R.string.pid_map
    "rpm" -> R.string.pid_rpm
    "speed" -> R.string.pid_speed
    "timing_advance" -> R.string.pid_timing_advance
    "intake_temp" -> R.string.pid_intake_temp
    "maf" -> R.string.pid_maf
    "throttle" -> R.string.pid_throttle
    "o2_b1s1" -> R.string.pid_o2_b1s1
    "o2_b1s2" -> R.string.pid_o2_b1s2
    "o2_b1s3" -> R.string.pid_o2_b1s3
    "o2_b1s4" -> R.string.pid_o2_b1s4
    "o2_b2s1" -> R.string.pid_o2_b2s1
    "o2_b2s2" -> R.string.pid_o2_b2s2
    "o2_b2s3" -> R.string.pid_o2_b2s3
    "o2_b2s4" -> R.string.pid_o2_b2s4
    "run_time" -> R.string.pid_run_time
    "distance_mil" -> R.string.pid_distance_mil
    "fuel_level" -> R.string.pid_fuel_level
    "distance_cleared" -> R.string.pid_distance_cleared
    "baro" -> R.string.pid_baro
    "module_voltage" -> R.string.pid_module_voltage
    "ambient_temp" -> R.string.pid_ambient_temp
    "oil_temp" -> R.string.pid_oil_temp
    "fuel_rate" -> R.string.pid_fuel_rate
    "odometer" -> R.string.pid_odometer
    "rail_pressure_rel" -> R.string.pid_rail_pressure_rel
    "rail_pressure" -> R.string.pid_rail_pressure
    "egr_commanded" -> R.string.pid_egr_commanded
    "egr_error" -> R.string.pid_egr_error
    "evap_purge" -> R.string.pid_evap_purge
    "warmups_cleared" -> R.string.pid_warmups_cleared
    "cat_temp_b1s1" -> R.string.pid_cat_temp_b1s1
    "cat_temp_b2s1" -> R.string.pid_cat_temp_b2s1
    "cat_temp_b1s2" -> R.string.pid_cat_temp_b1s2
    "absolute_load" -> R.string.pid_absolute_load
    "commanded_lambda" -> R.string.pid_commanded_lambda
    "relative_throttle" -> R.string.pid_relative_throttle
    "accel_pedal" -> R.string.pid_accel_pedal
    "commanded_throttle" -> R.string.pid_commanded_throttle
    "time_mil_on" -> R.string.pid_time_mil_on
    "time_since_clear" -> R.string.pid_time_since_clear
    "ethanol" -> R.string.pid_ethanol
    "rail_pressure_abs" -> R.string.pid_rail_pressure_abs
    "relative_pedal" -> R.string.pid_relative_pedal
    "hybrid_battery" -> R.string.pid_hybrid_battery
    "injection_timing" -> R.string.pid_injection_timing
    "torque_demand" -> R.string.pid_torque_demand
    "torque_actual" -> R.string.pid_torque_actual
    "torque_reference" -> R.string.pid_torque_reference
    "boost" -> R.string.pid_boost
    "power_est" -> R.string.pid_power_est
    "consumption_est" -> R.string.pid_consumption_est
    else -> R.string.pid_adapter_voltage
}

@StringRes
fun monitorRes(m: ObdDecoders.Monitor): Int = when (m) {
    ObdDecoders.Monitor.MISFIRE -> R.string.mon_misfire
    ObdDecoders.Monitor.FUEL_SYSTEM -> R.string.mon_fuel_system
    ObdDecoders.Monitor.COMPONENTS -> R.string.mon_components
    ObdDecoders.Monitor.CATALYST -> R.string.mon_catalyst
    ObdDecoders.Monitor.HEATED_CATALYST -> R.string.mon_heated_catalyst
    ObdDecoders.Monitor.EVAP -> R.string.mon_evap
    ObdDecoders.Monitor.SECONDARY_AIR -> R.string.mon_secondary_air
    ObdDecoders.Monitor.AC_REFRIGERANT -> R.string.mon_ac_refrigerant
    ObdDecoders.Monitor.O2_SENSOR -> R.string.mon_o2_sensor
    ObdDecoders.Monitor.O2_HEATER -> R.string.mon_o2_heater
    ObdDecoders.Monitor.EGR -> R.string.mon_egr
    ObdDecoders.Monitor.NMHC_CATALYST -> R.string.mon_nmhc_catalyst
    ObdDecoders.Monitor.NOX_SCR -> R.string.mon_nox_scr
    ObdDecoders.Monitor.BOOST_PRESSURE -> R.string.mon_boost_pressure
    ObdDecoders.Monitor.EXHAUST_SENSOR -> R.string.mon_exhaust_sensor
    ObdDecoders.Monitor.PM_FILTER -> R.string.mon_pm_filter
    ObdDecoders.Monitor.EGR_VVT -> R.string.mon_egr_vvt
}

@StringRes
fun countryRes(key: String): Int = when (key) {
    "us" -> R.string.country_us; "ca" -> R.string.country_ca; "mx" -> R.string.country_mx; "na" -> R.string.country_na
    "au" -> R.string.country_au; "nz" -> R.string.country_nz; "sa" -> R.string.country_sa; "br" -> R.string.country_br
    "jp" -> R.string.country_jp; "kr" -> R.string.country_kr; "cn" -> R.string.country_cn; "in" -> R.string.country_in
    "th" -> R.string.country_th; "tr" -> R.string.country_tr; "asia" -> R.string.country_asia; "uk" -> R.string.country_uk
    "de" -> R.string.country_de; "cz" -> R.string.country_cz; "ch" -> R.string.country_ch; "ro" -> R.string.country_ro
    "fr" -> R.string.country_fr; "es" -> R.string.country_es; "ru" -> R.string.country_ru; "se" -> R.string.country_se
    "be" -> R.string.country_be; "it" -> R.string.country_it; "za" -> R.string.country_za; "eu" -> R.string.country_eu
    else -> R.string.country_unknown
}

@StringRes
fun errorRes(r: ErrorReason): Int = when (r) {
    ErrorReason.BLUETOOTH_OFF -> R.string.error_bluetooth_off
    ErrorReason.PERMISSION -> R.string.error_permission
    ErrorReason.DEVICE_NOT_FOUND -> R.string.error_device_not_found
    ErrorReason.SOCKET_FAILED -> R.string.error_socket_failed
    ErrorReason.NO_OBD_SERVICE -> R.string.error_no_obd_service
    ErrorReason.TIMEOUT -> R.string.error_timeout
    ErrorReason.NOT_ELM -> R.string.error_not_elm
    ErrorReason.NO_VEHICLE -> R.string.error_no_vehicle
    ErrorReason.BUS_ERROR -> R.string.error_bus_error
    ErrorReason.LINK_LOST -> R.string.error_link_lost
    ErrorReason.UNKNOWN -> R.string.error_unknown
}

@StringRes
fun errorHintRes(r: ErrorReason): Int = when (r) {
    ErrorReason.BLUETOOTH_OFF -> R.string.error_hint_bluetooth_off
    ErrorReason.PERMISSION -> R.string.error_hint_permission
    ErrorReason.DEVICE_NOT_FOUND -> R.string.error_hint_device_not_found
    ErrorReason.SOCKET_FAILED -> R.string.error_hint_socket_failed
    ErrorReason.NO_OBD_SERVICE -> R.string.error_hint_no_obd_service
    ErrorReason.TIMEOUT -> R.string.error_hint_timeout
    ErrorReason.NOT_ELM -> R.string.error_hint_not_elm
    ErrorReason.NO_VEHICLE -> R.string.error_hint_no_vehicle
    ErrorReason.BUS_ERROR -> R.string.error_hint_bus_error
    ErrorReason.LINK_LOST -> R.string.error_hint_link_lost
    ErrorReason.UNKNOWN -> R.string.error_hint_unknown
}

@StringRes
fun severityRes(s: Severity): Int = when (s) {
    Severity.LOW -> R.string.sev_low; Severity.MEDIUM -> R.string.sev_medium
    Severity.HIGH -> R.string.sev_high; Severity.STOP -> R.string.sev_stop
}

@StringRes
fun severityDescRes(s: Severity): Int = when (s) {
    Severity.LOW -> R.string.sev_low_desc; Severity.MEDIUM -> R.string.sev_medium_desc
    Severity.HIGH -> R.string.sev_high_desc; Severity.STOP -> R.string.sev_stop_desc
}

@StringRes
fun dtcKindRes(k: DtcKind): Int = when (k) {
    DtcKind.STORED -> R.string.codes_stored; DtcKind.PENDING -> R.string.codes_pending; DtcKind.PERMANENT -> R.string.codes_permanent
}

/** "Engine (7E8)" etc. Address stays LTR. */
fun ecuLabel(ctx: Context, ecu: String): String {
    val a = Bidi.ltr(ecu)
    return when (ObdFrames.ecuDisplayKey(ecu)) {
        EcuRole.ENGINE -> ctx.getString(R.string.ecu_engine, a)
        EcuRole.TRANSMISSION -> ctx.getString(R.string.ecu_transmission, a)
        EcuRole.ABS -> ctx.getString(R.string.ecu_abs, a)
        EcuRole.OTHER -> ctx.getString(R.string.ecu_other, a)
    }
}

/** Formats a PID value in the user's units: returns (number, unit). */
fun formatPid(def: PidDef, base: Double, units: UnitPrefs): Pair<String, String> {
    if (def.isCustom) return Units.format(base, def.decimals) to def.customUnit.orEmpty()
    if (def.quantity == Quantity.TIME) return Units.formatDuration(base.toLong()) to ""
    val v = Units.convert(def.quantity, base, units)
    return Units.format(v, Units.decimalsFor(def.quantity, def.decimals, units)) to Units.unitLabel(def.quantity, units)
}

fun stateText(ctx: Context, s: ConnectionState): String = when (s) {
    ConnectionState.Disconnected -> ctx.getString(R.string.status_disconnected)
    is ConnectionState.Connecting -> ctx.getString(R.string.status_connecting, Bidi.ltr(s.deviceName))
    is ConnectionState.Initializing -> ctx.getString(
        when (s.stage) {
            InitStage.RESETTING -> R.string.status_init_resetting
            InitStage.CONFIGURING -> R.string.status_init_configuring
            InitStage.SEARCHING_PROTOCOL -> R.string.status_searching_protocol
            InitStage.READING_INFO -> R.string.status_reading_info
        },
    )
    is ConnectionState.Connected -> ctx.getString(R.string.status_connected, Bidi.ltr(s.protocol?.displayName ?: "OBD2"))
    is ConnectionState.Reconnecting -> ctx.getString(R.string.status_reconnecting, s.attempt, s.delaySec)
    is ConnectionState.Error -> ctx.getString(R.string.status_error, ctx.getString(errorRes(s.reason)))
}

fun formatDateTime(time: Long, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(time))

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

@Composable
fun isHebrewUi(): Boolean = LocaleManager.isHebrew(currentLocale())

@Composable
fun isRtl(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

/** Display name of any PID key, including user-defined ones. */
@Composable
fun pidName(key: String): String = Pids.byKey(key)?.customName ?: stringResource(pidNameRes(key))

fun pidLabel(ctx: Context, key: String): String = Pids.byKey(key)?.customName ?: ctx.getString(pidNameRes(key))

fun unitOf(def: PidDef, units: UnitPrefs): String = def.customUnit ?: Units.unitLabel(def.quantity, units)
