package com.diagnosepro.app.domain

import com.diagnosepro.app.elm327.Elm327
import com.diagnosepro.app.elm327.ElmError
import com.diagnosepro.app.obd.DtcReport
import com.diagnosepro.app.obd.ObdService
import com.diagnosepro.app.transport.BaseTransport
import com.diagnosepro.app.transport.DemoTransport
import com.diagnosepro.app.transport.TransportType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearCodesFlowTest {

    @Test fun clearsAndSavesBefore() = runBlocking {
        val t = DemoTransport(latencyMs = 0); t.open()
        val elm = Elm327(t); elm.initialize()
        val saved = mutableListOf<DtcReport>()
        val outcome = ClearCodesFlow(ObdService(elm), { saved += it }, settleDelayMs = 0).run()
        assertEquals(1, saved.size)
        assertTrue(saved[0].stored.isNotEmpty())
        assertTrue(saved[0].milOn)
        assertTrue(outcome is ClearOutcome.Cleared)
        val after = (outcome as ClearOutcome.Cleared).after
        assertTrue(after.stored.isEmpty())
        // Permanent code stays until the monitor passes — realistic behaviour
        assertEquals(listOf("P0420"), after.permanent.map { it.code })
    }

    /** Fake ECU that accepts 04 but keeps the MIL on (fault still active). */
    private class StubbornEcu(private val clearAnswer: String) : BaseTransport() {
        override val type = TransportType.DEMO
        override val isOpen = true
        override suspend fun open() {}
        override fun close() {}
        override suspend fun write(data: ByteArray) {
            val cmd = String(data).trim()
            val ans = when {
                cmd.startsWith("AT") -> "OK"
                cmd == "0101" -> "41 01 81 07 65 00"
                cmd == "03" -> "7E8 04 43 01 03 01"
                cmd == "04" -> clearAnswer
                else -> "NO DATA"
            }
            deliver("$ans\r\r>".toByteArray())
        }
    }

    @Test fun codesComeBack() = runBlocking {
        val elm = Elm327(StubbornEcu("44"))
        val outcome = ClearCodesFlow(ObdService(elm), {}, 0).run()
        assertTrue(outcome is ClearOutcome.CameBack)
    }

    @Test fun noDataMeansFailure() = runBlocking {
        val elm = Elm327(StubbornEcu("NO DATA"))
        val outcome = ClearCodesFlow(ObdService(elm), {}, 0).run()
        assertEquals(ClearOutcome.Failed(ElmError.NO_DATA), outcome)
    }

    @Test fun negativeResponseMeansFailure() = runBlocking {
        val elm = Elm327(StubbornEcu("7F 04 22"))
        val outcome = ClearCodesFlow(ObdService(elm), {}, 0).run()
        assertTrue(outcome is ClearOutcome.Failed)
    }
}
