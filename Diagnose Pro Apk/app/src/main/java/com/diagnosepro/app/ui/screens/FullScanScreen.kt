package com.diagnosepro.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.diagnosepro.app.R
import com.diagnosepro.app.i18n.Bidi
import com.diagnosepro.app.obd.ModuleKind
import com.diagnosepro.app.ui.ExtrasViewModel
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.KeyValueRow
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.isHebrewUi
import com.diagnosepro.app.ui.nav.Routes
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.Ok
import com.diagnosepro.app.ui.theme.Warn

private fun kindName(k: ModuleKind): Int = when (k) {
    ModuleKind.ENGINE -> R.string.mod_engine
    ModuleKind.TRANSMISSION -> R.string.mod_transmission
    ModuleKind.ABS -> R.string.mod_abs
    ModuleKind.STEERING -> R.string.mod_steering
    ModuleKind.SUSPENSION -> R.string.mod_suspension
    ModuleKind.AIRBAG -> R.string.mod_airbag
    ModuleKind.CLUSTER -> R.string.mod_cluster
    ModuleKind.BODY -> R.string.mod_body
    ModuleKind.HVAC -> R.string.mod_hvac
    ModuleKind.GATEWAY -> R.string.mod_gateway
    ModuleKind.LIGHTING -> R.string.mod_lighting
    ModuleKind.ENTERTAINMENT -> R.string.mod_entertainment
    ModuleKind.SECURITY -> R.string.mod_security
    ModuleKind.UNKNOWN -> R.string.mod_unknown
}

private fun didName(did: Int): Int = when (did) {
    0xF197 -> R.string.did_system
    0xF187 -> R.string.did_part
    0xF18C -> R.string.did_serial
    0xF195, 0xF189 -> R.string.did_software
    0xF191 -> R.string.did_hardware
    else -> R.string.vi_vin
}

/** Scans every reachable control module (engine, ABS, airbag, body...) for fault codes. */
@Composable
fun FullScanScreen(vm: MainViewModel, onBack: () -> Unit, nav: NavController, evm: ExtrasViewModel = hiltViewModel()) {
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val modules by evm.modules.collectAsStateWithLifecycle()
    val done by evm.scanDone.collectAsStateWithLifecycle()
    val progress by evm.scanProgress.collectAsStateWithLifecycle()
    val lookup by vm.dtc.collectAsStateWithLifecycle()
    val info by evm.moduleInfo.collectAsStateWithLifecycle()
    val infoBusy by evm.moduleInfoBusy.collectAsStateWithLifecycle()
    val hebrew = isHebrewUi()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.tool_fullscan), onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DrivingWarning(vm) }
            item { Banner(stringResource(R.string.fs_note), color = MaterialTheme.colorScheme.secondary) }
            if (!state.isConnected) item { Banner(stringResource(R.string.codes_need_connection)) }
            else item {
                Button(onClick = { evm.scanModules() }, enabled = progress == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(if (progress == null) R.string.fs_run else R.string.fs_running))
                }
                progress?.let { (d, t) -> Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { d.toFloat() / t.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth()) }
            }
            val list = modules
            if (done && progress == null) {
                if (list == null) item { Banner(stringResource(R.string.fs_not_can)) }
                else if (list.isEmpty()) item { Banner(stringResource(R.string.fs_none)) }
            }
            if (list != null) items(list, key = { it.requestId }) { m ->
                val faults = m.dtcs?.size ?: 0
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(kindName(m.kind)), style = MaterialTheme.typography.titleMedium, color = if (faults > 0) Danger else Ok)
                            LtrText(m.requestId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            m.dtcs == null -> Text(stringResource(R.string.fs_no_dtc_service), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            faults == 0 -> Text(stringResource(R.string.fs_normal), color = Ok, style = MaterialTheme.typography.titleMedium)
                            else -> LtrText(faults.toString(), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Danger)
                        }
                    }
                    val infoMap = info[m.requestId]
                    if (infoMap == null) {
                        TextButton(onClick = { evm.readModuleInfo(m.requestId) }, enabled = infoBusy == null && progress == null) {
                            Text(stringResource(if (infoBusy == m.requestId) R.string.loading else R.string.fs_details))
                        }
                    } else if (infoMap.isEmpty()) {
                        Text(stringResource(R.string.fs_no_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else infoMap.forEach { (did, value) -> KeyValueRow(stringResource(didName(did)), value) }
                    m.dtcs?.forEach { d ->
                        val info = lookup?.lookup(d.code, hebrew)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { nav.navigate(Routes.code(d.code)) }) {
                                LtrText("${d.code}-%02X".format(d.failureType), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            }
                            Text(info?.description ?: stringResource(R.string.detail_manufacturer), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (d.current) R.string.fs_current else R.string.codes_pending), color = if (d.current) Danger else Warn, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.fs_limits, Bidi.ltr("ELM327")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(16.dp)) }
        }
    }
}
