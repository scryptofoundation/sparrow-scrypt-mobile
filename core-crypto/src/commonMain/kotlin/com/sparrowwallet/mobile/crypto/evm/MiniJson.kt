package com.sparrowwallet.mobile.crypto.evm

/**
 * A JSON number as drongo's MiniJson reads it (a BigDecimal there): kept as the text BigDecimal.toPlainString() gives,
 * which is all the metadata reader shows of it.
 */
class JsonNumber internal constructor(private val plain: String) {
    fun toPlainString(): String = plain

    override fun equals(other: Any?): Boolean = other is JsonNumber && plain == other.plain

    override fun hashCode(): Int = plain.hashCode()

    override fun toString(): String = plain
}

/**
 * A small strict JSON reader for NFT metadata, ported from drongo's MiniJson so both read exactly the same. Objects
 * become LinkedHashMap, arrays List, numbers JsonNumber; strings, booleans and null as usual.
 *
 * Strict JSON (RFC 8259), with no use of the platform's Unicode data: whitespace is space, tab, line feed and carriage
 * return only; a \u escape is exactly four ASCII hex digits; numbers follow the JSON grammar (no leading +, no leading
 * zeros, digits on both sides of a point), at most 80 characters and a scale within 100; a raw character below U+0020
 * inside a string is refused. A repeated key keeps its last value, in the place of its first. Nesting deeper than 64
 * is refused, so the recursion is bounded.
 */
internal class MiniJson private constructor(private val s: String) {
    /** A parsed value, which may itself be null (JSON null). */
    class Parsed(val value: Any?)

    private class Malformed : RuntimeException()

    private var pos = 0
    private var depth = 0

    companion object {
        private const val MAX_DEPTH = 64
        private const val MAX_NUMBER_LENGTH = 80
        private const val MAX_SCALE = 100

        /** The value of this JSON text, or null when it is not strict JSON within the bounds. Never throws. */
        fun parse(json: String): Parsed? {
            val parser = MiniJson(json)
            return try {
                val value = parser.value()
                parser.skipWhitespace()
                if(parser.pos != json.length) null else Parsed(value)
            } catch(e: Malformed) {
                null
            }
        }
    }

    private fun value(): Any? {
        skipWhitespace()
        if(pos >= s.length) {
            throw Malformed()
        }
        val c = s[pos]
        if((c == '{' || c == '[') && ++depth > MAX_DEPTH) {
            throw Malformed()
        }
        return when(c) {
            '{' -> try { obj() } finally { depth-- }
            '[' -> try { array() } finally { depth-- }
            '"' -> string()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> number()
        }
    }

    private fun obj(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        pos++
        skipWhitespace()
        if(peek('}')) {
            pos++
            return map
        }
        while(true) {
            skipWhitespace()
            val key = string()
            skipWhitespace()
            consume(':')
            map[key] = value()
            skipWhitespace()
            if(peek(',')) {
                pos++
                continue
            }
            consume('}')
            return map
        }
    }

    private fun array(): List<Any?> {
        val list = ArrayList<Any?>()
        pos++
        skipWhitespace()
        if(peek(']')) {
            pos++
            return list
        }
        while(true) {
            list.add(value())
            skipWhitespace()
            if(peek(',')) {
                pos++
                continue
            }
            consume(']')
            return list
        }
    }

    private fun string(): String {
        consume('"')
        val sb = StringBuilder()
        while(pos < s.length) {
            val c = s[pos++]
            if(c == '"') {
                return sb.toString()
            }
            if(c == '\\') {
                if(pos >= s.length) {
                    break
                }
                when(val e = s[pos++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if(pos + 4 > s.length) {
                            throw Malformed()
                        }
                        var code = 0
                        for(i in 0 until 4) {
                            val digit = hexDigit(s[pos + i])
                            if(digit < 0) {
                                throw Malformed()
                            }
                            code = (code shl 4) or digit
                        }
                        sb.append(code.toChar())
                        pos += 4
                    }
                    else -> throw Malformed()
                }
            } else if(c.code < 0x20) {
                throw Malformed()
            } else {
                sb.append(c)
            }
        }
        throw Malformed()
    }

    //-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?
    private fun number(): JsonNumber {
        val start = pos
        val negative = peek('-')
        if(negative) {
            pos++
        }
        val intStart = pos
        if(peek('0')) {
            pos++
        } else if(pos < s.length && s[pos] in '1'..'9') {
            digits()
        } else {
            throw Malformed()
        }
        val intEnd = pos
        var fracStart = pos
        var fracEnd = pos
        if(peek('.')) {
            pos++
            fracStart = pos
            if(digits() == 0) {
                throw Malformed()
            }
            fracEnd = pos
        }
        var exponent = 0L
        if(peek('e') || peek('E')) {
            pos++
            var expNegative = false
            if(peek('+') || peek('-')) {
                expNegative = s[pos] == '-'
                pos++
            }
            val expStart = pos
            if(digits() == 0) {
                throw Malformed()
            }
            for(i in expStart until pos) {
                //Saturates far beyond any scale that is accepted
                exponent = minOf(exponent * 10 + (s[i] - '0'), 1_000_000_000_000L)
            }
            if(expNegative) {
                exponent = -exponent
            }
        }
        if(pos - start > MAX_NUMBER_LENGTH) {
            throw Malformed()
        }
        val fraction = fracEnd - fracStart
        val scale = fraction - exponent
        if(scale > MAX_SCALE || scale < -MAX_SCALE) {
            throw Malformed()
        }
        return JsonNumber(plain(negative, s.substring(intStart, intEnd) + s.substring(fracStart, fracEnd), scale.toInt()))
    }

    //BigDecimal.toPlainString of the unscaled digits and scale
    private fun plain(negative: Boolean, digits: String, scale: Int): String {
        var first = 0
        while(first < digits.length - 1 && digits[first] == '0') {
            first++
        }
        val unscaled = digits.substring(first)
        val zero = unscaled == "0"
        val sign = if(negative && !zero) "-" else ""
        if(scale == 0) {
            return sign + unscaled
        }
        if(scale < 0) {
            return if(zero) "0" else sign + unscaled + "0".repeat(-scale)
        }
        val point = unscaled.length - scale
        return when {
            point == 0 -> sign + "0." + unscaled
            point > 0 -> sign + unscaled.substring(0, point) + "." + unscaled.substring(point)
            else -> sign + "0." + "0".repeat(-point) + unscaled
        }
    }

    private fun expect(word: String) {
        if(!s.startsWith(word, pos)) {
            throw Malformed()
        }
        pos += word.length
    }

    private fun consume(c: Char) {
        skipWhitespace()
        if(!peek(c)) {
            throw Malformed()
        }
        pos++
    }

    private fun peek(c: Char): Boolean = pos < s.length && s[pos] == c

    private fun digits(): Int {
        val start = pos
        while(pos < s.length && s[pos] in '0'..'9') {
            pos++
        }
        return pos - start
    }

    private fun hexDigit(c: Char): Int = when(c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun skipWhitespace() {
        while(pos < s.length) {
            val c = s[pos]
            if(c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return
            }
            pos++
        }
    }
}
