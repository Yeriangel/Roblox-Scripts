package com.diagnosepro.app.obd

/** Kind of control module, used to pick a localized name. */
enum class ModuleKind { ENGINE, TRANSMISSION, ABS, STEERING, SUSPENSION, AIRBAG, CLUSTER, BODY, HVAC, GATEWAY, LIGHTING, ENTERTAINMENT, SECURITY, UNKNOWN }

data class UdsDtc(val code: String, val failureType: Int, val status: Int) {
    /** Confirmed or currently failing = current fault; otherwise only pending. */
    val current: Boolean get() = status and 0x09 != 0
}

/** One module found by the full-system scan. [dtcs] is null when the module refused the DTC request. */
data class ModuleResult(val requestId: String, val address: String, val kind: ModuleKind, val dtcs: List<UdsDtc>?)

/**
 * Standard UDS (ISO 14229) helpers for the full-system scan. Only read services are used:
 * 0x10 01 (default session, used as a "ping") and 0x19 02 (read DTCs by status mask).
 */
object Uds {
    /** Status mask: testFailed (0x01) | pending (0x04) | confirmed (0x08). */
    const val STATUS_MASK = 0x0D

    /**
     * "59 02 <availabilityMask> [DTC hi, DTC mid, failure type, status]..." → DTC list.
     * The first two DTC bytes use the same P/C/B/U encoding as OBD; the third is the failure type.
     */
    fun decodeDtcs(bytes: IntArray): List<UdsDtc>? {
        if (bytes.size < 3 || bytes[0] != 0x59 || bytes[1] != 0x02) return null
        val out = mutableListOf<UdsDtc>()
        var i = 3
        while (i + 3 < bytes.size) {
            val hi = bytes[i]; val mid = bytes[i + 1]; val ft = bytes[i + 2]; val st = bytes[i + 3]
            if ((hi != 0 || mid != 0 || ft != 0) && st and STATUS_MASK != 0) out += UdsDtc(ObdDecoders.dtcFromBytes(hi, mid), ft, st)
            i += 4
        }
        return out.distinctBy { it.code to it.failureType }
    }

    /** Standard identification data ids (ISO 14229 Annex C). */
    val IDENT_DIDS = listOf(0xF197, 0xF187, 0xF18C, 0xF195, 0xF189, 0xF191, 0xF190)

    /** ASCII if the data is text, otherwise hex. Null for empty / all-0x00 / all-0xFF data. */
    fun printable(data: IntArray): String? {
        val trimmed = data.dropLastWhile { it == 0x00 || it == 0xFF || it == 0x20 }
        if (trimmed.isEmpty()) return null
        return if (trimmed.all { it in 0x20..0x7E }) trimmed.map { it.toChar() }.joinToString("").trim()
        else trimmed.joinToString(" ") { "%02X".format(it) }
    }

    /** Request targets to probe. 11-bit: 0x700-0x7E7 (without the 0x7DF broadcast id); 29-bit: every physical address. */
    fun probeTargets(extended: Boolean): List<String> =
        if (extended) (0x00..0xFF).filter { it != 0xF1 }.map { "DA%02XF1".format(it) }
        else ((0x700..0x7DE) + (0x7E0..0x7E7)).map { "%03X".format(it) }

    /**
     * Best-effort module name.
     *  - 29-bit CAN uses SAE J2178 address ranges, which are standardized.
     *  - 11-bit CAN ids are manufacturer-specific; only well-known ids of a few makes are named,
     *    everything else is reported by address.
     */
    fun moduleKind(requestId: String, address: String, extended: Boolean, manufacturer: String?): ModuleKind {
        if (extended) {
            return when (address.toIntOrNull(16) ?: return ModuleKind.UNKNOWN) {
                in 0x10..0x17 -> ModuleKind.ENGINE
                in 0x18..0x1F -> ModuleKind.TRANSMISSION
                in 0x28..0x2F -> ModuleKind.ABS
                in 0x30..0x37 -> ModuleKind.STEERING
                in 0x38..0x3F -> ModuleKind.SUSPENSION
                in 0x58..0x5F -> ModuleKind.AIRBAG
                in 0x60..0x6F -> ModuleKind.CLUSTER
                in 0x70..0x7F -> ModuleKind.LIGHTING
                in 0x80..0x97 -> ModuleKind.ENTERTAINMENT
                in 0x98..0x9F -> ModuleKind.HVAC
                in 0xC0..0xC7 -> ModuleKind.SECURITY
                in 0x40..0xC7 -> ModuleKind.BODY
                else -> ModuleKind.UNKNOWN
            }
        }
        val id = requestId.toIntOrNull(16) ?: return ModuleKind.UNKNOWN
        if (id == 0x7E0) return ModuleKind.ENGINE
        if (id == 0x7E1) return ModuleKind.TRANSMISSION
        val m = manufacturer.orEmpty().lowercase()
        val table: Map<Int, ModuleKind> = when {
            listOf("ford", "mazda", "lincoln").any { m.contains(it) } -> mapOf(
                0x760 to ModuleKind.ABS, 0x737 to ModuleKind.AIRBAG, 0x726 to ModuleKind.BODY, 0x720 to ModuleKind.CLUSTER, 0x730 to ModuleKind.STEERING, 0x733 to ModuleKind.HVAC)
            listOf("volkswagen", "audi", "seat", "škoda", "skoda").any { m.contains(it) } -> mapOf(
                0x713 to ModuleKind.ABS, 0x715 to ModuleKind.AIRBAG, 0x714 to ModuleKind.CLUSTER, 0x70E to ModuleKind.BODY, 0x710 to ModuleKind.GATEWAY, 0x712 to ModuleKind.STEERING, 0x746 to ModuleKind.HVAC)
            listOf("toyota", "lexus").any { m.contains(it) } -> mapOf(
                0x7B0 to ModuleKind.ABS, 0x780 to ModuleKind.AIRBAG, 0x7C0 to ModuleKind.CLUSTER, 0x750 to ModuleKind.BODY, 0x7A1 to ModuleKind.STEERING, 0x7C4 to ModuleKind.HVAC)
            listOf("hyundai", "kia").any { m.contains(it) } -> mapOf(
                0x7D1 to ModuleKind.ABS, 0x7D2 to ModuleKind.AIRBAG, 0x7C6 to ModuleKind.CLUSTER, 0x7A0 to ModuleKind.BODY, 0x7D4 to ModuleKind.STEERING, 0x7B3 to ModuleKind.HVAC)
            else -> emptyMap()
        }
        return table[id] ?: ModuleKind.UNKNOWN
    }
}
