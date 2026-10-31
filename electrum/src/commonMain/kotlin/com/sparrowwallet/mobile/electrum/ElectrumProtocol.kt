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
     * litoshis without any floating-point rounding.
     */
    fun ltcToLitoshis(decimal: String): Long {
        val negative = decimal.startsWith("-")
        val s = expandExponent(decimal.removePrefix("-"), decimal)
        require(s.isNotEmpty() && s.all { it.isDigit() || it == '.' }) { "Not a decimal amount: $decimal" }
        val parts = s.split(".")
        require(parts.size <= 2 && (parts.getOrNull(1)?.length ?: 0) <= 8) { "Not an LTC amount: $decimal" }
        val whole = if(parts[0].isEmpty()) 0L else parts[0].toLong()
        val frac = if(parts.size > 1) parts[1].padEnd(8, '0').toLong() else 0L
        val value = whole * 100_000_000L + frac
        return if(negative) -value else value
    }

    /** "7.166e-05" to "0.00007166" by moving the decimal point; plain decimals pass through. */
    private fun expandExponent(s: String, original: String): String {
        val e = s.indexOfFirst { it == 'e' || it == 'E' }
        if(e < 0) return s
        val mantissa = s.substring(0, e)
        val exponent = s.substring(e + 1).removePrefix("+").toIntOrNull()
        require(exponent != null && mantissa.isNotEmpty() && mantissa.all { it.isDigit() || it == '.' } && mantissa.count { it == '.' } <= 1) {
            "Not a decimal amount: $original"
        }
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
