package com.diagnosepro.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diagnosepro.app.R
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.EmptyState
import com.diagnosepro.app.ui.theme.Danger

/** Shown on interactive screens while the car is moving. */
@Composable
fun DrivingWarning(vm: MainViewModel, modifier: Modifier = Modifier) {
    val live by vm.obd.live.collectAsStateWithLifecycle()
    val speed = live["speed"]?.value ?: 0.0
    if (speed > 5) Banner(stringResource(R.string.driving_warning), color = Danger, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
fun NotConnectedState(text: String, onConnect: () -> Unit) {
    EmptyState(Icons.Filled.DirectionsCar, stringResource(R.string.dash_not_connected_title), text) {
        Button(onClick = onConnect) { Text(stringResource(R.string.connect)) }
    }
}
