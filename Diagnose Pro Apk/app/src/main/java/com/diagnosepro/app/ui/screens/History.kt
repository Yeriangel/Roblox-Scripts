package com.diagnosepro.app.ui.screens

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.diagnosepro.app.R
import com.diagnosepro.app.data.DtcJson
import com.diagnosepro.app.domain.Units
import com.diagnosepro.app.i18n.Bidi
import com.diagnosepro.app.obd.Pids
import com.diagnosepro.app.obd.Quantity
import com.diagnosepro.app.ui.HistoryViewModel
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.EmptyState
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.KeyValueRow
import com.diagnosepro.app.ui.components.LineChart
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.components.SectionTitle
import com.diagnosepro.app.ui.currentLocale
import com.diagnosepro.app.ui.formatDateTime
import com.diagnosepro.app.ui.isHebrewUi
import com.diagnosepro.app.ui.nav.Routes
import com.diagnosepro.app.ui.pidName
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.Ok
import com.diagnosepro.app.ui.theme.Warn
import com.diagnosepro.app.util.Exporter

@Composable
fun HistoryScreen(vm: MainViewModel, nav: NavController, hvm: HistoryViewModel = hiltViewModel()) {
    val scans by hvm.scans.collectAsStateWithLifecycle()
    val sessions by hvm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val units = settings?.units ?: return
    val locale = currentLocale()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.hist_scans)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.hist_sessions)) })
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            if (tab == 0) {
                if (scans.isEmpty()) item { EmptyState(Icons.Filled.History, stringResource(R.string.hist_scans), stringResource(R.string.hist_empty_scans)) }
                items(scans, key = { it.id }) { s ->
                    val codes = DtcJson.decode(s.codesJson)
                    InfoCard(onClick = { nav.navigate(Routes.scan(s.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDateTime(s.timestamp, locale), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(if (s.milOn) R.string.codes_mil_on else R.string.codes_mil_off), color = if (s.milOn) Danger else Ok, style = MaterialTheme.typography.labelMedium)
                        }
                        if (s.beforeClear) Text(stringResource(R.string.hist_before_clear), color = Warn, style = MaterialTheme.typography.labelMedium)
                        s.mileageKm?.let { Text(stringResource(R.string.hist_mileage, Bidi.value(Units.format(Units.convert(Quantity.DISTANCE, it, units), 0), Units.unitLabel(Quantity.DISTANCE, units))), style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(6.dp))
                        if (codes.isEmpty()) Text(stringResource(R.string.hist_no_codes), color = Ok)
                        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            codes.distinctBy { it.code }.forEach { d -> AssistChip(onClick = { nav.navigate(Routes.scan(s.id)) }, label = { LtrText(d.code, style = MaterialTheme.typography.labelLarge) }) }
                        }
                    }
                }
            } else {
                if (sessions.isEmpty()) item { EmptyState(Icons.Filled.Route, stringResource(R.string.hist_sessions), stringResource(R.string.hist_empty_sessions)) }
                items(sessions, key = { it.id }) { s ->
                    InfoCard(onClick = { nav.navigate(Routes.session(s.id)) }) {
                        Text(formatDateTime(s.startTime, locale), style = MaterialTheme.typography.titleSmall)
                        if (s.endTime == null) Text(stringResource(R.string.session_in_progress), color = Warn, style = MaterialTheme.typography.labelMedium)
                        else LtrText(
                            "${Units.formatDuration(s.durationS)} · ${Units.format(Units.convert(Quantity.DISTANCE, s.distanceKm, units), 1)} ${Units.unitLabel(Quantity.DISTANCE, units)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun ScanDetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit, nav: NavController, hvm: HistoryViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(id) { hvm.loadScan(id) }
    val scan by hvm.scan.collectAsStateWithLifecycle()
    val scans by hvm.scans.collectAsStateWithLifecycle()
    val lookup by vm.dtc.collectAsStateWithLifecycle()
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val hebrew = isHebrewUi()
    val locale = currentLocale()
    var confirmDelete by remember { mutableStateOf(false) }
    val s = scan
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.scan_title), onBack) {
            if (s != null && lookup != null) IconButton(onClick = {
                val text = buildReportText(ctx, DtcJson.decode(s.codesJson), s.milOn, vehicles.firstOrNull { it.id == s.vehicleId }?.name, s.timestamp, s.mileageKm, lookup!!, hebrew, locale)
                Exporter.sharePdf(ctx, "diagnose-pro-scan-${s.id}.pdf", text, hebrew, ctx.getString(R.string.report_title))
            }) { Icon(Icons.Filled.Share, stringResource(R.string.share_as_pdf)) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        }
    }) { pad ->
        if (s == null) return@Scaffold
        val codes = DtcJson.decode(s.codesJson)
        // Compare with the previous scan of the same vehicle.
        val previous = scans.filter { it.vehicleId == s.vehicleId && it.timestamp < s.timestamp }.maxByOrNull { it.timestamp }
        val prevCodes = previous?.let { DtcJson.decode(it.codesJson).map { d -> d.code }.toSet() }
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(formatDateTime(s.timestamp, locale), style = MaterialTheme.typography.titleMedium)
                vehicles.firstOrNull { it.id == s.vehicleId }?.let { Text(stringResource(R.string.vehicle_label, it.name)) }
                Text(stringResource(if (s.milOn) R.string.codes_mil_on else R.string.codes_mil_off), color = if (s.milOn) Danger else Ok)
                Text(pluralStringResource(R.plurals.codes_found, codes.size, codes.size))
                if (s.beforeClear) Text(stringResource(R.string.hist_before_clear), color = Warn)
            }
            if (prevCodes != null) {
                val now = codes.map { it.code }.toSet()
                val added = now - prevCodes; val gone = prevCodes - now
                if (added.isNotEmpty() || gone.isNotEmpty()) item {
                    InfoCard {
                        if (added.isNotEmpty()) LtrText("+ " + added.joinToString("  "), color = Danger, style = MaterialTheme.typography.titleMedium)
                        if (gone.isNotEmpty()) LtrText("− " + gone.joinToString("  "), color = Ok, style = MaterialTheme.typography.titleMedium)
                        Text(formatDateTime(previous.timestamp, locale), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(codes, key = { "${it.kind}-${it.ecu}-${it.code}" }) { d ->
                DtcRow(d, lookup, hebrew) { nav.navigate(Routes.code(d.code, d.ecu, d.kind.name)) }
            }
        }
    }
    if (confirmDelete) DeleteDialog({ confirmDelete = false }) { hvm.deleteScan(id); confirmDelete = false; onBack() }
}

@Composable
private fun DeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirm)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SessionDetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit, hvm: HistoryViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(id) { hvm.loadSession(id) }
    val session by hvm.session.collectAsStateWithLifecycle()
    val samples by hvm.samples.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val units = settings?.units ?: return
    val locale = currentLocale()
    var confirmDelete by remember { mutableStateOf(false) }
    val byPid = remember(samples) { samples.groupBy { it.pid }.mapValues { e -> e.value.map { it.t to it.value } } }
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.session_title), onBack) {
            IconButton(onClick = { Exporter.shareCsv(ctx, "trip-$id.csv", buildCsv(ctx, byPid, units), ctx.getString(R.string.export_csv)) }, enabled = samples.isNotEmpty()) {
                Icon(Icons.Filled.FileDownload, stringResource(R.string.export_csv))
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        }
    }) { pad ->
        val s = session ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(formatDateTime(s.startTime, locale), style = MaterialTheme.typography.titleMedium)
                InfoCard {
                    KeyValueRow(stringResource(R.string.session_duration), Units.formatDuration(s.durationS))
                    KeyValueRow(stringResource(R.string.session_distance), "${Units.format(Units.convert(Quantity.DISTANCE, s.distanceKm, units), 1)} ${Units.unitLabel(Quantity.DISTANCE, units)}")
                    s.maxSpeed?.let { KeyValueRow(stringResource(R.string.session_max_speed), "${Units.format(Units.convert(Quantity.SPEED, it, units), 0)} ${Units.unitLabel(Quantity.SPEED, units)}") }
                    s.avgRpm?.let { KeyValueRow(stringResource(R.string.session_avg_rpm), "${Units.format(it, 0)} rpm") }
                    s.maxCoolant?.let { KeyValueRow(stringResource(R.string.session_max_coolant), "${Units.format(Units.convert(Quantity.TEMPERATURE, it, units), 0)} ${Units.unitLabel(Quantity.TEMPERATURE, units)}") }
                }
            }
            if (samples.isEmpty()) item { Text(stringResource(R.string.session_no_samples)) }
            val keys = byPid.keys.toList()
            items(keys) { key ->
                val def = Pids.byKey(key) ?: return@items
                val pts = byPid.getValue(key).map { it.first to Units.convert(def.quantity, it.second, units) }
                val start = pts.first().first; val end = pts.last().first
                SectionTitle(pidName(key) + " (" + Units.unitLabel(def.quantity, units) + ")")
                LineChart(pts, (end - start).coerceAtLeast(1000), end, SeriesColors[keys.indexOf(key) % SeriesColors.size], Modifier.fillMaxWidth().height(140.dp))
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (confirmDelete) DeleteDialog({ confirmDelete = false }) { hvm.deleteSession(id); confirmDelete = false; onBack() }
}
