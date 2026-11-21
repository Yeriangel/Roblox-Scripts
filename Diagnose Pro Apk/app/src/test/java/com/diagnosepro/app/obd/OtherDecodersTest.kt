package com.diagnosepro.app.obd

import com.diagnosepro.app.elm327.ElmResponseParser
import com.diagnosepro.app.elm327.ElmResult
import com.diagnosepro.app.elm327.ObdFrames
import com.diagnosepro.app.elm327.ObdProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherDecodersTest {
    private fun msgs(raw: String, p: ObdProtocol = ObdProtocol.CAN_11_500, h: Boolean = false) =
        ObdFrames.assemble((ElmResponseParser.parse(raw) as ElmResult.Data).lines, p, h)

    @Test fun supportedPidsUnionOfEcus() {
        val m = msgs("41 00 BE 3E B8 11\r41 00 80 00 00 01\r")
        val s = ObdDecoders.decodeSupportedPids(m, 0x00)
        assertTrue(0x01 in s); assertTrue(0x0C in s); assertTrue(0x0D in s); assertTrue(0x20 in s)
        assertFalse(0x02 in s)
        assertEquals(setOf(1, 3, 4, 5, 6, 7, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x20), s)
    }

    @Test fun monitorStatusSpark() {
        // MIL on, 3 codes; misfire/fuel/comp supported & complete; cat+evap+O2+O2heater supported, EVAP incomplete
        val st = ObdDecoders.decodeMonitorStatus(intArrayOf(0x83, 0x07, 0b1110_0101, 0b0000_0100))!!
        assertTrue(st.milOn); assertEquals(3, st.dtcCount); assertFalse(st.isDiesel)
        assertEquals(ObdDecoders.MonitorState.READY, st.monitors[ObdDecoders.Monitor.CATALYST])
        assertEquals(ObdDecoders.MonitorState.NOT_READY, st.monitors[ObdDecoders.Monitor.EVAP])
        assertEquals(ObdDecoders.MonitorState.NOT_SUPPORTED, st.monitors[ObdDecoders.Monitor.SECONDARY_AIR])
        assertEquals(1, st.notReadyCount); assertFalse(st.allReady)
    }

    @Test fun monitorStatusDiesel() {
        val st = ObdDecoders.decodeMonitorStatus(intArrayOf(0x00, 0x0F, 0b0100_0001, 0x00))!!
        assertTrue(st.isDiesel); assertFalse(st.milOn)
        assertEquals(ObdDecoders.MonitorState.READY, st.monitors[ObdDecoders.Monitor.PM_FILTER])
        assertTrue(st.allReady)
    }

    @Test fun vinCanMultiFrameHeadersOff() {
        val raw = "014\r0: 49 02 01 31 47 31\r1: 4A 43 35 34 34 34 52\r2: 37 32 35 32 33 36 37\r"
        assertEquals("1G1JC5444R7252367", ObdDecoders.decodeVin(msgs(raw), true))
    }

    @Test fun vinCanHeadersOn() {
        val raw = "7E8 10 14 49 02 01 31 47 31\r7E8 21 4A 43 35 34 34 34 52\r7E8 22 37 32 35 32 33 36 37\r"
        assertEquals("1G1JC5444R7252367", ObdDecoders.decodeVin(msgs(raw, h = true), true))
    }

    @Test fun vinLegacyFiveMessages() {
        val raw = "49 02 01 00 00 00 31\r49 02 02 47 31 4A 43\r49 02 03 35 34 34 34\r49 02 04 52 37 32 35\r49 02 05 32 33 36 37\r"
        assertEquals("1G1JC5444R7252367", ObdDecoders.decodeVin(msgs(raw, ObdProtocol.ISO_9141_2), false))
    }

    @Test fun mode06() {
        val raw = "7E8 10 13 46 01 01 0A 0B 8F\r7E8 21 0A 00 0E 00 01 02 0A\r7E8 22 01 C2 00 00 03 00 00\r"
        val r = ObdDecoders.decodeMode06(msgs(raw, h = true))
        assertEquals(2, r.size)
        assertEquals(0x01, r[0].tid); assertEquals("mV", r[0].unit)
        assertEquals(2959 * 0.122, r[0].value, 0.01)
        assertTrue(r[0].passed)
    }

    @Test fun ecuNameAndCvn() {
        val m = msgs("7E8 06 49 06 01 1A 2B 3C\r", h = true)
        // incomplete CVN (3 bytes) must be ignored, not crash
        assertTrue(ObdDecoders.decodeCvns(m, true).isEmpty())
    }
}
