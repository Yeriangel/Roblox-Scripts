package com.diagnosepro.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.diagnosepro.app.R
import com.diagnosepro.app.data.dtc.DtcLookup
import com.diagnosepro.app.data.dtc.Severity
import com.diagnosepro.app.domain.ClearOutcome
import com.diagnosepro.app.i18n.Bidi
import com.diagnosepro.app.obd.Dtc
import com.diagnosepro.app.obd.DtcKind
import com.diagnosepro.app.ui.CodesViewModel
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.EmptyState
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.components.SectionTitle
import com.diagnosepro.app.ui.dtcKindRes
import com.diagnosepro.app.ui.ecuLabel
import com.diagnosepro.app.ui.formatDateTime
import com.diagnosepro.app.ui.isHebrewUi
import com.diagnosepro.app.ui.nav.Routes
import com.diagnosepro.app.ui.currentLocale
import com.diagnosepro.app.ui.severityDescRes
import com.diagnosepro.app.ui.severityRes
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.Ok
import com.diagnosepro.app.ui.theme.Warn
import com.diagnosepro.app.util.Exporter
import java.util.Locale

fun severityColor(s: Severity): Color = when (s) {
    Severity.LOW -> Color(0xFF4FC3F7); Severity.MEDIUM -> Warn; Severity.HIGH -> Color(0xFFFF8A3D); Severity.STOP -> Danger
}

