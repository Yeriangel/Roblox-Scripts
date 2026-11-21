package com.diagnosepro.app.obd

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tiny arithmetic expression evaluator for user-defined PID formulas, e.g. "(A*256+B)/4-40".
 *
 * Variables A..H are the response data bytes (0-255). Supports + - * / % , parentheses,
 * unary minus, bit operators & | ^ << >> (on integers), and functions min, max, abs,
 * signed(x) (8-bit two's complement) and signed16(x).
 * Recursive descent; no reflection or scripting engine, so a formula can never run code.
 */
class Expression private constructor(private val root: Node) {

    class ParseException(message: String) : Exception(message)

    fun eval(bytes: IntArray): Double = root.eval(bytes)

    /** Highest byte index used (0 for A) — tells how many data bytes the formula needs. */
    val bytesNeeded: Int get() = root.maxVar() + 1

    private sealed interface Node {
        fun eval(b: IntArray): Double
        fun maxVar(): Int
    }

    private class Num(val v: Double) : Node {
        override fun eval(b: IntArray) = v
        override fun maxVar() = -1
    }

    private class Var(val i: Int) : Node {
        override fun eval(b: IntArray) = if (i < b.size) b[i].toDouble() else throw ArithmeticException("missing byte")
        override fun maxVar() = i
    }

    private class Bin(val op: String, val l: Node, val r: Node) : Node {
        override fun eval(b: IntArray): Double {
            val x = l.eval(b); val y = r.eval(b)
            return when (op) {
                "+" -> x + y; "-" -> x - y; "*" -> x * y
                "/" -> if (y == 0.0) throw ArithmeticException("divide by zero") else x / y
                "%" -> if (y == 0.0) throw ArithmeticException("divide by zero") else x % y
                "&" -> (x.toLong() and y.toLong()).toDouble()
                "|" -> (x.toLong() or y.toLong()).toDouble()
                "^" -> (x.toLong() xor y.toLong()).toDouble()
                "<<" -> (x.toLong() shl y.toInt().coerceIn(0, 62)).toDouble()
                ">>" -> (x.toLong() shr y.toInt().coerceIn(0, 62)).toDouble()
                else -> throw ArithmeticException(op)
            }
        }
        override fun maxVar() = max(l.maxVar(), r.maxVar())
    }

    private class Fn(val name: String, val args: List<Node>) : Node {
        override fun eval(b: IntArray): Double {
            val a = args.map { it.eval(b) }
            return when (name) {
                "min" -> min(a[0], a[1]); "max" -> max(a[0], a[1]); "abs" -> abs(a[0])
                "signed" -> a[0].toLong().let { if (it > 127) it - 256 else it }.toDouble()
                "signed16" -> a[0].toLong().let { if (it > 32767) it - 65536 else it }.toDouble()
                else -> throw ArithmeticException(name)
            }
        }
        override fun maxVar() = args.maxOfOrNull { it.maxVar() } ?: -1
    }

    private class Parser(val s: String) {
        var i = 0
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun eat(t: String): Boolean { ws(); return if (s.startsWith(t, i)) { i += t.length; true } else false }

        fun parse(): Node {
            val n = bitOr(); ws()
            if (i < s.length) throw ParseException("Unexpected '${s[i]}' at ${i + 1}")
            return n
        }

        // precedence (low → high): |  ^  &  << >>  + -  * / %  unary  primary
        private fun bitOr(): Node { var n = bitXor(); while (eat("|")) n = Bin("|", n, bitXor()); return n }
        private fun bitXor(): Node { var n = bitAnd(); while (eat("^")) n = Bin("^", n, bitAnd()); return n }
        private fun bitAnd(): Node { var n = shift(); while (eat("&")) n = Bin("&", n, shift()); return n }
        private fun shift(): Node {
            var n = sum()
            while (true) n = if (eat("<<")) Bin("<<", n, sum()) else if (eat(">>")) Bin(">>", n, sum()) else return n
        }
        private fun sum(): Node {
            var n = product()
            while (true) n = if (eat("+")) Bin("+", n, product()) else if (eat("-")) Bin("-", n, product()) else return n
        }
        private fun product(): Node {
            var n = unary()
            while (true) n = if (eat("*")) Bin("*", n, unary()) else if (eat("/")) Bin("/", n, unary()) else if (eat("%")) Bin("%", n, unary()) else return n
        }
        private fun unary(): Node = if (eat("-")) Bin("-", Num(0.0), unary()) else if (eat("+")) unary() else primary()

        private fun primary(): Node {
            ws()
            if (i >= s.length) throw ParseException("Unexpected end of formula")
            if (eat("(")) { val n = bitOr(); if (!eat(")")) throw ParseException("Missing ')'"); return n }
            val c = s[i]
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return Num(s.substring(start, i).toDoubleOrNull() ?: throw ParseException("Bad number"))
            }
            if (c.isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit())) i++
                val word = s.substring(start, i)
                if (word.length == 1 && word[0].uppercaseChar() in 'A'..'H') return Var(word[0].uppercaseChar() - 'A')
                val name = word.lowercase()
                val arity = mapOf("min" to 2, "max" to 2, "abs" to 1, "signed" to 1, "signed16" to 1)[name]
                    ?: throw ParseException("Unknown name '$word'")
                if (!eat("(")) throw ParseException("Expected '(' after $word")
                val args = mutableListOf(bitOr())
                while (eat(",")) args += bitOr()
                if (!eat(")")) throw ParseException("Missing ')'")
                if (args.size != arity) throw ParseException("$word needs $arity argument(s)")
                return Fn(name, args)
            }
            throw ParseException("Unexpected '$c' at ${i + 1}")
        }
    }

    companion object {
        /** @throws ParseException with a readable reason. */
        fun parse(text: String): Expression {
            if (text.isBlank()) throw ParseException("Formula is empty")
            if (text.length > 200) throw ParseException("Formula is too long")
            return Expression(Parser(text).parse())
        }
    }
}

