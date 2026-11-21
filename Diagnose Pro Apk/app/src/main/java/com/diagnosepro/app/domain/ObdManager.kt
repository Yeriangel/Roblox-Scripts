package com.diagnosepro.app.domain

import android.content.Context
import com.diagnosepro.app.data.ExtrasRepository
import com.diagnosepro.app.data.HistoryRepository
import com.diagnosepro.app.data.RawLogStore
import com.diagnosepro.app.data.VehicleRepository
import com.diagnosepro.app.data.db.SampleEntity
import com.diagnosepro.app.data.db.VehicleEntity
import com.diagnosepro.app.data.settings.LastAdapter
import com.diagnosepro.app.data.settings.SettingsRepository
import com.diagnosepro.app.elm327.AdapterInfo
import com.diagnosepro.app.elm327.Elm327
import com.diagnosepro.app.elm327.ElmInitException
import com.diagnosepro.app.elm327.ElmResult
import com.diagnosepro.app.obd.CustomPid
import com.diagnosepro.app.obd.TerminalGuard
import com.diagnosepro.app.elm327.InitStage
import com.diagnosepro.app.elm327.ObdProtocol
import com.diagnosepro.app.elm327.RawLogger
import com.diagnosepro.app.obd.DtcReport
import com.diagnosepro.app.obd.FreezeFrame
import com.diagnosepro.app.obd.ObdDecoders
import com.diagnosepro.app.obd.ObdService
import com.diagnosepro.app.obd.PidDef
import com.diagnosepro.app.obd.Pids
import com.diagnosepro.app.obd.VehicleInfo
import com.diagnosepro.app.service.ObdForegroundService
import com.diagnosepro.app.transport.BleTransport
import com.diagnosepro.app.transport.BluetoothHelper
import com.diagnosepro.app.transport.ClassicSppTransport
import com.diagnosepro.app.transport.DemoTransport
import com.diagnosepro.app.transport.ObdTransport
import com.diagnosepro.app.transport.OpenFailure
import com.diagnosepro.app.transport.TransportClosedException
import com.diagnosepro.app.transport.TransportOpenException
import com.diagnosepro.app.transport.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ErrorReason {
    BLUETOOTH_OFF, PERMISSION, DEVICE_NOT_FOUND, SOCKET_FAILED, NO_OBD_SERVICE, TIMEOUT,
    NOT_ELM, NO_VEHICLE, BUS_ERROR, LINK_LOST, UNKNOWN,
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Initializing(val stage: InitStage) : ConnectionState()
    data class Connected(val protocol: ObdProtocol?, val adapter: AdapterInfo, val demo: Boolean, val deviceName: String) : ConnectionState()
    data class Reconnecting(val attempt: Int, val delaySec: Int) : ConnectionState()
    data class Error(val reason: ErrorReason) : ConnectionState()

    val isConnected get() = this is Connected
    val isBusy get() = this is Connecting || this is Initializing || this is Reconnecting
}

data class Target(val address: String, val name: String, val type: TransportType) {
    companion object { val DEMO = Target("DEMO", "Demo ELM327", TransportType.DEMO) }
}

data class LiveValue(val value: Double, val time: Long)

data class LoggingState(val sessionId: Long, val startedAt: Long, val samples: Int, val keys: List<String>)

/**
 * Owns the adapter connection for the whole app (singleton, survives screen changes and,
 * together with [ObdForegroundService], screen-off).
 *
 * Responsibilities: connect/init, auto-reconnect with backoff, smart polling of only the
 * PIDs that screens need and the car supports, live history for graphs, session logging,
 * and one-off operations (codes, clear, freeze frame, readiness, vehicle info, Mode 06).
 */