@Composable
fun CodesScreen(vm: MainViewModel, nav: NavController, cvm: CodesViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val report by cvm.report.collectAsStateWithLifecycle()
    val busy by cvm.busy.collectAsStateWithLifecycle()
    val clearing by cvm.clearing.collectAsStateWithLifecycle()
    val outcome by cvm.clearOutcome.collectAsStateWithLifecycle()
    val lookup by vm.dtc.collectAsStateWithLifecycle()
    val vehicle by vm.obd.vehicle.collectAsStateWithLifecycle()
    val hebrew = isHebrewUi()
    val locale = currentLocale()
    var clearStep by remember { mutableIntStateOf(0) } // 0 none, 1 info, 2 final
    var shareMenu by remember { mutableStateOf(false) }

    if (!state.isConnected) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            NotConnectedState(stringResource(R.string.codes_need_connection)) { nav.navigate(Routes.CONNECT) }
            Banner(stringResource(R.string.limits_note), color = MaterialTheme.colorScheme.secondary)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(4.dp)); DrivingWarning(vm) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { cvm.scan() }, enabled = !busy && !clearing, modifier = Modifier.weight(1f).height(52.dp)) {
                    if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                    Text(stringResource(if (busy) R.string.codes_reading else R.string.codes_read))
                }
                OutlinedButton(onClick = { clearStep = 1 }, enabled = !busy && !clearing, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Filled.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.codes_clear))
                }
                val r = report
                if (r != null && lookup != null) Box {
                    IconButton(onClick = { shareMenu = true }) { Icon(Icons.Filled.Share, stringResource(R.string.codes_share_report)) }
                    DropdownMenu(shareMenu, { shareMenu = false }) {
                        val text = { buildReportText(ctx, r.all, r.milOn, vehicle?.name, System.currentTimeMillis(), null, lookup!!, hebrew, locale) }
                        DropdownMenuItem({ Text(stringResource(R.string.share_as_text)) }, onClick = {
                            shareMenu = false; Exporter.shareText(ctx, ctx.getString(R.string.report_title), text())
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.share_as_pdf)) }, onClick = {
                            shareMenu = false; Exporter.sharePdf(ctx, "diagnose-pro-report.pdf", text(), hebrew, ctx.getString(R.string.report_title))
                        })
                    }
                }
            }
        }
        val r = report
        if (r == null) {
            item { EmptyState(Icons.Filled.Search, stringResource(R.string.codes_not_read_title), stringResource(R.string.codes_not_read_text)) }
        } else {
            item { MilCard(r.milOn, r.stored.size + r.pending.size + r.permanent.size) }
            r.errors.forEach { (kind, err) ->
                item { Banner(stringResource(R.string.codes_kind_failed, stringResource(dtcKindRes(kind)), Bidi.ltr(err.name))) }
            }
            if (r.all.isEmpty()) {
                item { EmptyState(Icons.Filled.CheckCircle, stringResource(R.string.codes_none_title), stringResource(R.string.codes_none_text)) }
            }
            for (kind in DtcKind.entries) {
                val list = r.all.filter { it.kind == kind }
                if (list.isEmpty()) continue
                item {
                    SectionTitle(stringResource(dtcKindRes(kind)))
                    Text(stringResource(when (kind) { DtcKind.STORED -> R.string.codes_stored_desc; DtcKind.PENDING -> R.string.codes_pending_desc; DtcKind.PERMANENT -> R.string.codes_permanent_desc }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(list, key = { "${it.kind}-${it.ecu}-${it.code}" }) { d ->
                    DtcRow(d, lookup, hebrew) { nav.navigate(Routes.code(d.code, d.ecu, d.kind.name)) }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)); Banner(stringResource(R.string.limits_note), color = MaterialTheme.colorScheme.secondary); Spacer(Modifier.height(16.dp)) }
    }

    // ----- Clear codes: two confirmations -----
    if (clearStep == 1) {
        AlertDialog(
            onDismissRequest = { clearStep = 0 },
            icon = { Icon(Icons.Filled.Warning, null, tint = Warn) },
            title = { Text(stringResource(R.string.clear_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.clear_text_engine))
                    Text(stringResource(R.string.clear_text_not_fix))
                    Text(stringResource(R.string.clear_text_monitors))
                    Text(stringResource(R.string.clear_text_saved), fontWeight = FontWeight.Medium)
                }
            },
            confirmButton = { Button(onClick = { clearStep = 2 }) { Text(stringResource(R.string.clear_continue)) } },
            dismissButton = { TextButton(onClick = { clearStep = 0 }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (clearStep == 2) {
        AlertDialog(
            onDismissRequest = { clearStep = 0 },
            title = { Text(stringResource(R.string.clear_confirm_title)) },
            text = { Text(stringResource(R.string.clear_confirm_text)) },
            confirmButton = {
                Button(onClick = { clearStep = 0; cvm.clear() }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                    Text(stringResource(R.string.clear_confirm_button))
                }
            },
            dismissButton = { TextButton(onClick = { clearStep = 0 }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (clearing) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(stringResource(R.string.clear_in_progress)) },
            text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } })
    }
    outcome?.let { o ->
        val (color, msg) = when (o) {
            is ClearOutcome.Cleared -> Ok to stringResource(R.string.clear_success)
            is ClearOutcome.CameBack -> Warn to stringResource(R.string.clear_came_back)
            is ClearOutcome.Failed -> Danger to stringResource(R.string.clear_failed, Bidi.ltr(o.error?.name ?: "?"))
        }
        val after = (o as? ClearOutcome.Cleared)?.after ?: (o as? ClearOutcome.CameBack)?.after
        AlertDialog(
            onDismissRequest = { cvm.dismissClearResult() },
            icon = { Icon(if (o is ClearOutcome.Cleared) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = color) },
            title = { Text(msg) },
            text = { if (after != null && after.permanent.isNotEmpty()) Text(stringResource(R.string.clear_permanent_remain)) },
            confirmButton = { Button(onClick = { cvm.dismissClearResult() }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun MilCard(milOn: Boolean, count: Int) {
    Surface(color = (if (milOn) Danger else Ok).copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (milOn) Icons.Filled.Warning else Icons.Filled.CheckCircle, null, tint = if (milOn) Danger else Ok, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(if (milOn) R.string.codes_mil_on else R.string.codes_mil_off), style = MaterialTheme.typography.titleMedium)
                Text(pluralStringResource(R.plurals.codes_found, count, count), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun DtcRow(d: Dtc, lookup: DtcLookup?, hebrew: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val info = lookup?.lookup(d.code, hebrew)
    InfoCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LtrText(d.code, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info?.description ?: if (info?.manufacturerSpecific == true) stringResource(R.string.detail_manufacturer) else info?.groupDescription ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(ecuLabel(ctx, d.ecu), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            info?.let { SeverityChip(it.severity) }
        }
    }
}

@Composable
fun SeverityChip(s: Severity) {
    Surface(color = severityColor(s).copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
        Text(stringResource(severityRes(s)), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = severityColor(s), style = MaterialTheme.typography.labelMedium)
    }
}

/** Plain-text report, used for "share as text" and rendered into the PDF. */
fun buildReportText(
    ctx: Context, codes: List<Dtc>, milOn: Boolean, vehicleName: String?, time: Long, mileageKm: Double?,
    lookup: DtcLookup, hebrew: Boolean, locale: Locale,
): String = buildString {
    appendLine(ctx.getString(R.string.report_title))
    appendLine()
    appendLine("${ctx.getString(R.string.report_date)}: ${formatDateTime(time, locale)}")
    vehicleName?.let { appendLine("${ctx.getString(R.string.report_vehicle)}: ${Bidi.ltr(it)}") }
    mileageKm?.let { appendLine(ctx.getString(R.string.hist_mileage, Bidi.ltr(String.format(Locale.US, "%,.0f km", it)))) }
    appendLine(ctx.getString(if (milOn) R.string.codes_mil_on else R.string.codes_mil_off))
    appendLine(ctx.resources.getQuantityString(R.plurals.codes_found, codes.size, codes.size))
    appendLine()
    for (kind in DtcKind.entries) {
        val list = codes.filter { it.kind == kind }
        if (list.isEmpty()) continue
        appendLine("— ${ctx.getString(dtcKindRes(kind))} —")
        for (d in list) {
            val info = lookup.lookup(d.code, hebrew)
            val desc = info.description ?: if (info.manufacturerSpecific) ctx.getString(R.string.detail_manufacturer) else info.groupDescription.orEmpty()
            appendLine("${Bidi.ltr(d.code)}  $desc")
            appendLine("    ${ecuLabel(ctx, d.ecu)} · ${ctx.getString(R.string.detail_severity)}: ${ctx.getString(severityRes(info.severity))}")
        }
        appendLine()
    }
    appendLine(ctx.getString(R.string.limits_note))
    appendLine(ctx.getString(R.string.report_generated))
}

@Composable
fun CodeDetailScreen(vm: MainViewModel, code: String, ecu: String, kind: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lookup by vm.dtc.collectAsStateWithLifecycle()
    val hebrew = isHebrewUi()
    val info = lookup?.lookup(code, hebrew)
    Scaffold(topBar = { BackTopBar(code, onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DrivingWarning(vm)
            LtrText(code, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            if (info == null) { CircularProgressIndicator(); return@Column }
            when {
                info.description != null -> Text(info.description, style = MaterialTheme.typography.headlineMedium)
                info.manufacturerSpecific -> {
                    Text(stringResource(R.string.detail_manufacturer), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.detail_manufacturer_text))
                }
                else -> {
                    Text(stringResource(R.string.detail_unknown_generic))
                    info.groupDescription?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                }
            }
            if (ecu.isNotEmpty()) Text(stringResource(R.string.detail_reported_by, ecuLabel(ctx, ecu)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            runCatching { DtcKind.valueOf(kind) }.getOrNull()?.let {
                Text(stringResource(R.string.detail_kind, stringResource(dtcKindRes(it))), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(color = severityColor(info.severity).copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.detail_severity), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(severityRes(info.severity)), style = MaterialTheme.typography.titleLarge, color = severityColor(info.severity))
                    Text(stringResource(severityDescRes(info.severity)))
                }
            }
            if (info.causes.isNotEmpty()) {
                SectionTitle(stringResource(R.string.detail_causes))
                info.causes.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            }
            info.tip?.let {
                SectionTitle(stringResource(R.string.detail_tip))
                InfoCard { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            if (info.causes.isEmpty() && info.tip == null) Text(stringResource(R.string.detail_no_details), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                val q = Uri.encode("$code OBD2 ${if (hebrew) "קוד תקלה" else "code"}")
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))) }
            }, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Search, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.search_web))
            }
            Banner(stringResource(R.string.limits_note), color = MaterialTheme.colorScheme.secondary)
        }
    }
}