/** A user-defined PID, turned into a [PidDef] so it works in gauges, graphs, alarms and logging. */
object CustomPid {
    /** Only read services are allowed: Mode 01 (current data) and Mode 21/22 (read data by id). */
    private val commandRe = Regex("^(01[0-9A-F]{2}|21[0-9A-F]{2}|22[0-9A-F]{4})$")

    fun normalizeCommand(cmd: String): String? = cmd.replace(" ", "").uppercase().takeIf { commandRe.matches(it) }

    fun key(id: Long) = "custom_$id"

    private val headerRe = Regex("^([0-7][0-9A-F]{2}|DA[0-9A-F]{2}F1)$")

    /** Module address for a custom PID: 11-bit id ("7B0") or 29-bit physical ("DA28F1"). Blank = default. */
    fun normalizeHeader(h: String?): String? = h?.replace(" ", "")?.uppercase()?.takeIf { headerRe.matches(it) }
    fun isHeaderValid(h: String) = h.isBlank() || normalizeHeader(h) != null

    fun toDef(id: Long, name: String, command: String, formula: String, unit: String, min: Double, max: Double, header: String? = null): PidDef? {
        val cmd = normalizeCommand(command) ?: return null
        val expr = runCatching { Expression.parse(formula) }.getOrNull() ?: return null
        return PidDef(
            pid = -1000 - id.toInt(), key = key(id), bytes = expr.bytesNeeded, quantity = Quantity.RAW,
            gaugeMin = min, gaugeMax = max, priority = PollPriority.MEDIUM, decimals = 1,
            customCommand = cmd, customName = name, customUnit = unit, customHeader = normalizeHeader(header),
        ) { expr.eval(it) }
    }

    /**
     * Extracts data bytes from a positive response: service+0x40 followed by the echoed id bytes.
     * e.g. request 221234 → "62 12 34 A B ..."; request 0105 → "41 05 A".
     */
    fun dataBytes(command: String, response: IntArray): IntArray? {
        val req = command.chunked(2).map { it.toInt(16) }
        if (response.size < req.size || response[0] != req[0] + 0x40) return null
        for (i in 1 until req.size) if (response[i] != req[i]) return null
        return response.copyOfRange(req.size, response.size)
    }
}

/**
 * Guards the developer terminal: only adapter (AT) commands that do not reprogram the adapter
 * and read-only OBD services are allowed. Anything that could write to an ECU is refused.
 */
object TerminalGuard {
    private val readOnlyModes = setOf("01", "02", "03", "06", "07", "09", "0A", "21", "22")
    // ATPP* writes adapter EEPROM, AT@3 overwrites the device id, ATSD saves data byte.
    private val blockedAt = listOf("ATPP", "AT@3", "ATSD", "ATWM", "ATBRD", "ATIB", "ATFE")

    fun isAllowed(raw: String): Boolean {
        val c = raw.replace(" ", "").uppercase()
        if (c.isEmpty() || c.length > 32) return false
        if (c.startsWith("AT")) return blockedAt.none { c.startsWith(it) } && c.all { it.isLetterOrDigit() || it == '@' }
        if (!c.all { it.isDigit() || it in 'A'..'F' } || c.length < 2 || c.length % 2 != 0) return false
        return c.substring(0, 2) in readOnlyModes
    }
}
