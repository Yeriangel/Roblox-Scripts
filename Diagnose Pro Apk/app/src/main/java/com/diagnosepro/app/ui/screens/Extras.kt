package com.diagnosepro.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diagnosepro.app.R
import com.diagnosepro.app.domain.FuelEstimator
import com.diagnosepro.app.domain.Units
import com.diagnosepro.app.elm327.RawLogger
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.KeyValueRow
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.theme.Accent
import com.diagnosepro.app.ui.theme.Ok
import com.diagnosepro.app.util.Exporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FuelScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val live by vm.obd.live.collectAsStateWithLifecycle()
    val supported by vm.obd.supportedPids.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val units = settings?.units ?: return
    DisposableEffect(Unit) {
        vm.obd.setDemand("fuel", listOf("maf", "speed"))
        onDispose { vm.obd.setDemand("fuel", emptyList()) }
    }
    // Running average: integrate fuel and distance between samples.
    var fuelL by remember { mutableDoubleStateOf(0.0) }
    var distKm by remember { mutableDoubleStateOf(0.0) }
    var lastT by remember { mutableLongStateOf(0L) }
    val maf = live["maf"]; val speed = live["speed"]
    LaunchedEffect(maf?.time) {
        if (maf == null || speed == null) return@LaunchedEffect
        if (lastT != 0L) {
            val dtH = (maf.time - lastT) / 3_600_000.0
            if (dtH in 0.0..(10.0 / 3600)) {
                fuelL += FuelEstimator.litersPerHour(maf.value) * dtH
                distKm += speed.value * dtH
            }
        }
        lastT = maf.time
    }
    val label = FuelEstimator.label(units.economy)
    Scaffold(topBar = { BackTopBar(stringResource(R.string.tool_fuel), onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banner(stringResource(R.string.fuel_estimate_note))
            if (!state.isConnected) { Banner(stringResource(R.string.codes_need_connection)); return@Column }
            if (supported.isNotEmpty() && 0x10 !in supported) { Banner(stringResource(R.string.fuel_need_maf)); return@Column }
            val instant = if (maf != null && speed != null) FuelEstimator.litersPer100Km(maf.value, speed.value) else null
            InfoCard {
                Text(stringResource(R.string.fuel_instant), style = MaterialTheme.typography.labelLarge)
                LtrText(instant?.let { "${Units.format(FuelEstimator.toUnit(it, units.economy), 1)} $label" } ?: stringResource(R.string.fuel_stopped),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp), color = Accent)
                maf?.let { KeyValueRow(stringResource(R.string.fuel_rate_label), "${Units.format(FuelEstimator.litersPerHour(it.value), 2)} L/h") }
            }
            InfoCard {
                Text(stringResource(R.string.fuel_average), style = MaterialTheme.typography.labelLarge)
                val avg = if (distKm > 0.05) fuelL / distKm * 100 else null
                LtrText(avg?.let { "${Units.format(FuelEstimator.toUnit(it, units.economy), 1)} $label" } ?: "—", style = MaterialTheme.typography.headlineMedium)
                LtrText("${Units.format(fuelL, 2)} L · ${Units.format(distKm, 1)} km", style = MaterialTheme.typography.bodyMedium)
                val price = settings?.fuelPrice ?: 0.0
                if (price > 0) {
                    val cur = settings?.currency.orEmpty()
                    KeyValueRow(stringResource(R.string.fuel_cost), "${Units.format(fuelL * price, 2)} $cur")
                    avg?.let { KeyValueRow(stringResource(R.string.fuel_cost_100), "${Units.format(it * price, 2)} $cur") }
                }
                OutlinedButton(onClick = { fuelL = 0.0; distKm = 0.0; lastT = 0L }) { Text(stringResource(R.string.fuel_reset)) }
            }
        }
    }
}

@Composable
fun ConsoleScreen(vm: MainViewModel, onBack: () -> Unit, evm: com.diagnosepro.app.ui.ExtrasViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    var command by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lines by vm.rawLog.lines.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.tool_console), onBack) {
            IconButton(onClick = { clipboard.setText(AnnotatedString(vm.rawLog.asText())) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) }
            IconButton(onClick = { Exporter.shareText(ctx, ctx.getString(R.string.tool_console), vm.rawLog.asText()) }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
            IconButton(onClick = { vm.rawLog.clear() }) { Icon(Icons.Filled.DeleteSweep, stringResource(R.string.console_clear)) }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
        // Terminal: safe adapter commands and read-only OBD services only (see TerminalGuard).
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(command, { command = it; blocked = false }, Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.terminal_hint)) }, isError = blocked)
            Spacer(Modifier.padding(4.dp))
            Button(enabled = command.isNotBlank(), onClick = {
                if (com.diagnosepro.app.obd.TerminalGuard.isAllowed(command)) { evm.send(command); command = "" } else blocked = true
            }) { Text(stringResource(R.string.terminal_send)) }
        }
        if (blocked) Text(stringResource(R.string.terminal_blocked), Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (lines.isEmpty()) Text(stringResource(R.string.console_empty), Modifier.padding(16.dp))
        // Raw protocol text is always shown LTR, also in Hebrew.
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), reverseLayout = true) {
            items(lines.asReversed()) { l ->
                val color = when (l.direction) { RawLogger.Direction.TX -> Accent; RawLogger.Direction.RX -> MaterialTheme.colorScheme.onSurface; RawLogger.Direction.INFO -> Color(0xFF4FC3F7) }
                LtrText("${fmt.format(Date(l.time))} ${if (l.direction == RawLogger.Direction.TX) ">" else if (l.direction == RawLogger.Direction.RX) "<" else "#"} ${l.text}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = color, modifier = Modifier.fillMaxWidth())
            }
        }
        }
    }
}
