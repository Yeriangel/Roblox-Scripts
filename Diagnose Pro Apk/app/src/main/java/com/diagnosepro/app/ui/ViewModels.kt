package com.diagnosepro.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diagnosepro.app.data.HistoryRepository
import com.diagnosepro.app.data.RawLogStore
import com.diagnosepro.app.data.VehicleRepository
import com.diagnosepro.app.data.db.SampleEntity
import com.diagnosepro.app.data.db.ScanEntity
import com.diagnosepro.app.data.db.SessionEntity
import com.diagnosepro.app.data.dtc.DtcDatabase
import com.diagnosepro.app.data.dtc.DtcLookup
import com.diagnosepro.app.data.settings.AppSettings
import com.diagnosepro.app.data.settings.PollingSpeed
import com.diagnosepro.app.data.settings.SettingsRepository
import com.diagnosepro.app.data.settings.ThemeMode
import com.diagnosepro.app.domain.ClearOutcome
import com.diagnosepro.app.domain.ConnectionState
import com.diagnosepro.app.domain.ObdManager
import com.diagnosepro.app.domain.Target
import com.diagnosepro.app.domain.UnitPrefs
import com.diagnosepro.app.obd.DtcReport
import com.diagnosepro.app.obd.FreezeFrame
import com.diagnosepro.app.obd.ObdDecoders
import com.diagnosepro.app.obd.VehicleInfo
import com.diagnosepro.app.transport.BluetoothHelper
import com.diagnosepro.app.transport.TransportType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val obd: ObdManager,
    private val settingsRepo: SettingsRepository,
    val bluetooth: BluetoothHelper,
    private val dtcDb: DtcDatabase,
    val rawLog: RawLogStore,
    private val vehicleRepo: VehicleRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val vehicles = vehicleRepo.vehicles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dtc = MutableStateFlow<DtcLookup?>(null)
    val dtc: StateFlow<DtcLookup?> = _dtc.asStateFlow()

    private var autoConnectTried = false

    init {
        viewModelScope.launch { _dtc.value = dtcDb.get() }
        viewModelScope.launch { obd.restoreVehicle() }
    }

    /** Called once on launch: reconnect to the remembered adapter if allowed. */
    fun tryAutoConnect() {
        if (autoConnectTried) return
        autoConnectTried = true
        viewModelScope.launch {
            val s = settingsRepo.current()
            val last = s.lastAdapter ?: return@launch
            if (!s.autoConnect || obd.state.value != ConnectionState.Disconnected) return@launch
            if (last.type != TransportType.DEMO && (!bluetooth.hasPermissions() || !bluetooth.isEnabled)) return@launch
            obd.connect(Target(last.address, last.name, last.type))
        }
    }

    fun connect(t: Target) = obd.connect(t)
    fun disconnect() = obd.disconnect()

    fun setUnits(u: UnitPrefs) = viewModelScope.launch { settingsRepo.setUnits(u) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { settingsRepo.setTheme(t) }
    fun setPolling(p: PollingSpeed) = viewModelScope.launch { settingsRepo.setPolling(p) }
    fun setAutoConnect(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoConnect(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { settingsRepo.setKeepScreenOn(v) }
    fun setDevConsole(v: Boolean) = viewModelScope.launch { settingsRepo.setDeveloperConsole(v) }
    fun finishOnboarding() = viewModelScope.launch { settingsRepo.setOnboardingDone() }
    fun forgetAdapter() = viewModelScope.launch { settingsRepo.setLastAdapter(null) }
    fun setGraphPids(keys: List<String>) = viewModelScope.launch { settingsRepo.setGraphPids(keys) }
    fun setGauges(keys: List<String>) = viewModelScope.launch { obd.setGauges(keys) }
    fun selectVehicle(id: Long) = viewModelScope.launch { obd.selectVehicle(id) }
    fun renameVehicle(id: Long, name: String) = viewModelScope.launch {
        vehicleRepo.rename(id, name)
        if (obd.vehicle.value?.id == id) obd.selectVehicle(id)
    }
    fun deleteVehicle(id: Long) = viewModelScope.launch { vehicleRepo.delete(id) }
    fun startLogging() = viewModelScope.launch { obd.startLogging(settingsRepo.current().logPids) }
    fun stopLogging() = obd.stopLogging()
}

@HiltViewModel
class CodesViewModel @Inject constructor(private val obd: ObdManager) : ViewModel() {
    private val _report = MutableStateFlow<DtcReport?>(null)
    val report: StateFlow<DtcReport?> = _report.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _clear = MutableStateFlow<ClearOutcome?>(null)
    val clearOutcome: StateFlow<ClearOutcome?> = _clear.asStateFlow()
    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    fun scan() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { obd.scanCodes()?.let { _report.value = it } } finally { _busy.value = false }
        }
    }

    fun clear() {
        if (_clearing.value) return
        viewModelScope.launch {
            _clearing.value = true
            try {
                val outcome = obd.clearCodes() ?: ClearOutcome.Failed(null)
                _clear.value = outcome
                when (outcome) {
                    is ClearOutcome.Cleared -> _report.value = outcome.after
                    is ClearOutcome.CameBack -> _report.value = outcome.after
                    is ClearOutcome.Failed -> Unit
                }
            } finally { _clearing.value = false }
        }
    }

    fun dismissClearResult() { _clear.value = null }
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(private val obd: ObdManager) : ViewModel() {
    val busy = MutableStateFlow(false)
    val monitors = MutableStateFlow<Pair<ObdDecoders.MonitorStatus?, ObdDecoders.MonitorStatus?>?>(null)
    val freezeFrame = MutableStateFlow<FreezeFrame?>(null)
    val freezeRead = MutableStateFlow(false)
    val vehicleInfo = MutableStateFlow<VehicleInfo?>(null)
    val mode06 = MutableStateFlow<List<ObdDecoders.Mode06Result>?>(null)
    val mode06Read = MutableStateFlow(false)

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch { busy.value = true; try { block() } finally { busy.value = false } }
    }

    fun readMonitors() = run { monitors.value = obd.readMonitors() }
    fun readFreezeFrame() = run { freezeFrame.value = obd.readFreezeFrame(); freezeRead.value = true }
    fun readVehicleInfo() = run { vehicleInfo.value = obd.readVehicleInfo() }
    fun readMode06() = run { mode06.value = obd.readMode06(); mode06Read.value = true }
}

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repo: HistoryRepository) : ViewModel() {
    val scans = repo.scans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sessions = repo.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scan = MutableStateFlow<ScanEntity?>(null)
    val session = MutableStateFlow<SessionEntity?>(null)
    val samples = MutableStateFlow<List<SampleEntity>>(emptyList())

    fun loadScan(id: Long) = viewModelScope.launch { scan.value = repo.scan(id) }
    fun loadSession(id: Long) = viewModelScope.launch {
        session.value = repo.session(id)
        samples.value = repo.samples(id)
    }
    fun deleteScan(id: Long) = viewModelScope.launch { repo.deleteScan(id) }
    fun deleteSession(id: Long) = viewModelScope.launch { repo.deleteSession(id) }
}
