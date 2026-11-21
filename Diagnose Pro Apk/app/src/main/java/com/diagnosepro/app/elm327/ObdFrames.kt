package com.diagnosepro.app.elm327

/**
 * OBD protocols as numbered by the ELM327 ("ATDPN" answers e.g. "A6" = auto-detected 6).
 */
enum class ObdProtocol(val code: Char, val displayName: String, val isCan: Boolean, val canHeaderChars: Int) {
    AUTO('0', "Automatic", false, 0),
    J1850_PWM('1', "SAE J1850 PWM", false, 0),
    J1850_VPW('2', "SAE J1850 VPW", false, 0),
    ISO_9141_2('3', "ISO 9141-2", false, 0),
    KWP_5BAUD('4', "ISO 14230-4 KWP (5 baud init)", false, 0),
    KWP_FAST('5', "ISO 14230-4 KWP (fast init)", false, 0),
    CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", true, 3),
    CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", true, 8),
    CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", true, 3),
    CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", true, 8),
    J1939('A', "SAE J1939 CAN", true, 8),
    USER1('B', "User1 CAN", true, 3),
    USER2('C', "User2 CAN", true, 3);

    companion object {
        /** Parses "A6", "6", "AUTO, ISO 15765-4 (CAN 11/500)" style answers. */
        fun fromDpn(answer: String?): ObdProtocol? {
            val a = answer?.trim()?.uppercase()?.removePrefix("A") ?: return null
            if (a.isEmpty()) return null
            return entries.firstOrNull { it.code == a[0] }
        }
    }
}

/** One assembled message from one ECU, starting at the service byte (e.g. 0x41). */
data class EcuMessage(val ecu: String, val bytes: IntArray) {
    override fun equals(other: Any?) = other is EcuMessage && ecu == other.ecu && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * ecu.hashCode() + bytes.contentHashCode()
    override fun toString() = "EcuMessage($ecu, ${bytes.joinToString(" ") { "%02X".format(it) }})"
}

/**
 * Turns cleaned ELM327 response lines into per-ECU messages.
 *
 * Handles:
 *  - headers OFF (ATH0) and ON (ATH1), spaces ON or OFF (ATS0)
 *  - CAN single frames and ISO-TP multi-frame responses
 *    (headers off: "014" length line + "0: .." / "1: .." indexed lines;
 *     headers on : PCI bytes 0x / 1x xx / 2x)
 *  - legacy protocols (J1850, ISO 9141, KWP): 3 header bytes + checksum when headers are on;
 *    each line is its own message.
 */
object ObdFrames {

    private const val UNKNOWN_ECU = "ECU"

    fun assemble(lines: List<String>, protocol: ObdProtocol?, headers: Boolean): List<EcuMessage> {
        val hexLines = lines.map { it.replace(" ", "").uppercase() }.filter { it.isNotEmpty() }
        if (hexLines.isEmpty()) return emptyList()
        val isCan = protocol?.isCan ?: guessCan(lines, headers)
        return if (isCan) {
            if (headers) assembleCanWithHeaders(hexLines, protocol, lines) else assembleCanNoHeaders(hexLines)
        } else {
            assembleLegacy(hexLines, headers)
        }
    }

    /** When protocol is unknown, guess from the shape of the first line. */
    private fun guessCan(lines: List<String>, headers: Boolean): Boolean {
        val first = lines.first().trim()
        if (!headers) return true // format is the same for CAN and legacy in single-line cases
        val tok = first.split(" ").firstOrNull() ?: return true
        return tok.length == 3 || first.replace(" ", "").length % 2 == 1
    }

