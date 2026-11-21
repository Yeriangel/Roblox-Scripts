package com.diagnosepro.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.diagnosepro.app.R
import com.diagnosepro.app.domain.Units
import com.diagnosepro.app.obd.Pids
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.ArcGauge
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.ValueTile
import com.diagnosepro.app.ui.formatPid
import com.diagnosepro.app.ui.isRtl
import com.diagnosepro.app.ui.nav.Routes
import com.diagnosepro.app.ui.pidName
import com.diagnosepro.app.ui.theme.Danger

private val BIG_GAUGES = setOf("rpm", "speed")

@Composable
fun DashboardScreen(vm: MainViewModel, nav: NavController) {
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val live by vm.obd.live.collectAsStateWithLifecycle()
    val supported by vm.obd.supportedPids.collectAsStateWithLifecycle()
    val vehicle by vm.obd.vehicle.collectAsStateWithLifecycle()
    val logging by vm.obd.logging.collectAsStateWithLifecycle()
    val responding by vm.obd.vehicleResponding.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val units = settings?.units ?: return
    val rtl = isRtl()

    val chosen = vehicle?.gauges?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: Pids.defaultDashboard
    val visible = chosen.filter { k -> Pids.byKey(k)?.let { it.key == Pids.KEY_ADAPTER_VOLTAGE || it.pid in supported } == true }
    val hidden = if (state.isConnected) chosen.size - visible.size else 0

    DisposableEffect(visible) {
        vm.obd.setDemand("dashboard", visible)
        onDispose { vm.obd.setDemand("dashboard", emptyList()) }
    }

    if (!state.isConnected) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            item { NotConnectedState(stringResource(R.string.dash_not_connected_text)) { nav.navigate(Routes.CONNECT) } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = { nav.navigate(Routes.TOOLS) }) { Icon(Icons.Filled.Build, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dash_tools)) }
                }
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vehicle_label, vehicle?.name ?: "—"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    IconButton(onClick = { nav.navigate(Routes.GAUGES) }) { Icon(Icons.Filled.Tune, stringResource(R.string.dash_customize)) }
                    IconButton(onClick = { nav.navigate(Routes.TOOLS) }) { Icon(Icons.Filled.Build, stringResource(R.string.dash_tools)) }
                }
                val log = logging
                if (log == null) {
                    OutlinedButton(onClick = { vm.startLogging() }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.FiberManualRecord, null, tint = Danger); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.logging_start))
                    }
                } else {
                    Button(onClick = { vm.stopLogging() }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                        Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.logging_stop) + " · " + stringResource(R.string.logging_active, Units.formatDuration((System.currentTimeMillis() - log.startedAt) / 1000)))
                    }
                }
                if (!responding) { Spacer(Modifier.padding(4.dp)); Banner(stringResource(R.string.vehicle_not_responding)) }
                if (hidden > 0) { Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.dash_unsupported_hidden, hidden), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (visible.isEmpty()) Text(stringResource(R.string.dash_no_gauges))
            }
        }
        items(visible, key = { it }) { key ->
            val def = Pids.byKey(key)!!
            val v = live[key]?.value
            val (num, unit) = if (v != null) formatPid(def, v, units) else "—" to Units.unitLabel(def.quantity, units)
            val warn = v != null && def.isWarning(v)
            if (key in BIG_GAUGES) {
                val frac = if (v == null) 0f else ((v - def.gaugeMin) / (def.gaugeMax - def.gaugeMin)).toFloat()
                ArcGauge(pidName(key), num, unit, frac, warn, rtl)
            } else {
                ValueTile(pidName(key), num, unit, warn)
            }
        }
        item(span = { GridItemSpan(2) }) { Spacer(Modifier.padding(8.dp)) }
    }
}

@Composable
fun GaugeConfigScreen(vm: MainViewModel, onBack: () -> Unit) {
    val vehicle by vm.obd.vehicle.collectAsStateWithLifecycle()
    val supported by vm.obd.supportedPids.collectAsStateWithLifecycle()
    val initial = vehicle?.gauges?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: Pids.defaultDashboard
    val allKeys = (Pids.all.map { it.key } + Pids.KEY_ADAPTER_VOLTAGE)
    // Ordered list: selected first (in saved order), then the rest.
    val order = remember(vehicle?.id) { mutableStateListOf<String>().apply { addAll(initial + allKeys.filterNot { it in initial }) } }
    val selected = remember(vehicle?.id) { mutableStateListOf<String>().apply { addAll(initial) } }

    fun save() = vm.setGauges(order.filter { it in selected })

    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.gauges_title), { save(); onBack() }) {
            TextButton(onClick = { selected.clear(); selected.addAll(Pids.defaultDashboard); order.clear(); order.addAll(Pids.defaultDashboard + allKeys.filterNot { it in Pids.defaultDashboard }) }) {
                Text(stringResource(R.string.gauges_reset))
            }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item { Text(stringResource(R.string.gauges_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            itemsIndexed(order, key = { _, k -> k }) { idx, key ->
                val def = Pids.byKey(key)!!
                val isSupported = supported.isEmpty() || def.key == Pids.KEY_ADAPTER_VOLTAGE || def.pid in supported
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = key in selected, onCheckedChange = { if (it) selected.add(key) else selected.remove(key); save() })
                    Column(Modifier.weight(1f)) {
                        Text(pidName(key), style = MaterialTheme.typography.bodyLarge)
                        if (!isSupported) Text(stringResource(R.string.not_supported_by_car), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { if (idx > 0) { order.add(idx - 1, order.removeAt(idx)); save() } }, enabled = idx > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up))
                    }
                    IconButton(onClick = { if (idx < order.lastIndex) { order.add(idx + 1, order.removeAt(idx)); save() } }, enabled = idx < order.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down))
                    }
                }
            }
        }
    }
}
