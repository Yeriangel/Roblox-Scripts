package com.diagnosepro.app.ui.screens

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diagnosepro.app.R
import com.diagnosepro.app.domain.UnitPrefs
import com.diagnosepro.app.domain.Units
import com.diagnosepro.app.obd.Pids
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.LineChart
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.formatPid
import com.diagnosepro.app.ui.pidName
import com.diagnosepro.app.ui.pidLabel
import com.diagnosepro.app.util.Exporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val SeriesColors = listOf(Color(0xFFFF6D00), Color(0xFF4FC3F7), Color(0xFF2EC27E), Color(0xFFE879F9))
private val WINDOWS_S = listOf(15, 30, 60, 120, 300, 600)

@Composable
fun GraphScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val supported by vm.obd.supportedPids.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Each poll cycle bumps the tick, which recomposes the charts with fresh history.
    val tick by vm.obd.tick.collectAsStateWithLifecycle()
    val s = settings ?: return
    val selected = s.graphPids
    var paused by remember { mutableStateOf(false) }
    var pausedAt by remember { mutableLongStateOf(0L) }
    var windowIdx by remember { mutableIntStateOf(2) }

    DisposableEffect(selected) {
        vm.obd.setDemand("graph", selected)
        onDispose { vm.obd.setDemand("graph", emptyList()) }
    }

    if (!state.isConnected) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            NotConnectedState(stringResource(R.string.graph_need_connection)) { }
        }
        return
    }

    val all = Pids.allKeys().filter { k -> Pids.byKey(k)?.let { Pids.isAvailable(it, supported) } == true }
    val available = selected.filter { it in all } + all.filterNot { it in selected } // selected first
    val end = remember(tick, paused) { if (paused) pausedAt else System.currentTimeMillis() }
    val windowMs = WINDOWS_S[windowIdx] * 1000L

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.graph_select), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                available.forEach { key ->
                    val on = key in selected
                    FilterChip(selected = on, onClick = {
                        val next = if (on) selected - key else if (selected.size < 4) selected + key else selected
                        vm.setGraphPids(next)
                    }, label = { Text(pidName(key)) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!paused) pausedAt = System.currentTimeMillis(); paused = !paused }) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(if (paused) R.string.graph_resume else R.string.graph_pause))
                }
                IconButton(onClick = { if (windowIdx > 0) windowIdx-- }) { Icon(Icons.Filled.ZoomIn, stringResource(R.string.graph_zoom_in)) }
                IconButton(onClick = { if (windowIdx < WINDOWS_S.lastIndex) windowIdx++ }) { Icon(Icons.Filled.ZoomOut, stringResource(R.string.graph_zoom_out)) }
                Text(stringResource(R.string.graph_window, WINDOWS_S[windowIdx]), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = {
                    val csv = buildCsv(ctx, selected.associateWith { vm.obd.historyOf(it) }, s.units)
                    Exporter.shareCsv(ctx, "live-${System.currentTimeMillis()}.csv", csv, ctx.getString(R.string.export_csv))
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.FileDownload, stringResource(R.string.export_csv)) }
            }
        }
        if (selected.isEmpty()) item { Text(stringResource(R.string.graph_no_selection)) }
        items(selected, key = { it }) { key ->
            val def = Pids.byKey(key) ?: return@items
            val color = SeriesColors[selected.indexOf(key) % SeriesColors.size]
            val pts = vm.obd.historyOf(key).map { it.first to Units.convert(def.quantity, it.second, s.units) }
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pidName(key), Modifier.weight(1f), color = color, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    vm.obd.historyOf(key).lastOrNull()?.let { last ->
                        val (num, unit) = formatPid(def, last.second, s.units)
                        LtrText(if (unit.isEmpty()) num else "$num $unit", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                LineChart(pts, windowMs, end, color, Modifier.fillMaxWidth().height(150.dp))
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

/** CSV with one row per timestamp; columns use the selected units. */
fun buildCsv(ctx: Context, series: Map<String, List<Pair<Long, Double>>>, units: UnitPrefs): String {
    val keys = series.keys.toList()
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    val header = listOf("time") + keys.map { k ->
        val def = Pids.byKey(k)
        val u = def?.let { it.customUnit ?: Units.unitLabel(it.quantity, units) }.orEmpty()
        "\"" + pidLabel(ctx, k) + (if (u.isNotEmpty()) " ($u)" else "") + "\""
    }
    val times = series.values.flatten().map { it.first }.toSortedSet()
    val lookup = series.mapValues { (_, v) -> v.toMap() }
    val rows = times.map { t ->
        (listOf(fmt.format(Date(t))) + keys.map { k ->
            val def = Pids.byKey(k)
            lookup[k]?.get(t)?.let { v -> if (def != null) String.format(Locale.US, "%.3f", Units.convert(def.quantity, v, units)) else v.toString() } ?: ""
        }).joinToString(",")
    }
    return (listOf(header.joinToString(",")) + rows).joinToString("\n")
}
