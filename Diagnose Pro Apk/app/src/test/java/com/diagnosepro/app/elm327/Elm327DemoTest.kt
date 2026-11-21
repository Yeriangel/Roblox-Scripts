package com.diagnosepro.app.elm327

import com.diagnosepro.app.obd.DtcKind
import com.diagnosepro.app.obd.ObdService
import com.diagnosepro.app.obd.Pids
import com.diagnosepro.app.transport.DemoTransport
import com.diagnosepro.app.transport.TransportClosedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end through the real ELM layer against the simulator. */
class Elm327DemoTest {
    private fun setup(): Pair<DemoTransport, Elm327> = runBlocking {
        val t = DemoTransport(latencyMs = 0)
        t.open()
        t to Elm327(t)
    }

    @Test fun initDetectsCanAndVersion() = runBlocking {
        val (_, elm) = setup()
        val info = elm.initialize()
        assertEquals(ObdProtocol.CAN_11_500, info.protocol)
        assertEquals("v2.2", info.version)
        assertFalse(info.likelyClone)
    }

    @Test fun supportedPidsAndLiveValues() = runBlocking {
        val (_, elm) = setup(); elm.initialize()
        val obd = ObdService(elm)
        val supported = obd.readSupportedPids()
        assertTrue(0x0C in supported); assertTrue(0x42 in supported); assertTrue(0xA6 in supported)
        val rpm = obd.readPid(Pids.byKey("rpm")!!)
        assertNotNull(rpm); assertTrue(rpm!! > 500)
    }

    @Test fun dtcsFromTwoEcus() = runBlocking {
        val (_, elm) = setup(); elm.initialize()
        val obd = ObdService(elm)
        val (stored, err) = obd.readDtcs(DtcKind.STORED)
        assertEquals(null, err)
        assertEquals(setOf("P0301", "P0420", "P0715"), stored.map { it.code }.toSet())
        assertEquals("7E9", stored.first { it.code == "P0715" }.ecu)
        assertFalse(elm.headersOn) // restored
    }

    @Test fun vehicleInfo() = runBlocking {
        val (_, elm) = setup(); elm.initialize()
        val vi = ObdService(elm).readVehicleInfo()
        assertEquals("WVWZZZ1KZ9W123456", vi.vin)
        assertEquals("ECM-EngineControl", vi.ecuNames.first().second)
        assertEquals("1A2B3C4D", vi.cvns.first().second)
    }

    @Test fun freezeFrameAndMode06() = runBlocking {
        val (_, elm) = setup(); elm.initialize()
        val obd = ObdService(elm)
        val ff = obd.readFreezeFrame()!!
        assertEquals("P0301", ff.dtc)
        assertEquals(2150.0, ff.values.first { it.first.key == "rpm" }.second, 0.01)
        val m6 = obd.readMode06()!!
        assertTrue(m6.size >= 2)
    }

    @Test fun headersRestoredAfterVehicleInfoThenMode06Works() = runBlocking {
        val (_, elm) = setup(); elm.initialize()
        val obd = ObdService(elm)
        obd.readVehicleInfo()
        assertFalse("headers must be off again", elm.headersOn)
        assertTrue(obd.readMode06()!!.isNotEmpty())
    }

    @Test fun selfTestPassesOnSimulatedCarAndNeverClearsCodes() = runBlocking {
        val sent = mutableListOf<String>()
        val t = DemoTransport(latencyMs = 0); t.open()
        val elm = Elm327(t, RawLogger { d, text -> if (d == RawLogger.Direction.TX) sent += text })
        val info = elm.initialize()
        val steps = com.diagnosepro.app.domain.SelfTest(ObdService(elm), info).run { _, _ -> }
        assertEquals(com.diagnosepro.app.domain.TestStepId.entries.size, steps.size)
        assertTrue(steps.toString(), steps.none { it.status == com.diagnosepro.app.domain.TestStatus.FAIL })
        assertTrue("self-test must be read-only", sent.none { it == "04" || it.startsWith("08") })
        assertTrue(steps.first { it.id == com.diagnosepro.app.domain.TestStepId.STORED }.detail.contains("P0301@7E8"))
    }

    @Test fun disconnectMidReadThrowsClosedNotCrash() = runBlocking {
        val (t, elm) = setup(); elm.initialize()
        t.close()
        try {
            elm.send("010C")
            fail("expected exception")
        } catch (e: TransportClosedException) {
            // expected
        }
    }
}
