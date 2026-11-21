package com.diagnosepro.app.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinDecoderTest {
    @Test fun northAmericanVin() {
        val i = VinDecoder.decode("1G1JC5444R7252367", 2026)!!
        assertEquals("Chevrolet", i.manufacturer)
        assertEquals("us", i.countryKey)
        assertEquals(1994, i.modelYear) // R, position 7 digit → 1994
        assertEquals(true, i.checkDigitValid)
    }

    @Test fun invalidCheckDigit() = assertEquals(false, VinDecoder.decode("1G1JC5445R7252367", 2026)!!.checkDigitValid)

    @Test fun europeanVin() {
        val i = VinDecoder.decode("WVWZZZ1KZ9W123456", 2026)!!
        assertEquals("Volkswagen", i.manufacturer)
        assertEquals("de", i.countryKey)
        assertEquals(2009, i.modelYear)
    }

    @Test fun japaneseAndKorean() {
        assertEquals("Toyota", VinDecoder.decode("JTDKB20U093123456", 2026)!!.manufacturer)
        assertEquals("jp", VinDecoder.decode("JTDKB20U093123456", 2026)!!.countryKey)
        assertEquals("kr", VinDecoder.decode("KMHCT41BAEU123456", 2026)!!.countryKey)
        assertEquals(2014, VinDecoder.decode("KMHCT41BAEU123456", 2026)!!.modelYear)
    }

    @Test fun recentYearCycle() {
        // 'R' = 1994 or 2024 — for a non-NA VIN pick the latest not in the future
        assertEquals(2024, VinDecoder.decode("WVWZZZAUZRW123456", 2026)!!.modelYear)
    }

    @Test fun rejectsInvalid() {
        assertNull(VinDecoder.decode("123"))
        assertFalse(VinDecoder.isValid("1G1JC5444R72523I7")) // I not allowed
        assertTrue(VinDecoder.isValid("1G1JC5444R7252367"))
    }
}
