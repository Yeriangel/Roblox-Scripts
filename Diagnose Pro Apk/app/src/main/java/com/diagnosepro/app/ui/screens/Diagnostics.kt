package com.diagnosepro.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.diagnosepro.app.R
import com.diagnosepro.app.i18n.Bidi
import com.diagnosepro.app.obd.ObdDecoders
import com.diagnosepro.app.obd.VinDecoder
import com.diagnosepro.app.ui.DiagnosticsViewModel
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.EmptyState
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.KeyValueRow
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.components.SectionTitle
import com.diagnosepro.app.ui.countryRes
import com.diagnosepro.app.ui.ecuLabel
import com.diagnosepro.app.ui.formatPid
import com.diagnosepro.app.ui.monitorRes
import com.diagnosepro.app.ui.nav.Routes
import com.diagnosepro.app.ui.pidName
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.Ok
import com.diagnosepro.app.ui.theme.Warn
import java.util.Locale

@Composable
fun ToolsScreen(vm: MainViewModel, nav: NavController) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    data class T(val icon: ImageVector, val title: Int, val desc: Int, val route: String)
    val tools = buildList {
        add(T(Icons.AutoMirrored.Filled.FactCheck, R.string.rd_title, R.string.tool_readiness_desc, Routes.READINESS))
        add(T(Icons.Filled.CameraAlt, R.string.ff_title, R.string.tool_freeze_desc, Routes.FREEZE))
        add(T(Icons.Filled.DirectionsCar, R.string.vi_title, R.string.tool_vehicle_desc, Routes.VEHICLE))
        add(T(Icons.AutoMirrored.Filled.Assignment, R.string.tool_mode06, R.string.tool_mode06_desc, Routes.MODE06))
        add(T(Icons.Filled.LocalGasStation, R.string.tool_fuel, R.string.tool_fuel_desc, Routes.FUEL))
        add(T(Icons.Filled.Timer, R.string.tool_perf, R.string.tool_perf_desc, Routes.ACCEL))
        add(T(Icons.Filled.Sensors, R.string.tool_sensors, R.string.tool_sensors_desc, Routes.SENSORS))
        add(T(Icons.Filled.NotificationsActive, R.string.tool_alerts, R.string.tool_alerts_desc, Routes.ALERTS))
        add(T(Icons.Filled.Speed, R.string.tool_hud, R.string.tool_hud_desc, Routes.HUD))
        add(T(Icons.Filled.BatteryChargingFull, R.string.tool_battery, R.string.tool_battery_desc, Routes.BATTERY))
        add(T(Icons.Filled.Search, R.string.tool_dtc_search, R.string.tool_dtc_search_desc, Routes.DTC_SEARCH))
        add(T(Icons.Filled.Build, R.string.tool_maintenance, R.string.tool_maintenance_desc, Routes.MAINTENANCE))
        add(T(Icons.Filled.Functions, R.string.tool_custom, R.string.tool_custom_desc, Routes.CUSTOM_PIDS))
        if (settings?.developerConsole == true) add(T(Icons.Filled.Terminal, R.string.tool_console, R.string.tool_console_desc, Routes.CONSOLE))
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.tools_title), { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DrivingWarning(vm) }
            items(tools) { t ->
                InfoCard(onClick = { nav.navigate(t.route) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(t.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(t.desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)); Banner(stringResource(R.string.limits_note), color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

/** Common frame for one-shot read screens. */
@Composable
private fun ReadScreen(
    vm: MainViewModel, title: String, desc: String?, buttonText: String, busy: Boolean, onRead: () -> Unit, onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val state by vm.obd.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(title, onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DrivingWarning(vm) }
            desc?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (!state.isConnected) {
                item { Banner(stringResource(R.string.codes_need_connection)) }
            } else {
                item {
                    Button(onClick = onRead, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                        Text(if (busy) stringResource(R.string.loading) else buttonText)
                    }
                }
            }
            content()
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun ReadinessScreen(vm: MainViewModel, onBack: () -> Unit, dvm: DiagnosticsViewModel = hiltViewModel()) {
    val busy by dvm.busy.collectAsStateWithLifecycle()
    val data by dvm.monitors.collectAsStateWithLifecycle()
    val state by vm.obd.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.isConnected) { if (state.isConnected && data == null) dvm.readMonitors() }
    ReadScreen(vm, stringResource(R.string.rd_title), stringResource(R.string.rd_desc), stringResource(R.string.rd_read), busy, { dvm.readMonitors() }, onBack) {
        val since = data?.first
        if (since != null) {
            item {
                val ready = since.allReady && !since.milOn
                Surface(color = (if (ready) Ok else Warn).copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (since.allReady) stringResource(R.string.rd_summary_ready)
                            else pluralStringResource(R.plurals.rd_summary_not_ready, since.notReadyCount, since.notReadyCount),
                            style = MaterialTheme.typography.titleLarge, color = if (ready) Ok else Warn,
                        )
                        if (since.milOn) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.rd_mil_on_warning), color = Danger) }
                        if (!since.allReady) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.rd_drive_tip), style = MaterialTheme.typography.bodyMedium) }
                        if (since.isDiesel) Text(stringResource(R.string.rd_diesel), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.rd_since_clear)) }
            items(since.monitors.entries.toList()) { (m, st) -> MonitorRow(m, st) }
            data?.second?.let { cycle ->
                item { SectionTitle(stringResource(R.string.rd_this_cycle)) }
                items(cycle.monitors.entries.filter { it.value != ObdDecoders.MonitorState.NOT_SUPPORTED }) { (m, st) -> MonitorRow(m, st) }
            }
        }
    }
}