    // ---------- CAN, headers OFF ----------
    private fun assembleCanNoHeaders(lines: List<String>): List<EcuMessage> {
        val result = mutableListOf<EcuMessage>()
        var i = 0
        var ecuIndex = 1
        while (i < lines.size) {
            val line = lines[i]
            // ISO-TP: a 3-hex-digit length line followed by "0:" "1:" ... lines.
            if (line.length == 3 && !line.contains(':') && i + 1 < lines.size && lines[i + 1].contains(':')) {
                val total = line.toIntOrNull(16) ?: 0
                val data = mutableListOf<Int>()
                i++
                while (i < lines.size && lines[i].contains(':')) {
                    data += hexToBytes(lines[i].substringAfter(':'))
                    i++
                }
                result += EcuMessage("$UNKNOWN_ECU#${ecuIndex++}", data.take(total).toIntArray())
                continue
            }
            if (line.contains(':')) { // stray indexed line without length line
                result += EcuMessage("$UNKNOWN_ECU#${ecuIndex++}", hexToBytes(line.substringAfter(':')).toIntArray())
            } else {
                result += EcuMessage("$UNKNOWN_ECU#${ecuIndex++}", hexToBytes(line).toIntArray())
            }
            i++
        }
        return result
    }

    // ---------- CAN, headers ON ----------
    private fun assembleCanWithHeaders(lines: List<String>, protocol: ObdProtocol?, original: List<String>): List<EcuMessage> {
        val headerLen = protocol?.canHeaderChars?.takeIf { it > 0 }
            ?: if ((original.first().trim().split(" ").firstOrNull()?.length ?: 3) == 3) 3 else 8
        // Group frames by sender, keep arrival order of first appearance.
        val buffers = LinkedHashMap<String, MutableList<Int>>()
        val expected = HashMap<String, Int>()
        val done = mutableListOf<EcuMessage>()
        for (line in lines) {
            if (line.length <= headerLen) continue
            val header = line.substring(0, headerLen)
            val ecu = if (headerLen == 8) header.substring(6, 8) else header
            val payload = hexToBytes(line.substring(headerLen))
            if (payload.isEmpty()) continue
            val pci = payload[0]
            when (pci shr 4) {
                0x0 -> { // single frame: low nibble = length
                    val len = pci and 0x0F
                    done += EcuMessage(ecu, payload.drop(1).take(len).toIntArray())
                }
                0x1 -> { // first frame: 12-bit length
                    if (payload.size < 2) continue
                    val len = ((pci and 0x0F) shl 8) or payload[1]
                    expected[ecu] = len
                    buffers[ecu] = payload.drop(2).toMutableList()
                }
                0x2 -> { // consecutive frame
                    buffers[ecu]?.addAll(payload.drop(1))
                }
                else -> { // not ISO-TP framed (some clones) — keep raw
                    done += EcuMessage(ecu, payload.toIntArray())
                }
            }
        }
        for ((ecu, data) in buffers) {
            val len = expected[ecu] ?: data.size
            done += EcuMessage(ecu, data.take(len).toIntArray())
        }
        return done
    }

    // ---------- J1850 / ISO 9141 / KWP ----------
    private fun assembleLegacy(lines: List<String>, headers: Boolean): List<EcuMessage> {
        return lines.mapNotNull { line ->
            val bytes = hexToBytes(line)
            if (headers) {
                // [priority][target][source] data... [checksum]
                if (bytes.size < 5) return@mapNotNull null
                EcuMessage("%02X".format(bytes[2]), bytes.subList(3, bytes.size - 1).toIntArray())
            } else {
                // Without headers the sender is unknown; legacy multi-line answers (e.g. the
                // 5-message VIN) belong together, so they share one id.
                if (bytes.isEmpty()) null else EcuMessage(UNKNOWN_ECU, bytes.toIntArray())
            }
        }
    }

    fun hexToBytes(hex: String): List<Int> {
        val clean = hex.replace(" ", "").filter { it.isLetterOrDigit() }
        val out = ArrayList<Int>(clean.length / 2)
        var i = 0
        while (i + 1 < clean.length) {
            val b = clean.substring(i, i + 2).toIntOrNull(16) ?: return out
            out += b
            i += 2
        }
        return out
    }

    /** Human friendly ECU name for the common OBD addresses. */
    fun ecuDisplayKey(ecu: String): EcuRole = when (ecu.uppercase()) {
        "7E8", "10" -> EcuRole.ENGINE
        "7E9", "18", "1A" -> EcuRole.TRANSMISSION
        "7EA", "28" -> EcuRole.ABS
        else -> EcuRole.OTHER
    }
}

enum class EcuRole { ENGINE, TRANSMISSION, ABS, OTHER }
