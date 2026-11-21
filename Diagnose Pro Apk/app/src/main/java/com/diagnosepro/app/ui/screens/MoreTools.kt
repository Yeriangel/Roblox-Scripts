package com.diagnosepro.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.diagnosepro.app.R
import com.diagnosepro.app.data.db.CustomPidEntity
import com.diagnosepro.app.data.db.MaintenanceEntity
import com.diagnosepro.app.domain.AlertRule
import com.diagnosepro.app.domain.BatteryTest
import com.diagnosepro.app.domain.PerfResults
import com.diagnosepro.app.domain.PerformanceTracker
import com.diagnosepro.app.domain.Units
import com.diagnosepro.app.i18n.Bidi
import com.diagnosepro.app.obd.CustomPid
import com.diagnosepro.app.obd.Expression
import com.diagnosepro.app.obd.Pids
import com.diagnosepro.app.obd.Quantity
import com.diagnosepro.app.ui.ExtrasViewModel
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.KeyValueRow
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.components.SectionTitle
import com.diagnosepro.app.ui.currentLocale
import com.diagnosepro.app.ui.formatDateTime
import com.diagnosepro.app.ui.formatPid
import com.diagnosepro.app.ui.isHebrewUi
import com.diagnosepro.app.ui.nav.Routes
import com.diagnosepro.app.ui.pidName
import com.diagnosepro.app.ui.theme.Accent
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.Ok
import com.diagnosepro.app.ui.theme.Warn
import com.diagnosepro.app.ui.unitOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private fun num(v: Double, d: Int = 2) = String.format(Locale.US, "%.${d}f", v)

/** Alarm rule as LTR text, e.g. "> 110 °C". Thresholds are stored in metric base units. */
private fun ruleText(rule: AlertRule): String {
    val def = Pids.byKey(rule.key)
    val unit = def?.customUnit ?: def?.let { Units.unitLabel(it.quantity, com.diagnosepro.app.domain.UnitPrefs()) }.orEmpty()
    return Bidi.ltr("${if (rule.above) ">" else "<"} ${Units.format(rule.threshold, if (rule.threshold % 1.0 == 0.0) 0 else 1)} $unit".trim())
}

