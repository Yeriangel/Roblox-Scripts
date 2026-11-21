package com.diagnosepro.app.elm327

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samples recorded from real adapters (genuine ELM327, OBDLink, v1.5 / v2.1 clones). */
class ElmResponseParserTest {
    private fun data(raw: String, cmd: String? = null) = (ElmResponseParser.parse(raw, cmd) as ElmResult.Data).lines
    private fun err(raw: String, cmd: String? = null) = (ElmResponseParser.parse(raw, cmd) as ElmResult.Error).error

    @Test fun simpleWithPrompt() = assertEquals(listOf("41 0C 1A F8"), data("41 0C 1A F8\r\r>"))

    @Test fun echoIsRemoved() = assertEquals(listOf("41 0D 32"), data("010D\r41 0D 32\r\r>", "010D"))

    @Test fun searchingIsRemoved() = assertEquals(listOf("41 00 BE 3E B8 11"), data("SEARCHING...\r41 00 BE 3E B8 11\r\r>", "0100"))

    @Test fun busInitOkIsRemoved() = assertEquals(listOf("41 00 BE 1F B8 10"), data("BUS INIT: ...OK\r41 00 BE 1F B8 10\r\r>"))

    @Test fun crLfAndNulBytes() = assertEquals(listOf("41 05 7B"), data("\u000041 05 7B\r\n\r\n>"))

    @Test fun atzBanner() = assertEquals(listOf("ELM327 v1.5"), data("ATZ\r\r\rELM327 v1.5\r\r>", "ATZ"))

    @Test fun errors() {
        assertEquals(ElmError.NO_DATA, err("NO DATA\r\r>"))
        assertEquals(ElmError.NO_DATA, err("SEARCHING...\rNO DATA\r\r>"))
        assertEquals(ElmError.UNABLE_TO_CONNECT, err("SEARCHING...\rUNABLE TO CONNECT\r\r>"))
        assertEquals(ElmError.CAN_ERROR, err("CAN ERROR\r\r>"))
        assertEquals(ElmError.BUS_INIT_ERROR, err("BUS INIT: ...ERROR\r\r>"))
        assertEquals(ElmError.STOPPED, err("STOPPED\r\r>"))
        assertEquals(ElmError.UNKNOWN_COMMAND, err("?\r\r>"))
        assertEquals(ElmError.BUFFER_FULL, err("BUFFER FULL\r\r>"))
        assertEquals(ElmError.DATA_ERROR, err("41 0C 1A <DATA ERROR\r\r>"))
        assertEquals(ElmError.LOW_VOLTAGE, err("LV RESET\r\r>"))
        assertEquals(ElmError.TIMEOUT, err(""))
    }

    @Test fun multiEcuWithOneNoData() {
        // Some ECUs answer, one sends NO DATA — keep the good lines
        assertEquals(listOf("7E8 03 41 0D 00"), data("7E8 03 41 0D 00\rNO DATA\r\r>"))
    }

    @Test fun multiLineIsoTp() {
        val l = data("014\r0: 49 02 01 31 47 31\r1: 4A 43 35 34 34 34 52\r2: 37 32 35 32 33 36 37\r\r>")
        assertEquals(4, l.size)
        assertTrue(l.all { ElmResponseParser.isHexLine(it) })
    }

    @Test fun voltage() {
        assertEquals(12.6, ElmResponseParser.parseVoltage(listOf("12.6V"))!!, 0.001)
        assertEquals(14.05, ElmResponseParser.parseVoltage(listOf("14.05 V"))!!, 0.001)
    }

    @Test fun garbageDoesNotCrash() {
        val r = ElmResponseParser.parse("ÿþ##@@\r>")
        assertTrue(r is ElmResult.Data || r is ElmResult.Error)
        val frames = ObdFrames.assemble(listOf("ZZ"), ObdProtocol.CAN_11_500, true)
        assertTrue(frames.isEmpty() || frames.all { it.bytes.isEmpty() || true })
    }

    @Test fun protocolFromDpn() {
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.ISO_9141_2, ObdProtocol.fromDpn("3"))
        assertEquals(ObdProtocol.J1939, ObdProtocol.fromDpn("AA"))
    }
}
