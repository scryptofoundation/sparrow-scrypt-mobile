package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.sha256
import com.sparrowwallet.mobile.crypto.toHex

/**
 * Electrum protocol primitives that are pure (no I/O), so they can be unit-tested
 * without a server. The scripthash mapping is the address→server-query key and must
 * match desktop drongo exactly (ElectrumServer.getScriptHash):
 *   scripthash = hex(reverse(SHA256(scriptPubKey)))
 */
object ElectrumProtocol {
    fun scriptHash(scriptPubKey: ByteArray): String = sha256(scriptPubKey).reversedArray().toHex()

    /**
     * Parses a decimal LTC amount string (as found in verbose transaction JSON, e.g.
     * "0.05", "1.23456789", or "7.166e-05" from servers that print Python floats) into
     * litoshis without any floating-point rounding. An output value is never negative, and one past the Long range
     * throws rather than wrapping.
     */
    fun ltcToLitoshis(decimal: String): Long {
        require(!decimal.startsWith("-")) { "Not an LTC amount: $decimal" }
        val s = expandExponent(decimal, decimal)
        require(s.isNotEmpty() && s.all { it.isDigit() || it == '.' }) { "Not a decimal amount: $decimal" }
        val parts = s.split(".")
        require(parts.size <= 2) { "Not an LTC amount: $decimal" }
        var fraction = parts.getOrNull(1) ?: ""
        //A server that sums in floating point (Fulcrum) can print 0.0009971800000000001 for 99718 litoshis: digits past the
        //eighth that sit within 1/10000 of a litoshi of a whole one are that noise and are rounded; a real sub-litoshi
        //amount (0.000000015) is still refused
        var carry = 0L
        if(fraction.length > 8) {
            val excess = fraction.substring(8)
            require(excess.all { it.isDigit() }) { "Not an LTC amount: $decimal" }
            carry = when {
                excess.startsWith("0000") -> 0L
                excess.startsWith("9999") -> 1L
                else -> throw IllegalArgumentException("Not an LTC amount: $decimal")
            }
            fraction = fraction.substring(0, 8)
        }
        //Twenty digits or more cannot fit (toLong would throw a NumberFormatException, an IllegalArgumentException)
        require(parts[0].length <= 19) { "Not an LTC amount: $decimal" }
        val whole = if(parts[0].isEmpty()) 0L else parts[0].toLong()
        val frac = fraction.padEnd(8, '0').toLong()
        //Bound checks rather than Math.multiplyExact, which common code does not have
        require(whole <= Long.MAX_VALUE / UNIT) { "Not an LTC amount: $decimal" }
        val base = whole * UNIT
        require(frac + carry <= Long.MAX_VALUE - base) { "Not an LTC amount: $decimal" }
        return base + frac + carry
    }

    private const val UNIT = 100_000_000L

    /** "7.166e-05" to "0.00007166" by moving the decimal point; plain decimals pass through. */
    private fun expandExponent(s: String, original: String): String {
        val e = s.indexOfFirst { it == 'e' || it == 'E' }
        if(e < 0) return s
        val mantissa = s.substring(0, e)
        val exponent = s.substring(e + 1).removePrefix("+").toIntOrNull()
        require(exponent != null && mantissa.isNotEmpty() && mantissa.all { it.isDigit() || it == '.' } && mantissa.count { it == '.' } <= 1) {
            "Not a decimal amount: $original"
        }
        // server input: a real amount needs a small exponent, and a huge one would build a huge string
        require(exponent in -20..20 && mantissa.length <= 40) { "Not an LTC amount: $original" }
        val dot = mantissa.indexOf('.').let { if(it < 0) mantissa.length else it }
        val digits = mantissa.replace(".", "")
        val point = dot + exponent
        return when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
    }
}
