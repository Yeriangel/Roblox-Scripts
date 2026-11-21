package com.diagnosepro.app.ui.screens

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diagnosepro.app.R
import com.diagnosepro.app.domain.ConnectionState
import com.diagnosepro.app.domain.Target
import com.diagnosepro.app.transport.BluetoothHelper
import com.diagnosepro.app.transport.BtDevice
import com.diagnosepro.app.transport.TransportType
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.EmptyState
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.LtrText
import com.diagnosepro.app.ui.components.SectionTitle
import com.diagnosepro.app.ui.errorHintRes
import com.diagnosepro.app.ui.stateText
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.Ok

@Composable
fun ConnectScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scanResults by vm.bluetooth.scanResults.collectAsStateWithLifecycle()
    val scanning by vm.bluetooth.scanning.collectAsStateWithLifecycle()
    // Re-read permission / adapter state after returning from system dialogs.
    var refresh by remember { mutableIntStateOf(0) }
    var deniedOnce by remember { mutableStateOf(false) }
    val hasPerm = remember(refresh) { vm.bluetooth.hasPermissions() }
    val btOn = remember(refresh) { vm.bluetooth.isEnabled }
    val paired = remember(refresh, hasPerm, btOn) { if (hasPerm && btOn) vm.bluetooth.pairedDevices() else emptyList() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++; deniedOnce = true }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    DisposableEffect(Unit) { onDispose { vm.bluetooth.stopScan() } }

    val connect: (Target) -> Unit = { t -> vm.bluetooth.stopScan(); vm.connect(t) }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.connect_title), onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DrivingWarning(vm) }
            item { ConnectionStatusCard(state, onDisconnect = { vm.disconnect() }, onRetry = { settings?.lastAdapter?.let { connect(Target(it.address, it.name, it.type)) } }) }

            if (!vm.bluetooth.isSupported) {
                // Emulators / phones without Bluetooth: only demo mode.
            } else if (!hasPerm) {
                item {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(if (Build.VERSION.SDK_INT >= 31) R.string.perm_text else R.string.perm_text_legacy))
                        Spacer(Modifier.height(12.dp))
                        val permanentlyDenied = deniedOnce && activity != null && BluetoothHelper.requiredPermissions().none {
                            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                        }
                        if (permanentlyDenied) {
                            Text(stringResource(R.string.perm_denied_forever), color = Danger)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                            }) { Text(stringResource(R.string.perm_open_settings)) }
                            TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.retry)) }
                        } else {
                            Button(onClick = {
                                val perms = BluetoothHelper.requiredPermissions().toMutableList()
                                if (Build.VERSION.SDK_INT >= 33) perms += android.Manifest.permission.POST_NOTIFICATIONS
                                permLauncher.launch(perms.toTypedArray())
                            }) { Text(stringResource(R.string.perm_grant)) }
                        }
                    }
                }
            } else if (!btOn) {
                item {
                    EmptyState(Icons.Filled.BluetoothDisabled, stringResource(R.string.bt_off_title), stringResource(R.string.bt_off_text)) {
                        Button(onClick = {
                            runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        }) { Text(stringResource(R.string.bt_turn_on)) }
                    }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.paired_devices), Modifier.weight(1f))
                        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text(stringResource(R.string.open_bt_settings)) }
                    }
                }
                if (paired.isEmpty()) item { Text(stringResource(R.string.no_paired), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(paired, key = { "p" + it.address }) { d ->
                    DeviceRow(d, isLast = settings?.lastAdapter?.address == d.address, enabled = !state.isBusy) { connect(Target(d.address, d.name, d.type)) }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.found_devices), Modifier.weight(1f))
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.bluetooth.stopScan() }) { Text(stringResource(R.string.scanning)) }
                        } else {
                            OutlinedButton(onClick = { vm.bluetooth.startScan() }) {
                                Icon(Icons.AutoMirrored.Filled.BluetoothSearching, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.scan))
                            }
                        }
                    }
                }
                val found = scanResults.filterNot { r -> paired.any { it.address == r.address } }
                if (found.isEmpty() && scanning) item { Text(stringResource(R.string.no_found), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(found, key = { "f" + it.address }) { d ->
                    DeviceRow(d, isLast = false, enabled = !state.isBusy) { connect(Target(d.address, d.name, d.type)) }
                }
            }

            item { SectionTitle(stringResource(R.string.demo_mode)) }
            item {
                InfoCard(onClick = { if (!state.isBusy) connect(Target.DEMO) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Science, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.demo_mode), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.demo_mode_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)); Banner(stringResource(R.string.limits_note), color = MaterialTheme.colorScheme.secondary); Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConnectionStatusCard(state: ConnectionState, onDisconnect: () -> Unit, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.isBusy) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
            Text(stateText(ctx, state), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                color = when (state) { is ConnectionState.Connected -> Ok; is ConnectionState.Error -> Danger; else -> MaterialTheme.colorScheme.onSurface })
        }
        when (state) {
            is ConnectionState.Connected -> {
                Spacer(Modifier.height(8.dp))
                LtrText("${state.deviceName} · ELM327 ${state.adapter.version}" + (state.adapter.voltage?.let { " · %.1f V".format(java.util.Locale.US, it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.adapter.likelyClone) {
                    Spacer(Modifier.height(8.dp))
                    Banner(stringResource(R.string.clone_warning_title) + "\n" + stringResource(R.string.clone_warning_text, state.adapter.cloneReason ?: ""))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
            }
            is ConnectionState.Error -> {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(errorHintRes(state.reason)), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row { Button(onClick = onRetry) { Text(stringResource(R.string.retry)) } }
            }
            is ConnectionState.Reconnecting, is ConnectionState.Connecting, is ConnectionState.Initializing -> {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.cancel)) }
            }
            ConnectionState.Disconnected -> Unit
        }
    }
}

@Composable
private fun DeviceRow(d: BtDevice, isLast: Boolean, enabled: Boolean, onClick: () -> Unit) {
    InfoCard(onClick = { if (enabled) onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bluetooth, null, tint = if (d.looksLikeObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                LtrText(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                LtrText(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (d.type == TransportType.BLE) R.string.type_ble else R.string.type_classic), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (d.looksLikeObd) AssistChip(onClick = onClick, label = { Text(stringResource(R.string.likely_obd)) }, leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) })
            if (isLast) { Spacer(Modifier.width(4.dp)); AssistChip(onClick = onClick, label = { Text(stringResource(R.string.last_adapter)) }) }
        }
    }
}
