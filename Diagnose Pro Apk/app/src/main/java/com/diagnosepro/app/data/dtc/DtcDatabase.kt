package com.diagnosepro.app.data.dtc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class Severity { LOW, MEDIUM, HIGH, STOP }

@Serializable
data class DtcEntry(
    val en: String,
    val he: String,
    val sev: String? = null,
    @SerialName("causes_en") val causesEn: List<String> = emptyList(),
    @SerialName("causes_he") val causesHe: List<String> = emptyList(),
    @SerialName("tip_en") val tipEn: String? = null,
    @SerialName("tip_he") val tipHe: String? = null,
)

@Serializable
data class DtcGroup(val prefix: String, val en: String, val he: String, val sev: String? = null)

@Serializable
data class DtcDbFile(val version: Int = 1, val codes: Map<String, DtcEntry> = emptyMap(), val groups: List<DtcGroup> = emptyList())

/** Localized information about one code, ready for display. */
data class DtcInfo(
    val code: String,
    val description: String?,
    val causes: List<String>,
    val tip: String?,
    val severity: Severity,
    /** P1xxx, P30xx-P33xx, B1/B2, C1/C2, U1/U2 — defined by each car maker. */
    val manufacturerSpecific: Boolean,
    /** True when we only know the code's group, not its exact meaning. */
    val groupOnly: Boolean,
    val groupDescription: String?,
)

/** Pure lookup logic (no Android), so it can be unit-tested against the real JSON asset. */
class DtcLookup(private val db: DtcDbFile) {

    val size get() = db.codes.size

    fun lookup(codeRaw: String, hebrew: Boolean): DtcInfo {
        val code = codeRaw.uppercase()
        val entry = db.codes[code]
        val group = db.groups.filter { code.startsWith(it.prefix) }.maxByOrNull { it.prefix.length }
        val manufacturer = isManufacturerSpecific(code)
        val sev = parseSeverity(entry?.sev ?: group?.sev) ?: if (manufacturer) Severity.MEDIUM else Severity.MEDIUM
        return DtcInfo(
            code = code,
            description = entry?.let { if (hebrew) it.he else it.en },
            causes = entry?.let { if (hebrew) it.causesHe else it.causesEn } ?: emptyList(),
            tip = entry?.let { if (hebrew) it.tipHe else it.tipEn },
            severity = sev,
            manufacturerSpecific = manufacturer && entry == null,
            groupOnly = entry == null && !manufacturer,
            groupDescription = group?.let { if (hebrew) it.he else it.en },
        )
    }

    /** Offline search by code prefix or by words in the description (current language). */
    fun search(query: String, hebrew: Boolean, limit: Int = 60): List<DtcInfo> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val upper = q.uppercase()
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return db.codes.asSequence().filter { (code, e) ->
            code.startsWith(upper) || (if (hebrew) e.he else e.en).lowercase().let { d -> words.all { d.contains(it) } }
        }.sortedBy { it.key }.take(limit).map { lookup(it.key, hebrew) }.toList()
    }

    companion object {
        fun parseSeverity(s: String?): Severity? = when (s?.lowercase()) {
            "low" -> Severity.LOW; "medium" -> Severity.MEDIUM; "high" -> Severity.HIGH; "stop" -> Severity.STOP
            else -> null
        }

        /** SAE J2012: second char 1 (and P3000-P33FF) are manufacturer controlled. */
        fun isManufacturerSpecific(code: String): Boolean {
            if (code.length != 5) return true
            val d1 = code[1]
            return when (code[0]) {
                'P' -> d1 == '1' || (d1 == '3' && code[2] in '0'..'3')
                'B', 'C', 'U' -> d1 == '1' || d1 == '2'
                else -> true
            }
        }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): DtcDbFile = json.decodeFromString(DtcDbFile.serializer(), text)
    }
}

/** Loads the bundled offline database from assets/dtc_db.json on first use. */
@Singleton
class DtcDatabase @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var lookup: DtcLookup? = null

    suspend fun get(): DtcLookup = lookup ?: mutex.withLock {
        lookup ?: withContext(Dispatchers.IO) {
            val db = runCatching {
                context.assets.open("dtc_db.json").bufferedReader(Charsets.UTF_8).use { DtcLookup.parse(it.readText()) }
            }.getOrElse { DtcDbFile() }
            DtcLookup(db).also { lookup = it }
        }
    }
}