@Singleton
class ObdManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val rawLog: RawLogStore,
    private val history: HistoryRepository,
    private val vehicles: VehicleRepository,
    private val bluetooth: BluetoothHelper,
    private val extras: ExtrasRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Alarm rules currently violated (shown as a banner; a tone plays when one starts). */
    private val _alerts = MutableStateFlow<List<ActiveAlert>>(emptyList())
    val alerts: StateFlow<List<ActiveAlert>> = _alerts.asStateFlow()
    @Volatile private var alertRules: List<AlertRule> = emptyList()
    @Volatile private var alertSound = true
    private val lastTone = HashMap<AlertRule, Long>()
    private val stats = HashMap<String, Pair<Double, Double>>()

    init {
        // Keep user-defined PIDs registered so every screen can resolve them by key.
        scope.launch {
            extras.customPids.collect { list ->
                Pids.setCustom(list.mapNotNull { CustomPid.toDef(it.id, it.name, it.command, it.formula, it.unit, it.minValue, it.maxValue) })
            }
        }
        scope.launch { settings.settings.collect { alertRules = it.alerts; alertSound = it.alertSound } }
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _live = MutableStateFlow<Map<String, LiveValue>>(emptyMap())
    val live: StateFlow<Map<String, LiveValue>> = _live.asStateFlow()

    private val _supported = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supported.asStateFlow()

    private val _vehicle = MutableStateFlow<VehicleEntity?>(null)
    val vehicle: StateFlow<VehicleEntity?> = _vehicle.asStateFlow()

    private val _logging = MutableStateFlow<LoggingState?>(null)
    val logging: StateFlow<LoggingState?> = _logging.asStateFlow()

    /** False when the adapter answers but the car stopped answering (ignition turned off). */
    private val _vehicleResponding = MutableStateFlow(true)
    val vehicleResponding: StateFlow<Boolean> = _vehicleResponding.asStateFlow()

    /** Incremented on every poll cycle; graph screens use it as a refresh tick. */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private val historyLock = Any()
    private val liveHistory = HashMap<String, ArrayDeque<Pair<Long, Double>>>()
    private val historyWindowMs = 10 * 60_000L

    private var connectJob: Job? = null
    private var transport: ObdTransport? = null
    @Volatile private var obd: ObdService? = null
    @Volatile private var userDisconnect = false
    private var currentTarget: Target? = null

    private val demand = HashMap<String, Set<String>>()
    private val pendingSamples = mutableListOf<SampleEntity>()

    val isDemo get() = currentTarget?.type == TransportType.DEMO

    // ---------------------------------------------------------------- connection

    fun connect(target: Target) {
        userDisconnect = false
        connectJob?.cancel()
        closeTransport()
        currentTarget = target
        connectJob = scope.launch { connectLoop(target) }
    }

    fun disconnect() {
        userDisconnect = true
        connectJob?.cancel()
        connectJob = null
        scope.launch { stopLoggingInternal() }
        closeTransport()
        obd = null
        _state.value = ConnectionState.Disconnected
        _live.value = emptyMap()
        _alerts.value = emptyList()
        resetStats()
        ObdForegroundService.stop(context)
    }

    private fun closeTransport() {
        runCatching { transport?.close() }
        transport = null
    }

    private fun createTransport(t: Target): ObdTransport = when (t.type) {
        TransportType.DEMO -> DemoTransport()
        TransportType.CLASSIC -> ClassicSppTransport(bluetooth.adapter, t.address)
        TransportType.BLE -> BleTransport(context, bluetooth.adapter, t.address)
    }

    private suspend fun connectLoop(target: Target) {
        var attempt = 0
        var everConnected = false
        while (scope.isActive && !userDisconnect) {
            try {
                if (target.type != TransportType.DEMO) {
                    if (!bluetooth.hasPermissions()) throw TransportOpenException(OpenFailure.PERMISSION, "permission")
                    if (!bluetooth.isEnabled) throw TransportOpenException(OpenFailure.BLUETOOTH_OFF, "bt off")
                }
                _state.value = ConnectionState.Connecting(target.name)
                val t = createTransport(target)
                transport = t
                t.open()
                val s = settings.current()
                val elm = Elm327(t, rawLog, defaultTimeoutMs = 2_500)
                rawLog.log(RawLogger.Direction.INFO, "connected to ${target.name} (${target.type})")
                val info = elm.initialize { stage -> _state.value = ConnectionState.Initializing(stage) }
                val service = ObdService(elm)
                _supported.value = service.readSupportedPids()
                val vin = runCatching { service.readVin() }.getOrNull()
                val v = vehicles.resolve(vin, target.address, if (target.type == TransportType.DEMO) "Demo car" else target.name)
                _vehicle.value = v
                settings.setActiveVehicle(v.id)
                settings.setLastAdapter(LastAdapter(target.address, target.name, target.type))
                obd = service
                attempt = 0
                everConnected = true
                _vehicleResponding.value = true
                _state.value = ConnectionState.Connected(info.protocol, info, target.type == TransportType.DEMO, target.name)
                if (target.type != TransportType.DEMO) ObdForegroundService.start(context, target.name)
                pollLoop(service, s.polling.delayMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                obd = null
                closeTransport()
                if (userDisconnect) return
                val reason = when (e) {
                    is TransportOpenException -> when (e.reason) {
                        OpenFailure.BLUETOOTH_OFF -> ErrorReason.BLUETOOTH_OFF
                        OpenFailure.PERMISSION -> ErrorReason.PERMISSION
                        OpenFailure.DEVICE_NOT_FOUND -> ErrorReason.DEVICE_NOT_FOUND
                        OpenFailure.SOCKET_FAILED -> ErrorReason.SOCKET_FAILED
                        OpenFailure.NO_OBD_SERVICE -> ErrorReason.NO_OBD_SERVICE
                        OpenFailure.TIMEOUT -> ErrorReason.TIMEOUT
                    }
                    is ElmInitException -> when (e.reason) {
                        ElmInitException.Reason.NOT_ELM -> ErrorReason.NOT_ELM
                        ElmInitException.Reason.NO_VEHICLE -> ErrorReason.NO_VEHICLE
                        ElmInitException.Reason.BUS_ERROR -> ErrorReason.BUS_ERROR
                    }
                    is TransportClosedException -> ErrorReason.LINK_LOST
                    else -> ErrorReason.UNKNOWN
                }
                rawLog.log(RawLogger.Direction.INFO, "error: $reason ${e.message}")
                // Auto-reconnect only after a working connection dropped. First-time failures
                // are reported so the user can fix them (wrong adapter, ignition off...).
                if (!everConnected || reason == ErrorReason.PERMISSION || attempt >= MAX_RECONNECTS) {
                    _state.value = ConnectionState.Error(reason)
                    stopLoggingInternal()
                    ObdForegroundService.stop(context)
                    return
                }
                attempt++
                val wait = BACKOFF_SEC.getOrElse(attempt - 1) { BACKOFF_SEC.last() }
                _state.value = ConnectionState.Reconnecting(attempt, wait)
                delay(wait * 1000L)
            }
        }
    }

    // ---------------------------------------------------------------- polling

    /** Screens declare which PID keys they need; only those are polled. */
    fun setDemand(owner: String, keys: Collection<String>) {
        synchronized(demand) { if (keys.isEmpty()) demand.remove(owner) else demand[owner] = keys.toSet() }
    }

    private fun demandedDefs(): List<PidDef> {
        // Alarms must keep working on every screen, so their PIDs are always polled.
        val keys = synchronized(demand) { demand.values.flatten().toSet() } + (_logging.value?.keys ?: emptyList()) + alertRules.map { it.key }
        val supported = _supported.value
        val wanted = keys.mapNotNull { Pids.byKey(it) }.filter { Pids.isAvailable(it, supported) }
        // Calculated PIDs need their inputs read from the car (boost also uses baro when available).
        val inputs = wanted.filter { it.isComputed }
            .flatMap { it.inputs + if (it.key == "boost") listOf("baro") else emptyList() }
            .mapNotNull { Pids.byKey(it) }.filter { Pids.isAvailable(it, supported) }
        return (wanted + inputs).distinctBy { it.key }
    }

    private suspend fun pollLoop(service: ObdService, defaultDelay: Long) {
        var cycle = 0L
        var silentCycles = 0
        while (scope.isActive) {
            val delayMs = runCatching { settings.current().polling.delayMs }.getOrDefault(defaultDelay)
            val defs = demandedDefs()
            if (defs.isEmpty()) { delay(300); continue }
            var gotAny = false
            var attempted = 0
            for (def in defs) {
                if (def.isComputed) continue
                if (cycle % def.priority.everyNCycles != 0L && _live.value.containsKey(def.key)) continue
                attempted++
                val v = service.readPid(def) // throws TransportClosedException on link loss
                if (v != null) {
                    gotAny = gotAny || def.key != Pids.KEY_ADAPTER_VOLTAGE
                    record(def.key, v, System.currentTimeMillis())
                }
            }
            if (attempted > 0 && defs.any { it.key != Pids.KEY_ADAPTER_VOLTAGE }) {
                silentCycles = if (gotAny) 0 else silentCycles + 1
                _vehicleResponding.value = silentCycles < 4
            }
            // Derive calculated PIDs from the values just read.
            val computed = defs.filter { it.isComputed }
            if (computed.isNotEmpty()) {
                val values = _live.value.mapValues { it.value.value }
                val now = System.currentTimeMillis()
                for (def in computed) {
                    val v = runCatching { def.compute?.invoke(values) }.getOrNull()
                    if (v != null) record(def.key, v, now) else _live.value = _live.value - def.key
                }
            }
            flushSamplesIfNeeded()
            cycle++
            _tick.value = cycle
            delay(delayMs)
        }
    }

    private fun record(key: String, value: Double, t: Long) {
        _live.value = _live.value + (key to LiveValue(value, t))
        synchronized(historyLock) {
            val q = liveHistory.getOrPut(key) { ArrayDeque() }
            q.addLast(t to value)
            while (q.isNotEmpty() && t - q.first().first > historyWindowMs) q.removeFirst()
            val s = stats[key]
            stats[key] = if (s == null) value to value else minOf(s.first, value) to maxOf(s.second, value)
        }
        checkAlerts(key, value, t)
        val log = _logging.value
        if (log != null && key in log.keys) {
            synchronized(pendingSamples) { pendingSamples += SampleEntity(sessionId = log.sessionId, t = t, pid = key, value = value) }
            _logging.value = log.copy(samples = log.samples + 1)
        }
    }

    private fun checkAlerts(key: String, value: Double, t: Long) {
        val rules = alertRules.filter { it.key == key }
        if (rules.isEmpty() && _alerts.value.none { it.rule.key == key }) return
        val violated = rules.filter { it.matches(value) }
        val others = _alerts.value.filter { it.rule.key != key }
        val started = violated.filter { r -> _alerts.value.none { it.rule == r } }
        _alerts.value = others + violated.map { ActiveAlert(it, value, t) }
        if (started.isNotEmpty() && alertSound) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (started.any { now - (lastTone[it] ?: 0L) > 20_000 }) {
                started.forEach { lastTone[it] = now }
                runCatching {
                    val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 90)
                    tone.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900)
                    scope.launch { delay(1_200); runCatching { tone.release() } }
                }
            }
        }
    }

    /** Minimum and maximum seen since connecting (or since [resetStats]). */
    fun statsOf(key: String): Pair<Double, Double>? = synchronized(historyLock) { stats[key] }
    fun resetStats() = synchronized(historyLock) { stats.clear() }

    /** Snapshot of the last 10 minutes of a PID, for live graphs. */
    fun historyOf(key: String): List<Pair<Long, Double>> = synchronized(historyLock) { liveHistory[key]?.toList() ?: emptyList() }

    fun clearHistory() = synchronized(historyLock) { liveHistory.clear() }

    // ---------------------------------------------------------------- logging

    fun startLogging(keys: List<String>) {
        if (_logging.value != null || keys.isEmpty()) return
        scope.launch {
            val id = history.startSession(_vehicle.value?.id, keys, isDemo)
            _logging.value = LoggingState(id, System.currentTimeMillis(), 0, keys)
        }
    }

    fun stopLogging() { scope.launch { stopLoggingInternal() } }

    private suspend fun flushSamplesIfNeeded(force: Boolean = false) {
        val batch = synchronized(pendingSamples) {
            if (!force && pendingSamples.size < 40) return
            pendingSamples.toList().also { pendingSamples.clear() }
        }
        runCatching { history.addSamples(batch) }
    }

    private suspend fun stopLoggingInternal() = withContext(NonCancellable) {
        val log = _logging.value ?: return@withContext
        _logging.value = null
        flushSamplesIfNeeded(force = true)
        val end = System.currentTimeMillis()
        val summary = TripStats.summarize(
            history.samples(log.sessionId, "speed").map { it.t to it.value },
            history.samples(log.sessionId, "rpm").map { it.t to it.value },
            history.samples(log.sessionId, "coolant_temp").map { it.t to it.value },
            log.startedAt, end,
        )
        history.session(log.sessionId)?.let {
            history.updateSession(
                it.copy(endTime = end, durationS = summary.durationS, distanceKm = summary.distanceKm,
                    maxSpeed = summary.maxSpeed, avgRpm = summary.avgRpm, maxCoolant = summary.maxCoolant),
            )
        }
    }

    // ---------------------------------------------------------------- one-off operations

    /** Runs [block] with the live OBD service, or returns null if not connected. */
    private suspend fun <T> withObd(block: suspend (ObdService) -> T): T? {
        val s = obd ?: return null
        return try {
            // Run in the manager's scope, off the main thread: if the user leaves the screen,
            // the running ELM327 exchange still completes cleanly instead of being cut in half.
            scope.async { block(s) }.await()
        } catch (e: TransportClosedException) {
            null // the connect loop notices the drop and reconnects
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rawLog.log(RawLogger.Direction.INFO, "operation failed: ${e.message}")
            null
        }
    }

    private suspend fun mileage(s: ObdService): Double? =
        if (0xA6 in _supported.value) s.readPid(Pids.byKey("odometer")!!) else null

    /** Reads stored, pending and permanent codes + MIL and saves the scan to history. */
    suspend fun scanCodes(): DtcReport? = withObd { s ->
        val report = s.readAllDtcs()
        if (!report.failed) history.saveScan(_vehicle.value?.id, report, mileage(s), beforeClear = false, demo = isDemo)
        report
    }

    suspend fun clearCodes(): ClearOutcome? = withObd { s ->
        val km = mileage(s)
        ClearCodesFlow(s, { before -> history.saveScan(_vehicle.value?.id, before, km, beforeClear = true, demo = isDemo) }).run()
    }

    suspend fun readFreezeFrame(): FreezeFrame? = withObd { it.readFreezeFrame() }
    suspend fun readMonitors(): Pair<ObdDecoders.MonitorStatus?, ObdDecoders.MonitorStatus?>? =
        withObd { it.readMonitorStatus(0x01) to (if (0x41 in _supported.value) it.readMonitorStatus(0x41) else null) }
    suspend fun readVehicleInfo(): VehicleInfo? = withObd { it.readVehicleInfo() }
    suspend fun readMode06(): List<ObdDecoders.Mode06Result>? = withObd { it.readMode06() }
    suspend fun readVoltage(): Double? = withObd { it.readVoltage() }

    /** Developer terminal. Refuses anything that is not a safe adapter command or a read-only service. */
    suspend fun sendRaw(command: String): String? {
        if (!TerminalGuard.isAllowed(command)) return null
        return withObd { s ->
            when (val r = s.elm.send(command.replace(" ", "").uppercase(), 5_000, retry = false)) {
                is ElmResult.Data -> r.lines.joinToString("\n")
                is ElmResult.Error -> r.error.name
            }
        }
    }

    suspend fun setGauges(keys: List<String>) = withContext(Dispatchers.IO) {
        setGaugesInternal(keys)
    }

    private suspend fun setGaugesInternal(keys: List<String>) {
        val v = _vehicle.value ?: return
        vehicles.setGauges(v.id, keys)
        _vehicle.value = vehicles.get(v.id)
    }

    /** Selects a saved vehicle manually (only used when not connected). */
    suspend fun selectVehicle(id: Long) {
        _vehicle.value = vehicles.get(id)
        settings.setActiveVehicle(id)
    }

    suspend fun restoreVehicle() {
        if (_vehicle.value == null) settings.current().activeVehicleId?.let { _vehicle.value = vehicles.get(it) }
    }

    companion object {
        private val BACKOFF_SEC = listOf(2, 4, 8, 16, 30)
        private const val MAX_RECONNECTS = 12
    }
}
