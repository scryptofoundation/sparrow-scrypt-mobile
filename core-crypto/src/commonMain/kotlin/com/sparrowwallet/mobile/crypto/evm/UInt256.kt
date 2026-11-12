package com.sparrowwallet.mobile.crypto.evm

/**
 * An unsigned 256-bit integer, the ABI's uint256: a token id, a balance in wei, a count. Common code has no big-number
 * type, so this holds just what the read-only LitVM port needs: reading and writing the 32-byte word, decimal and hex
 * text as the desktop's BigInteger.toString() and toString(16) give them, comparison, and the Vault Badge's
 * (owner << 8) | world. Immutable.
 */
class UInt256 private constructor(private val word: ByteArray) : Comparable<UInt256> {
    /** The 32-byte big-endian ABI word. */
    fun toWord(): ByteArray = word.copyOf()

    /** The number of bits without leading zeros, as BigInteger.bitLength() (0 for zero). */
    val bitLength: Int
        get() {
            for(i in 0 until WORD) {
                val b = word[i].toInt() and 0xFF
                if(b != 0) {
                    return (WORD - i) * 8 - (b.countLeadingZeroBits() - 24)
                }
            }
            return 0
        }

    /** this * 256 + low, or null when that needs more than 256 bits. */
    fun shl8Or(low: Int): UInt256? {
        require(low in 0..0xFF) { "Low byte out of range: $low" }
        if(word[0].toInt() != 0) {
            return null
        }
        val shifted = ByteArray(WORD)
        word.copyInto(shifted, 0, 1, WORD)
        shifted[WORD - 1] = low.toByte()
        return UInt256(shifted)
    }

    /** Lower-case hex without leading zeros ("0" for zero), as BigInteger.toString(16). */
    fun toHex(): String {
        val out = StringBuilder(64)
        for(b in word) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        var start = 0
        while(start < out.length - 1 && out[start] == '0') {
            start++
        }
        return out.substring(start)
    }

    /** Decimal, as BigInteger.toString(). */
    override fun toString(): String {
        val n = word.copyOf()
        val digits = StringBuilder(78)
        while(true) {
            var remainder = 0
            var zero = true
            for(i in 0 until WORD) {
                val current = (remainder shl 8) or (n[i].toInt() and 0xFF)
                val quotient = current / 10
                remainder = current % 10
                n[i] = quotient.toByte()
                if(quotient != 0) {
                    zero = false
                }
            }
            digits.append('0' + remainder)
            if(zero) {
                return digits.reverse().toString()
            }
        }
    }

    override fun compareTo(other: UInt256): Int {
        for(i in 0 until WORD) {
            val a = word[i].toInt() and 0xFF
            val b = other.word[i].toInt() and 0xFF
            if(a != b) {
                return if(a < b) -1 else 1
            }
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is UInt256 && word.contentEquals(other.word)

    override fun hashCode(): Int = word.contentHashCode()

    companion object {
        private const val WORD = 32
        private const val HEX = "0123456789abcdef"

        val ZERO = UInt256(ByteArray(WORD))
        val ONE = of(1)
        val MAX = UInt256(ByteArray(WORD) { -1 })

        fun of(value: Long): UInt256 {
            require(value >= 0) { "A uint256 is not negative: $value" }
            return UInt256(ByteArray(WORD) { i -> if(i < WORD - 8) 0 else (value ushr (8 * (WORD - 1 - i))).toByte() })
        }

        /** A big-endian unsigned number of any length, or null when it needs more than 256 bits. */
        fun fromBytes(bytes: ByteArray): UInt256? {
            var start = 0
            while(start < bytes.size && bytes[start].toInt() == 0) {
                start++
            }
            val length = bytes.size - start
            if(length > WORD) {
                return null
            }
            val word = ByteArray(WORD)
            bytes.copyInto(word, WORD - length, start, bytes.size)
            return UInt256(word)
        }

        /** ASCII decimal digits only (no sign, no spaces), or null when not that or more than 256 bits. */
        fun parseDecimal(text: String): UInt256? {
            if(text.isEmpty()) {
                return null
            }
            val word = ByteArray(WORD)
            for(c in text) {
                if(c < '0' || c > '9') {
                    return null
                }
                var carry = c - '0'
                for(i in WORD - 1 downTo 0) {
                    val v = (word[i].toInt() and 0xFF) * 10 + carry
                    word[i] = v.toByte()
                    carry = v ushr 8
                }
                if(carry != 0) {
                    return null
                }
            }
            return UInt256(word)
        }
    }
}
