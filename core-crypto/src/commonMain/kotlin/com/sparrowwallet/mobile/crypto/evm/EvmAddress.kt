package com.sparrowwallet.mobile.crypto.evm

/**
 * A 20-byte EVM account address, shown with the EIP-55 mixed-case checksum. Port of drongo's EvmAddress, kept apart from
 * the Litecoin address code: it has no script type and no network byte, and is never offered where a Litecoin address
 * is expected.
 */
class EvmAddress private constructor(private val raw: ByteArray) {
    /** The 20 bytes (a copy). */
    val bytes: ByteArray
        get() = raw.copyOf()

    /** The EIP-55 checksummed form, 0x-prefixed. */
    override fun toString(): String {
        val lower = StringBuilder(40)
        for(b in raw) {
            val v = b.toInt() and 0xFF
            lower.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        val hash = keccak256(lower.toString().encodeToByteArray())
        val out = StringBuilder("0x")
        for(i in 0 until 40) {
            val c = lower[i]
            val nibble = (hash[i / 2].toInt() shr (if(i % 2 == 0) 4 else 0)) and 0x0F
            out.append(if(c in 'a'..'f' && nibble >= 8) c - ('a' - 'A') else c)
        }
        return out.toString()
    }

    override fun equals(other: Any?): Boolean = other is EvmAddress && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    companion object {
        private const val HEX = "0123456789abcdef"

        /** Exactly 20 bytes, or null. */
        fun ofBytes(bytes: ByteArray): EvmAddress? = if(bytes.size == 20) EvmAddress(bytes.copyOf()) else null

        /** The address of an uncompressed public key (65 bytes, 0x04 first): the last 20 bytes of keccak256 of x and y. */
        fun ofPublicKey(uncompressed65: ByteArray): EvmAddress {
            require(uncompressed65.size == 65 && uncompressed65[0].toInt() == 4) { "Not an uncompressed public key" }
            val hash = keccak256(uncompressed65.copyOfRange(1, 65))
            return EvmAddress(hash.copyOfRange(12, 32))
        }

        /**
         * "0x" and exactly 40 hex digits, nothing around them. All lower or all upper case carries no checksum and is
         * accepted; mixed case must match its EIP-55 checksum, since a mismatch almost always means a mistyped address.
         * Stricter than the desktop, which also takes bare hex, "0X" and surrounding whitespace.
         */
        fun parse(text: String): EvmAddress? {
            if(text.length != 42 || text[0] != '0' || text[1] != 'x') {
                return null
            }
            val bytes = ByteArray(20)
            var lower = false
            var upper = false
            for(i in 0 until 40) {
                val c = text[2 + i]
                val digit = when(c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> { lower = true; c - 'a' + 10 }
                    in 'A'..'F' -> { upper = true; c - 'A' + 10 }
                    else -> return null
                }
                val index = i / 2
                bytes[index] = ((bytes[index].toInt() shl 4) or digit).toByte()
            }
            val address = EvmAddress(bytes)
            if(lower && upper && address.toString() != text) {
                return null
            }
            return address
        }
    }
}
