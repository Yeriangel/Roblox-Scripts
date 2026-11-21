package com.diagnosepro.app.data

import com.diagnosepro.app.data.dtc.DtcLookup
import com.diagnosepro.app.data.dtc.Severity
import com.diagnosepro.app.obd.Dtc
import com.diagnosepro.app.obd.DtcKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Validates the bundled offline DTC database asset. */
class DtcDatabaseTest {
    private val db by lazy { DtcLookup(DtcLookup.parse(File("src/main/assets/dtc_db.json").readText())) }

    @Test fun loadsAndHasCoverage() {
        assertTrue("expected > 2000 codes, got ${db.size}", db.size > 2000)
    }

    @Test fun bilingualDescriptions() {
        val he = db.lookup("P0301", hebrew = true)
        val en = db.lookup("P0301", hebrew = false)
        assertEquals("תקלת הצתה בצילינדר 1", he.description)
        assertTrue(en.description!!.contains("Misfire", ignoreCase = true))
        assertTrue(he.causes.isNotEmpty())
        assertEquals(he.causes.size, en.causes.size)
        assertNotNull(he.tip)
        assertEquals(Severity.HIGH, he.severity)
    }

    @Test fun commonCodesHaveDetails() {
        for (c in listOf("P0420", "P0171", "P0128", "P0300", "P0455", "P0700", "U0100")) {
            val i = db.lookup(c, true)
            assertNotNull("$c description", i.description)
            assertTrue("$c causes", i.causes.isNotEmpty())
            assertTrue("$c Hebrew", i.description!!.any { ch -> ch in '֐'..'׿' })
        }
    }

    @Test fun manufacturerSpecific() {
        val i = db.lookup("P1234", true)
        assertTrue(i.manufacturerSpecific)
        assertNull(i.description)
        assertTrue(DtcLookup.isManufacturerSpecific("P3000"))
        assertFalse(DtcLookup.isManufacturerSpecific("P3400"))
        assertTrue(DtcLookup.isManufacturerSpecific("B1001"))
        assertTrue(DtcLookup.isManufacturerSpecific("U2100"))
        assertFalse(DtcLookup.isManufacturerSpecific("C0035"))
    }

    @Test fun unknownGenericFallsBackToGroup() {
        val i = db.lookup("P0FFF", true)
        assertTrue(i.groupOnly)
    }

    @Test fun offlineSearch() {
        assertTrue(db.search("P030", false).any { it.code == "P0301" })
        assertTrue(db.search("misfire", false).isNotEmpty())
        assertTrue(db.search("הצתה", true).any { it.code == "P0301" })
        assertTrue(db.search("x", false).isEmpty())
        assertTrue(db.search("zzzzqq", false).isEmpty())
    }

    @Test fun dtcJsonRoundTrip() {
        val list = listOf(Dtc("P0301", DtcKind.STORED, "7E8"), Dtc("P0420", DtcKind.PERMANENT, "7E8"))
        assertEquals(list, DtcJson.decode(DtcJson.encode(list)))
        assertTrue(DtcJson.decode("garbage").isEmpty())
    }
}