/** Banner listing alarms that are firing right now. Shown on every main screen. */
@Composable
fun AlertBanner(vm: MainViewModel, modifier: Modifier = Modifier) {
    val alerts by vm.obd.alerts.collectAsStateWithLifecycle()
    Column(modifier) {
        alerts.forEach { a ->
            Banner(stringResource(R.string.alert_banner, pidName(a.rule.key) + " " + ruleText(a.rule)), color = Danger, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ All sensors

@Composable
fun AllSensorsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val live by vm.obd.live.collectAsStateWithLifecycle()
    val supported by vm.obd.supportedPids.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val units = settings?.units ?: return
    val keys = remember(supported, Pids.customDefs.size) { Pids.allKeys().filter { k -> Pids.byKey(k)?.let { Pids.isAvailable(it, supported) } == true } }
    DisposableEffect(keys) {
        vm.obd.setDemand("sensors", keys)
        onDispose { vm.obd.setDemand("sensors", emptyList()) }
    }
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.tool_sensors), onBack) {
            IconButton(onClick = { vm.obd.resetStats() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.reset_minmax)) }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            if (!state.isConnected) item { Banner(stringResource(R.string.codes_need_connection)) }
            else if (live.isEmpty()) item { Text(stringResource(R.string.sensors_waiting)) }
            items(keys, key = { it }) { key ->
                val def = Pids.byKey(key) ?: return@items
                val v = live[key]?.value
                val stats = vm.obd.statsOf(key)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pidName(key), style = MaterialTheme.typography.bodyLarge)
                        if (stats != null) Text(
                            stringResource(R.string.stats_minmax, Bidi.ltr(formatPid(def, stats.first, units).first), Bidi.ltr(formatPid(def, stats.second, units).first)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val (n, u) = if (v != null) formatPid(def, v, units) else "—" to unitOf(def, units)
                    LtrText(if (u.isEmpty()) n else "$n $u", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (v != null && def.isWarning(v)) Danger else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Custom PIDs

@Composable
fun CustomPidsScreen(vm: MainViewModel, onBack: () -> Unit, evm: ExtrasViewModel = hiltViewModel()) {
    val list by evm.customPids.collectAsStateWithLifecycle()
    val live by vm.obd.live.collectAsStateWithLifecycle()
    val state by vm.obd.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CustomPidEntity?>(null) }
    val keys = list.map { CustomPid.key(it.id) }
    DisposableEffect(keys) {
        vm.obd.setDemand("custom", keys)
        onDispose { vm.obd.setDemand("custom", emptyList()) }
    }
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.tool_custom), onBack) {
            IconButton(onClick = { editing = CustomPidEntity(name = "", command = "", formula = "", unit = "") }) { Icon(Icons.Filled.Add, stringResource(R.string.custom_add)) }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Banner(stringResource(R.string.custom_note), color = MaterialTheme.colorScheme.secondary) }
            if (list.isEmpty()) item { Text(stringResource(R.string.custom_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.id }) { p ->
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            LtrText("${p.command}  =  ${p.formula}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.isConnected) {
                                val v = live[CustomPid.key(p.id)]?.value
                                LtrText(v?.let { "${Units.format(it, 1)} ${p.unit}" } ?: "— (${stringResource(R.string.custom_no_answer)})",
                                    style = MaterialTheme.typography.titleLarge, color = Accent)
                            }
                        }
                        IconButton(onClick = { editing = p }) { Icon(Icons.Filled.Edit, stringResource(R.string.custom_edit)) }
                        IconButton(onClick = { evm.deleteCustomPid(p.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
            item { Button(onClick = { editing = CustomPidEntity(name = "", command = "", formula = "", unit = "") }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.custom_add)) } }
        }
    }
    editing?.let { p ->
        var name by remember(p) { mutableStateOf(p.name) }
        var command by remember(p) { mutableStateOf(p.command) }
        var formula by remember(p) { mutableStateOf(p.formula) }
        var unit by remember(p) { mutableStateOf(p.unit) }
        var min by remember(p) { mutableStateOf(num(p.minValue, 0)) }
        var max by remember(p) { mutableStateOf(num(p.maxValue, 0)) }
        val cmdOk = CustomPid.normalizeCommand(command)
        val formulaError = runCatching { Expression.parse(formula); null as String? }.getOrElse { it.message ?: "?" }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (p.id == 0L) R.string.custom_add else R.string.custom_edit)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.custom_name)) }, singleLine = true)
                    OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.custom_command)) }, singleLine = true,
                        isError = command.isNotEmpty() && cmdOk == null, supportingText = { Text(stringResource(if (command.isNotEmpty() && cmdOk == null) R.string.custom_bad_command else R.string.custom_command_hint)) })
                    OutlinedTextField(formula, { formula = it }, label = { Text(stringResource(R.string.custom_formula)) }, singleLine = true,
                        isError = formula.isNotEmpty() && formulaError != null,
                        supportingText = { Text(if (formula.isNotEmpty() && formulaError != null) stringResource(R.string.custom_bad_formula, formulaError) else stringResource(R.string.custom_formula_hint)) })
                    OutlinedTextField(unit, { unit = it }, label = { Text(stringResource(R.string.custom_unit)) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(min, { min = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.custom_min)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(max, { max = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.custom_max)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            },
            confirmButton = {
                Button(enabled = name.isNotBlank() && cmdOk != null && formulaError == null, onClick = {
                    evm.saveCustomPid(p.copy(name = name.trim(), command = cmdOk!!, formula = formula.trim(), unit = unit.trim(),
                        minValue = min.toDoubleOrNull() ?: 0.0, maxValue = max.toDoubleOrNull() ?: 100.0))
                    editing = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ------------------------------------------------------------------ Alarms

@Composable
fun AlertsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    var adding by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.tool_alerts), onBack) { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.alerts_add)) } }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { vm.setAlertSound(!s.alertSound) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.alerts_sound), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(s.alertSound, { vm.setAlertSound(it) })
                }
                Text(stringResource(R.string.alerts_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.alerts.isEmpty()) item { Text(stringResource(R.string.alerts_empty)) }
            items(s.alerts) { r ->
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pidName(r.key), style = MaterialTheme.typography.titleMedium)
                            Text(ruleText(r), style = MaterialTheme.typography.titleLarge, color = Accent)
                        }
                        IconButton(onClick = { vm.setAlerts(s.alerts - r) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
            item { Button(onClick = { adding = true }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.alerts_add)) } }
        }
    }
    if (adding) {
        val keys = remember { Pids.allKeys().filter { Pids.byKey(it)?.quantity != Quantity.TIME } }
        var key by remember { mutableStateOf("coolant_temp") }
        var above by remember { mutableStateOf(true) }
        var value by remember { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        val def = Pids.byKey(key)
        val unit = def?.customUnit ?: def?.let { Units.unitLabel(it.quantity, com.diagnosepro.app.domain.UnitPrefs()) }.orEmpty()
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.alerts_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box {
                        OutlinedButton(onClick = { menu = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.alerts_sensor) + ": " + pidName(key)) }
                        DropdownMenu(menu, { menu = false }) {
                            keys.forEach { k -> DropdownMenuItem({ Text(pidName(k)) }, onClick = { key = k; menu = false }) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(above, { above = true }, { Text(stringResource(R.string.alerts_above)) })
                        FilterChip(!above, { above = false }, { Text(stringResource(R.string.alerts_below)) })
                    }
                    OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.alerts_value, Bidi.ltr(unit))) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                Button(enabled = value.toDoubleOrNull() != null, onClick = {
                    vm.setAlerts((s.alerts + AlertRule(key, above, value.toDouble())).distinct()); adding = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ------------------------------------------------------------------ HUD

/** Head-up display: black screen, huge speed, optionally mirrored to reflect in the windshield. */
@Composable
fun HudScreen(vm: MainViewModel, onBack: () -> Unit) {
    val live by vm.obd.live.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val units = settings?.units ?: return
    var mirror by rememberSaveable { mutableStateOf(true) }
    DisposableEffect(Unit) {
        vm.obd.setDemand("hud", listOf("speed", "rpm", "coolant_temp"))
        onDispose { vm.obd.setDemand("hud", emptyList()) }
    }
    val speedDef = Pids.byKey("speed")!!
    val speed = live["speed"]?.value
    val rpm = live["rpm"]?.value
    val coolant = live["coolant_temp"]?.value
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        // Mirroring flips the whole readout horizontally; controls stay readable.
        Column(
            Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirror) -1f else 1f },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            LtrText(speed?.let { formatPid(speedDef, it, units).first } ?: "--", style = MaterialTheme.typography.displayLarge.copy(fontSize = 150.sp, fontWeight = FontWeight.Black), color = Color(0xFF7CFFB2))
            LtrText(Units.unitLabel(Quantity.SPEED, units), style = MaterialTheme.typography.headlineMedium, color = Color(0xFF7CFFB2))
            Spacer(Modifier.height(24.dp))
            LtrText("${rpm?.let { Units.format(it, 0) } ?: "--"} rpm", style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp), color = Color.White)
            coolant?.let {
                val (n, u) = formatPid(Pids.byKey("coolant_temp")!!, it, units)
                LtrText("$n $u", style = MaterialTheme.typography.headlineMedium, color = if (it > 105) Danger else Color.White)
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.close), color = Color.Gray) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { mirror = !mirror }) {
                Icon(Icons.Filled.Flip, null, tint = Color.Gray); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.hud_mirror), color = Color.Gray)
            }
        }
        Text(stringResource(R.string.hud_hint), Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------ Performance

@Composable
fun PerformanceScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val live by vm.obd.live.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val units = settings?.units ?: return
    DisposableEffect(Unit) {
        vm.obd.setDemand("perf", listOf("speed", "power_est"))
        onDispose { vm.obd.setDemand("perf", emptyList()) }
    }
    val tracker = remember { PerformanceTracker() }
    var results by remember { mutableStateOf(PerfResults()) }
    var peakPower by remember { mutableStateOf<Double?>(null) }
    val speed = live["speed"]
    LaunchedEffect(speed?.time) {
        speed?.let { tracker.add(it.time, it.value); results = tracker.results }
        live["power_est"]?.value?.let { p -> if (p > (peakPower ?: 0.0)) peakPower = p }
    }
    fun sec(v: Double?) = v?.let { "${num(it)} s" } ?: "—"
    Scaffold(topBar = { BackTopBar(stringResource(R.string.tool_perf), onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.perf_text))
            if (!state.isConnected) { Banner(stringResource(R.string.codes_need_connection)); return@Column }
            val (n, u) = formatPid(Pids.byKey("speed")!!, speed?.value ?: 0.0, units)
            LtrText("$n $u", style = MaterialTheme.typography.displayLarge, color = Accent)
            if (tracker.running) Text(stringResource(R.string.perf_running), color = Warn)
            InfoCard {
                KeyValueRow(stringResource(R.string.perf_0_60), sec(results.zeroTo60))
                KeyValueRow(stringResource(R.string.perf_0_100), sec(results.zeroTo100))
                KeyValueRow(stringResource(R.string.perf_80_120), sec(results.eightyTo120))
                KeyValueRow(stringResource(R.string.perf_quarter), sec(results.quarterMileTime))
                KeyValueRow(stringResource(R.string.perf_trap), results.quarterMileSpeed?.let { "${num(it, 0)} km/h" } ?: "—")
                KeyValueRow(stringResource(R.string.perf_brake), sec(results.braking100to0Time))
                KeyValueRow(stringResource(R.string.perf_brake_dist), results.braking100to0Dist?.let { "${num(it, 1)} m" } ?: "—")
                KeyValueRow(stringResource(R.string.perf_max_speed), "${num(results.maxSpeed, 0)} km/h")
                KeyValueRow(stringResource(R.string.perf_power), peakPower?.let { "${num(it, 0)} hp" } ?: "—")
            }
            OutlinedButton(onClick = { tracker.reset(); results = PerfResults(); peakPower = null }) { Text(stringResource(R.string.perf_reset)) }
            Text(stringResource(R.string.perf_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Battery test

@Composable
fun BatteryTestScreen(vm: MainViewModel, onBack: () -> Unit, evm: ExtrasViewModel = hiltViewModel()) {
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var resting by remember { mutableStateOf<Double?>(null) }
    var crank by remember { mutableStateOf<Double?>(null) }
    var charging by remember { mutableStateOf<Double?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val measuring = stringResource(R.string.battery_measuring)
    val crankNow = stringResource(R.string.battery_crank_now)

    suspend fun average(seconds: Int): Double? {
        val vals = mutableListOf<Double>()
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < end) { evm.voltage()?.let { vals += it }; delay(150) }
        return vals.takeIf { it.isNotEmpty() }?.average()
    }
    suspend fun minimum(seconds: Int): Double? {
        var min: Double? = null
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < end) { evm.voltage()?.let { v -> min = min?.let { minOf(it, v) } ?: v }; delay(20) }
        return min
    }

    @Composable
    fun Result(label: String, v: Double?, verdict: BatteryTest.Verdict?) {
        if (v == null || verdict == null) return
        val (color, text) = when (verdict) {
            BatteryTest.Verdict.GOOD -> Ok to R.string.verdict_good
            BatteryTest.Verdict.WEAK -> Warn to R.string.verdict_weak
            BatteryTest.Verdict.BAD -> Danger to R.string.verdict_bad
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            LtrText("${num(v)} V", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(text), color = color, style = MaterialTheme.typography.labelLarge)
        }
    }

    val r = BatteryTest.evaluate(resting, crank, charging)
    Scaffold(topBar = { BackTopBar(stringResource(R.string.tool_battery), onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.battery_text))
            if (!state.isConnected) { Banner(stringResource(R.string.codes_need_connection)); return@Column }
            busy?.let { Banner(it, color = Warn) }
            SectionTitle(stringResource(R.string.battery_step1))
            Button(enabled = busy == null, onClick = { scope.launch { busy = measuring; resting = average(3); busy = null } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.battery_measure_rest)) }
            Result(stringResource(R.string.battery_resting), resting, r.resting)
            SectionTitle(stringResource(R.string.battery_step2))
            Button(enabled = busy == null, onClick = { scope.launch { busy = crankNow; crank = minimum(15); busy = null } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.battery_measure_crank)) }
            Result(stringResource(R.string.battery_cranking), crank, r.cranking)
            SectionTitle(stringResource(R.string.battery_step3))
            Button(enabled = busy == null, onClick = { scope.launch { busy = measuring; charging = average(3); busy = null } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.battery_measure_charge)) }
            Result(stringResource(R.string.battery_charging), charging, r.charging)
            Text(stringResource(R.string.battery_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Code lookup

@Composable
fun DtcSearchScreen(vm: MainViewModel, onBack: () -> Unit, nav: NavController) {
    val lookup by vm.dtc.collectAsStateWithLifecycle()
    val hebrew = isHebrewUi()
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, hebrew, lookup) { lookup?.search(query, hebrew) ?: emptyList() }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.tool_dtc_search), onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dtc_search_hint)) }, singleLine = true) }
            if (query.trim().length < 2) item { Text(stringResource(R.string.dtc_search_intro, lookup?.size ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (results.isEmpty()) item {
                Text(stringResource(R.string.dtc_search_empty))
                // A well-formed code that is not in the database can still be opened (manufacturer codes).
                val code = query.trim().uppercase()
                if (Regex("^[PCBU][0-9A-F]{4}$").matches(code)) TextButton(onClick = { nav.navigate(Routes.code(code)) }) { LtrText(code) }
            }
            items(results, key = { it.code }) { info ->
                InfoCard(onClick = { nav.navigate(Routes.code(info.code)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LtrText(info.code, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(info.description.orEmpty(), Modifier.weight(1f))
                        SeverityChip(info.severity)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Maintenance log

@Composable
fun MaintenanceScreen(vm: MainViewModel, onBack: () -> Unit, evm: ExtrasViewModel = hiltViewModel()) {
    val list by evm.maintenance.collectAsStateWithLifecycle()
    val live by vm.obd.live.collectAsStateWithLifecycle()
    val vehicle by vm.obd.vehicle.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val locale = currentLocale()
    var adding by remember { mutableStateOf(false) }
    val odo = live["odometer"]?.value
    DisposableEffect(Unit) {
        vm.obd.setDemand("maint", listOf("odometer"))
        onDispose { vm.obd.setDemand("maint", emptyList()) }
    }
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.tool_maintenance), onBack) { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.maint_add)) } }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) item { Text(stringResource(R.string.maint_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.id }) { m ->
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.title, style = MaterialTheme.typography.titleMedium)
                            Text(formatDateTime(m.date, locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            m.odometerKm?.let { LtrText("${Units.format(it, 0)} km", style = MaterialTheme.typography.bodyMedium) }
                            m.cost?.let { LtrText("${Units.format(it, 0)} ${settings?.currency.orEmpty()}", style = MaterialTheme.typography.bodyMedium) }
                            if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodyMedium)
                            m.remindAtKm?.let { at ->
                                val due = odo != null && odo >= at
                                Text(if (due) stringResource(R.string.maint_due) else stringResource(R.string.maint_remind_at, Bidi.ltr("${Units.format(at, 0)} km")),
                                    color = if (due) Danger else MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        IconButton(onClick = { evm.deleteMaintenance(m.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
            item { Button(onClick = { adding = true }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.maint_add)) } }
        }
    }
    if (adding) {
        var title by remember { mutableStateOf("") }
        var km by remember { mutableStateOf(odo?.let { num(it, 0) } ?: "") }
        var cost by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var remind by remember { mutableStateOf("") }
        val numKb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.maint_add)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.maint_title)) }, singleLine = true)
                    OutlinedTextField(km, { km = it }, label = { Text(stringResource(R.string.maint_odometer)) }, singleLine = true, keyboardOptions = numKb)
                    OutlinedTextField(cost, { cost = it }, label = { Text(stringResource(R.string.maint_cost)) }, singleLine = true, keyboardOptions = numKb)
                    OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.maint_note)) })
                    OutlinedTextField(remind, { remind = it }, label = { Text(stringResource(R.string.maint_remind)) }, singleLine = true, keyboardOptions = numKb)
                }
            },
            confirmButton = {
                Button(enabled = title.isNotBlank(), onClick = {
                    evm.addMaintenance(MaintenanceEntity(vehicleId = vehicle?.id, title = title.trim(), date = System.currentTimeMillis(),
                        odometerKm = km.toDoubleOrNull(), cost = cost.toDoubleOrNull(), note = note.trim(), remindAtKm = remind.toDoubleOrNull()))
                    adding = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
