package com.diagnosepro.app.domain

import com.diagnosepro.app.elm327.ElmError
import com.diagnosepro.app.obd.DtcReport
import com.diagnosepro.app.obd.ObdService
import kotlinx.coroutines.delay

sealed class ClearOutcome {
    /** Stored codes gone and MIL off. Permanent codes may remain until the monitor passes. */
    data class Cleared(val after: DtcReport) : ClearOutcome()
    /** ECU accepted Mode 04, but codes/MIL came back immediately: the fault is still active. */
    data class CameBack(val after: DtcReport) : ClearOutcome()
    data class Failed(val error: ElmError?) : ClearOutcome()
}

/**
 * Mode 04 flow:
 *  1. read and SAVE the current codes (so nothing is lost),
 *  2. send 04 and check for the "44" positive response,
 *  3. wait, re-read codes and MIL status, and report whether the fault came back.
 *
 * The UI must have shown the double confirmation before calling [run].
 */
class ClearCodesFlow(
    private val obd: ObdService,
    private val saveBeforeClear: suspend (DtcReport) -> Unit,
    private val settleDelayMs: Long = 2_000,
) {
    suspend fun run(): ClearOutcome {
        val before = obd.readAllDtcs()
        saveBeforeClear(before)
        val resp = obd.clearDtcs()
        if (!resp.success) return ClearOutcome.Failed(resp.error)
        delay(settleDelayMs)
        val after = obd.readAllDtcs()
        return if (after.stored.isEmpty() && !after.milOn) ClearOutcome.Cleared(after) else ClearOutcome.CameBack(after)
    }
}
