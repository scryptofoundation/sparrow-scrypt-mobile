package com.sparrowwallet.mobile.crypto.evm

/**
 * The one rule for text that comes from a contract or its metadata: an NFT's name, description and attributes, a
 * collection's name and symbol, and the text of an SVG picture. Port of drongo's TextRules. It uses no platform Unicode
 * data, so the phone and the desktop give the same answer for every input.
 *
 * STRIPPED, a fixed table as of Unicode 15.1 copied code point for code point from drongo, is removed: C0 and C1
 * controls and DEL, U+2028 and U+2029, the variation selectors U+FE00..U+FE0F and U+E0100..U+E01EF, and every format
 * (Cf) character as of Unicode 15.1. The table is the rule, not any platform's Unicode data. A lone surrogate (half of a
 * pair) is removed too.
 *
 * Tab, line feed, carriage return and space separate words; they are not in the result as themselves.
 *   Single line: a run of separators between two kept characters becomes one space (a removed character inside the run
 *   does not end it), and there are none at the ends.
 *   Description: the same, except that a run holding a line feed or a carriage return becomes one line feed; from the
 *   20th line on, such a run becomes a space instead, so there are at most 20 lines.
 * A cap counts code points and is applied after cleaning; a separator left at the end of the cut is dropped, so the
 * result never ends in a space or a line feed and never holds half a surrogate pair.
 */
object TextRules {
    const val UNICODE_VERSION = "15.1"
    const val MAX_LINES = 20

    /** Inclusive ranges, see the class comment. */
    private val STRIPPED = intArrayOf(
        0x0000, 0x001F, 0x007F, 0x009F, 0x00AD, 0x00AD, 0x0600, 0x0605, 0x061C, 0x061C, 0x06DD, 0x06DD, 0x070F, 0x070F,
        0x0890, 0x0891, 0x08E2, 0x08E2, 0x180E, 0x180E, 0x200B, 0x200F, 0x2028, 0x202E, 0x2060, 0x206F, 0xFE00, 0xFE0F,
        0xFEFF, 0xFEFF, 0xFFF9, 0xFFFB, 0x110BD, 0x110BD, 0x110CD, 0x110CD, 0x13430, 0x1343F, 0x1BCA0, 0x1BCA3,
        0x1D173, 0x1D17A, 0xE0001, 0xE0001, 0xE0020, 0xE007F, 0xE0100, 0xE01EF)

    /** Whether this code point is in the fixed table. */
    fun stripped(c: Int): Boolean {
        for(i in STRIPPED.indices step 2) {
            if(c >= STRIPPED[i] && c <= STRIPPED[i + 1]) {
                return true
            }
        }
        return false
    }

    /** Whether this code point is a lone surrogate, as a string's code points give one. */
    fun loneSurrogate(c: Int): Boolean = c in 0xD800..0xDFFF

    /** The separators: tab, line feed, carriage return and space. */
    fun separator(c: Int): Boolean = c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D

    /** One line of text, at most maxCodePoints code points. */
    fun singleLine(text: String, maxCodePoints: Int): String = clean(text, maxCodePoints, 1)

    /** A description: at most 20 lines, at most maxCodePoints code points with the line feeds counted. */
    fun description(text: String, maxCodePoints: Int): String = clean(text, maxCodePoints, MAX_LINES)

    /** The code point at index i, as a UTF-16 string holds it (a lone surrogate as itself). */
    internal fun codePointOf(text: String, i: Int): Int {
        val high = text[i].code
        if(high in 0xD800..0xDBFF && i + 1 < text.length) {
            val low = text[i + 1].code
            if(low in 0xDC00..0xDFFF) {
                return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
            }
        }
        return high
    }

    internal fun charCount(c: Int): Int = if(c >= 0x10000) 2 else 1

    internal fun appendCodePoint(out: StringBuilder, c: Int) {
        if(c >= 0x10000) {
            out.append((0xD800 + ((c - 0x10000) ushr 10)).toChar())
            out.append((0xDC00 + ((c - 0x10000) and 0x3FF)).toChar())
        } else {
            out.append(c.toChar())
        }
    }

    private fun clean(text: String, maxCodePoints: Int, maxLines: Int): String {
        val kept = StringBuilder()
        var keptCodePoints = 0
        var space = false
        var lineBreak = false
        var lines = 1
        var i = 0
        while(i < text.length) {
            val c = codePointOf(text, i)
            i += charCount(c)
            if(c == 0x0A || c == 0x0D) {
                lineBreak = true
                continue
            }
            if(c == 0x20 || c == 0x09) {
                space = true
                continue
            }
            if(loneSurrogate(c) || stripped(c)) {
                continue
            }
            if(kept.isNotEmpty() && (space || lineBreak)) {
                if(lineBreak && lines < maxLines) {
                    kept.append('\n')
                    lines++
                } else {
                    kept.append(' ')
                }
                keptCodePoints++
            }
            space = false
            lineBreak = false
            appendCodePoint(kept, c)
            keptCodePoints++
        }
        if(keptCodePoints <= maxCodePoints) {
            return kept.toString()
        }
        //kept holds no lone surrogate, so a high surrogate always starts a pair
        var end = 0
        repeat(maxCodePoints) {
            end += if(kept[end].code in 0xD800..0xDBFF) 2 else 1
        }
        //Runs are already single, so at most one separator can end the cut
        if(end > 0 && (kept[end - 1] == ' ' || kept[end - 1] == '\n')) {
            end--
        }
        return kept.substring(0, end)
    }
}
