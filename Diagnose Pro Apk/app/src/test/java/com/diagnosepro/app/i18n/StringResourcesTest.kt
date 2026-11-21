package com.diagnosepro.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fails the build if any language is missing a string, has an extra one, or uses
 * different format placeholders (%1$s / %1$d) than English.
 */
class StringResourcesTest {
    private val res = File("src/main/res")

    private data class Entry(val text: String, val pluralQuantities: Set<String> = emptySet())

    private fun load(dir: String): Map<String, Entry> {
        val f = File(res, "$dir/strings.xml")
        assertTrue("missing ${f.path}", f.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = LinkedHashMap<String, Entry>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            out[e.getAttribute("name")] = Entry(e.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val e = plurals.item(i) as Element
            val items = e.getElementsByTagName("item")
            val q = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
            val other = (0 until items.length).map { items.item(it) as Element }.first { it.getAttribute("quantity") == "other" }.textContent
            out["plurals:" + e.getAttribute("name")] = Entry(other, q)
        }
        return out
    }

    private val placeholder = Regex("%\\d\\$[sd]")

    @Test fun hebrewHasExactlyTheSameKeys() {
        val en = load("values")
        for (dir in listOf("values-he", "values-iw")) {
            val he = load(dir)
            assertEquals("Missing in $dir", emptySet<String>(), en.keys - he.keys)
            assertEquals("Extra in $dir", emptySet<String>(), he.keys - en.keys)
            for ((k, v) in en) {
                val a = placeholder.findAll(v.text).map { it.value }.toSet()
                val b = placeholder.findAll(he.getValue(k).text).map { it.value }.toSet()
                assertEquals("Placeholders differ for $k in $dir", a, b)
            }
        }
    }

    @Test fun hebrewPluralsCoverHebrewQuantities() {
        val he = load("values-he")
        he.filterKeys { it.startsWith("plurals:") }.forEach { (k, v) ->
            assertTrue("$k must define one/two/other", v.pluralQuantities.containsAll(setOf("one", "two", "other")))
        }
    }

    @Test fun hebrewFilesAreIdentical() {
        assertEquals(File(res, "values-he/strings.xml").readText(), File(res, "values-iw/strings.xml").readText())
    }

    @Test fun hebrewStringsAreActuallyHebrew() {
        val he = load("values-he")
        val latinOnlyAllowed = setOf("not_available", "lang_english", "type_ble", "mon_nox_scr", "mon_egr_vvt", "ecu_abs")
        val untranslated = he.filter { (k, v) -> k !in latinOnlyAllowed && v.text.none { it in '֐'..'׿' } }.keys
        assertEquals("Strings without Hebrew letters", emptySet<String>(), untranslated)
    }

    @Test fun noHardcodedLeftRightInLayoutsCode() {
        // Compose code must use start/end; flag absolute paddings/alignments.
        val src = File("src/main/java")
        val bad = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (Regex("\\b(padding\\(left|padding\\(right|Alignment\\.(Left|Right)|TextAlign\\.(Left|Right)|absolutePadding)").containsMatchIn(line)) "${f.name}:${i + 1}" else null
            }
        }.toList()
        assertEquals(emptyList<String>(), bad)
    }
}
