package com.diagnosepro.app.ui.screens

import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diagnosepro.app.BuildConfig
import com.diagnosepro.app.R
import com.diagnosepro.app.domain.SelfTest
import com.diagnosepro.app.domain.TestStatus
import com.diagnosepro.app.domain.TestStepId
import com.diagnosepro.app.ui.ExtrasViewModel
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.Ok
import com.diagnosepro.app.util.Exporter

private fun stepName(id: TestStepId): Int = when (id) {
    TestStepId.ADAPTER -> R.string.st_adapter
    TestStepId.SUPPORTED -> R.string.st_supported
    TestStepId.LIVE_DATA -> R.string.st_live
    TestStepId.SPEED -> R.string.st_speed
    TestStepId.MONITORS -> R.string.rd_title
    TestStepId.STORED -> R.string.codes_stored
    TestStepId.PENDING -> R.string.codes_pending
    TestStepId.PERMANENT -> R.string.codes_permanent
    TestStepId.FREEZE_FRAME -> R.string.ff_title
    TestStepId.VEHICLE_INFO -> R.string.vi_title
    TestStepId.MODE06 -> R.string.tool_mode06
    TestStepId.VOLTAGE -> R.string.pid_adapter_voltage
}

@Composable
fun SelfTestScreen(vm: MainViewModel, onBack: () -> Unit, evm: ExtrasViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val steps by evm.testSteps.collectAsStateWithLifecycle()
    val progress by evm.testProgress.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.tool_selftest), onBack) {
            steps?.let { s ->
                IconButton(onClick = {
                    val log = vm.rawLog.asText().lines().takeLast(400).joinToString("\n")
                    Exporter.shareText(ctx, ctx.getString(R.string.tool_selftest), SelfTest.report(s, BuildConfig.VERSION_NAME, "${Build.VERSION.RELEASE} (${Build.MODEL})", log))
                }) { Icon(Icons.Filled.Share, stringResource(R.string.st_share)) }
            }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Banner(stringResource(R.string.st_note), color = MaterialTheme.colorScheme.secondary) }
            if (!state.isConnected) item { Banner(stringResource(R.string.codes_need_connection)) }
            else item {
                Button(onClick = { evm.runSelfTest() }, enabled = progress == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(if (progress == null) R.string.st_run else R.string.st_running))
                }
                progress?.let { (d, t) -> Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { d.toFloat() / t }, modifier = Modifier.fillMaxWidth()) }
            }
            steps?.let { list ->
                item {
                    val fails = list.count { it.status == TestStatus.FAIL }
                    Text(if (fails == 0) stringResource(R.string.st_all_ok) else stringResource(R.string.st_some_failed, fails),
                        style = MaterialTheme.typography.titleMedium, color = if (fails == 0) Ok else Danger)
                }
                items(list) { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        val (icon, color) = when (s.status) {
                            TestStatus.PASS -> Icons.Filled.CheckCircle to Ok
                            TestStatus.FAIL -> Icons.Filled.Error to Danger
                            TestStatus.NOT_SUPPORTED -> Icons.Filled.RemoveCircleOutline to MaterialTheme.colorScheme.outline
                        }
                        Icon(icon, null, tint = color)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(stepName(s.id)), style = MaterialTheme.typography.titleSmall)
                            // Technical detail is always LTR (codes, hex, units).
                            LtrText(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Text(stringResource(R.string.st_share_hint), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
