package com.diagnosepro.app.obd

import com.diagnosepro.app.elm327.ElmResponseParser
import com.diagnosepro.app.elm327.ElmResult
import com.diagnosepro.app.elm327.ObdFrames
import com.diagnosepro.app.elm327.ObdProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {

    private fun lines(raw: String) = (ElmResponseParser.parse(raw) as ElmResult.Data).lines

    @Test fun twoByteDecoding() {
        assertEquals("P0133", ObdDecoders.dtcFromBytes(0x01, 0x33))
        assertEquals("C0300", ObdDecoders.dtcFromBytes(0x43, 0x00))
        assertEquals("B1234", ObdDecoders.dtcFromBytes(0x92, 0x34))
        assertEquals("U0100", ObdDecoders.dtcFromBytes(0xC1, 0x00))
        assertEquals("P2A3F", ObdDecoders.dtcFromBytes(0x2A, 0x3F))
        assertEquals("P3400", ObdDecoders.dtcFromBytes(0x34, 0x00))
    }

    @Test fun canSingleFrameHeadersOff() {
        val msgs = ObdFrames.assemble(lines("43 02 01 33 03 01\r\r"), ObdProtocol.CAN_11_500, headers = false)
        val dtcs = ObdDecoders.decodeDtcs(msgs, DtcKind.STORED, isCan = true)
        assertEquals(listOf("P0133", "P0301"), dtcs.map { it.code })
    }

    @Test fun canNoCodes() {
        val msgs = ObdFrames.assemble(lines("43 00\r"), ObdProtocol.CAN_11_500, false)
        assertTrue(ObdDecoders.decodeDtcs(msgs, DtcKind.STORED, true).isEmpty())
    }

    @Test fun canMultiFrameHeadersOff() {
        val raw = "00A\r0: 43 04 01 33 01 34\r1: 01 35 01 36 00 00 00\r\r"
        val msgs = ObdFrames.assemble(lines(raw), ObdProtocol.CAN_11_500, false)
        val dtcs = ObdDecoders.decodeDtcs(msgs, DtcKind.STORED, true)
        assertEquals(listOf("P0133", "P0134", "P0135", "P0136"), dtcs.map { it.code })
    }

    @Test fun canMultiFrameWithHeadersAndTwoEcus() {
        val raw = "7E8 10 0A 43 04 01 33 01 34\r7E9 04 43 01 07 15\r7E8 21 01 35 01 36 00 00 00\r\r"
        val msgs = ObdFrames.assemble(lines(raw), ObdProtocol.CAN_11_500, true)
        val dtcs = ObdDecoders.decodeDtcs(msgs, DtcKind.STORED, true)
        assertEquals(5, dtcs.size)
        assertEquals(setOf("P0133", "P0134", "P0135", "P0136"), dtcs.filter { it.ecu == "7E8" }.map { it.code }.toSet())
        assertEquals(listOf("P0715"), dtcs.filter { it.ecu == "7E9" }.map { it.code })
    }

    @Test fun canHeadersNoSpaces() {
        val msgs = ObdFrames.assemble(lines("7E804470101710000\r"), ObdProtocol.CAN_11_500, true)
        val d = ObdDecoders.decodeDtcs(msgs, DtcKind.PENDING, true)
        assertEquals(listOf("P0171"), d.map { it.code })
        assertEquals("7E8", d[0].ecu)
    }

    @Test fun can29BitHeaders() {
        val msgs = ObdFrames.assemble(lines("18 DA F1 10 04 43 01 03 00\r"), ObdProtocol.CAN_29_500, true)
        val d = ObdDecoders.decodeDtcs(msgs, DtcKind.STORED, true)
        assertEquals("P0300", d.single().code)
        assertEquals("10", d.single().ecu)
    }

    @Test fun legacyIso9141HeadersOff() {
        // Two lines, 3 slots each, padded with 00 00
        val raw = "43 01 33 03 01 00 00\r43 04 20 00 00 00 00\r"
        val msgs = ObdFrames.assemble(lines(raw), ObdProtocol.ISO_9141_2, false)
        val d = ObdDecoders.decodeDtcs(msgs, DtcKind.STORED, isCan = false)
        assertEquals(listOf("P0133", "P0301", "P0420"), d.map { it.code })
    }

    @Test fun legacyJ1850HeadersOnMultiEcu() {
        // 48 6B 10 = priority/target/source(ECU 10), last byte = checksum
        val raw = "48 6B 10 43 03 01 00 00 00 00 A1\r48 6B 18 43 07 00 00 00 00 00 B2\r"
        val msgs = ObdFrames.assemble(lines(raw), ObdProtocol.J1850_PWM, true)
        val d = ObdDecoders.decodeDtcs(msgs, DtcKind.STORED, isCan = false)
        assertEquals(listOf("P0301" to "10", "P0700" to "18"), d.map { it.code to it.ecu })
    }

    @Test fun clearResponse() {
        val msgs = ObdFrames.assemble(lines("44\r"), ObdProtocol.CAN_11_500, false)
        assertTrue(ObdDecoders.isClearSuccess(msgs))
    }
}