@Composable
private fun MonitorRow(m: ObdDecoders.Monitor, st: ObdDecoders.MonitorState) {
    val (icon, color, text) = when (st) {
        ObdDecoders.MonitorState.READY -> Triple(Icons.Filled.CheckCircle, Ok, R.string.rd_ready)
        ObdDecoders.MonitorState.NOT_READY -> Triple(Icons.Filled.Schedule, Warn, R.string.rd_not_ready)
        ObdDecoders.MonitorState.NOT_SUPPORTED -> Triple(Icons.Filled.RemoveCircleOutline, MaterialTheme.colorScheme.outline, R.string.rd_not_supported)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(monitorRes(m)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(text), color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FreezeFrameScreen(vm: MainViewModel, onBack: () -> Unit, nav: NavController, dvm: DiagnosticsViewModel = hiltViewModel()) {
    val busy by dvm.busy.collectAsStateWithLifecycle()
    val ff by dvm.freezeFrame.collectAsStateWithLifecycle()
    val read by dvm.freezeRead.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val units = settings?.units ?: return
    ReadScreen(vm, stringResource(R.string.ff_title), stringResource(R.string.ff_desc), stringResource(R.string.ff_read), busy, { dvm.readFreezeFrame() }, onBack) {
        val f = ff
        if (read && f == null) item { EmptyState(Icons.Filled.CameraAlt, stringResource(R.string.ff_title), stringResource(R.string.ff_none)) }
        if (f != null) {
            f.dtc?.let { code ->
                item {
                    InfoCard(onClick = { nav.navigate(Routes.code(code)) }) {
                        Text(stringResource(R.string.ff_caused_by, Bidi.ltr(code)), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item {
                InfoCard {
                    f.values.forEach { (def, v) ->
                        val (num, unit) = formatPid(def, v, units)
                        KeyValueRow(pidName(def.key), if (unit.isEmpty()) num else "$num $unit")
                    }
                }
            }
        }
    }
}

@Composable
fun VehicleInfoScreen(vm: MainViewModel, onBack: () -> Unit, dvm: DiagnosticsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val busy by dvm.busy.collectAsStateWithLifecycle()
    val info by dvm.vehicleInfo.collectAsStateWithLifecycle()
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val active by vm.obd.vehicle.collectAsStateWithLifecycle()
    val state by vm.obd.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(state.isConnected) { if (state.isConnected && info == null) dvm.readVehicleInfo() }

    ReadScreen(vm, stringResource(R.string.vi_title), null, stringResource(R.string.vi_read), busy, { dvm.readVehicleInfo() }, onBack) {
        info?.let { vi ->
            item {
                InfoCard {
                    val vin = vi.vin
                    if (vin == null) Text(stringResource(R.string.vi_no_vin))
                    else {
                        KeyValueRow(stringResource(R.string.vi_vin), vin)
                        VinDecoder.decode(vin)?.let { d ->
                            KeyValueRow(stringResource(R.string.vi_manufacturer), d.manufacturer ?: stringResource(R.string.country_unknown))
                            d.modelYear?.let { KeyValueRow(stringResource(R.string.vi_model_year), it.toString()) }
                            KeyValueRow(stringResource(R.string.vi_country), stringResource(countryRes(d.countryKey)), ltrValue = false)
                            d.checkDigitValid?.let { KeyValueRow(stringResource(R.string.vi_check_digit), stringResource(if (it) R.string.vi_check_ok else R.string.vi_check_bad), ltrValue = false) }
                        }
                    }
                }
            }
            if (vi.ecuNames.isNotEmpty()) item {
                SectionTitle(stringResource(R.string.vi_ecu_name))
                InfoCard { vi.ecuNames.forEach { (ecu, n) -> KeyValueRow(ecuLabel(ctx, ecu), n) } }
            }
            if (vi.calibrationIds.isNotEmpty()) item {
                SectionTitle(stringResource(R.string.vi_cal_ids))
                InfoCard { vi.calibrationIds.forEach { (ecu, n) -> KeyValueRow(ecuLabel(ctx, ecu), n) } }
            }
            if (vi.cvns.isNotEmpty()) item {
                SectionTitle(stringResource(R.string.vi_cvn))
                InfoCard { vi.cvns.forEach { (ecu, n) -> KeyValueRow(ecuLabel(ctx, ecu), n) } }
            }
        }
        item { SectionTitle(stringResource(R.string.vi_saved_vehicles)) }
        if (vehicles.isEmpty()) item { Text(stringResource(R.string.vi_no_vehicles), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(vehicles, key = { it.id }) { v ->
            InfoCard(onClick = { if (!state.isConnected) vm.selectVehicle(v.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.name, style = MaterialTheme.typography.titleMedium)
                        v.vin?.let { LtrText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (active?.id == v.id) Text(stringResource(R.string.vi_active), color = Ok, style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { renaming = v.id to v.name }) { Icon(Icons.Filled.Edit, stringResource(R.string.vi_rename)) }
                    if (active?.id != v.id) IconButton(onClick = { deleting = v.id }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                }
            }
        }
    }
    renaming?.let { (id, name) ->
        var text by remember(id) { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.vi_rename)) },
            text = { OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.vi_name)) }, singleLine = true) },
            confirmButton = { Button(onClick = { vm.renameVehicle(id, text.trim().ifEmpty { name }); renaming = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = { Button(onClick = { vm.deleteVehicle(id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun Mode06Screen(vm: MainViewModel, onBack: () -> Unit, dvm: DiagnosticsViewModel = hiltViewModel()) {
    val busy by dvm.busy.collectAsStateWithLifecycle()
    val results by dvm.mode06.collectAsStateWithLifecycle()
    val read by dvm.mode06Read.collectAsStateWithLifecycle()
    ReadScreen(vm, stringResource(R.string.tool_mode06), stringResource(R.string.tool_mode06_desc), stringResource(R.string.m06_read), busy, { dvm.readMode06() }, onBack) {
        val r = results
        if (read && r == null) item { Banner(stringResource(R.string.m06_not_supported)) }
        if (read && r != null && r.isEmpty()) item { Banner(stringResource(R.string.m06_none)) }
        if (r != null) items(r) { t ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.m06_test, Bidi.ltr("$%02X".format(t.mid)), Bidi.ltr("$%02X".format(t.tid))), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(if (t.passed) R.string.m06_pass else R.string.m06_fail), color = if (t.passed) Ok else Danger, style = MaterialTheme.typography.labelLarge)
                }
                fun f(v: Double) = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
                LtrText("${f(t.value)} ${t.unit}", style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.m06_limits, Bidi.ltr(f(t.min)), Bidi.ltr("${f(t.max)} ${t.unit}")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
