package com.sparrowwallet.mobile.crypto.evm

/**
 * The slice of the Solidity ABI the phone's read-only calls need, ported from drongo's Abi: selectors, static arguments,
 * and decoding of bool, uint256, address and string results.
 *
 * Results come from a third-party node or contract, so every decode reads the offset and length words in place and
 * checks them against the data actually present, in arithmetic that cannot overflow, before anything is copied or
 * allocated. A malformed result is null, never an exception.
 */
object Abi {
    const val MAX_DYNAMIC = 4_096
    private const val WORD = 32

    /** The 4-byte selector of a canonical ASCII signature such as "balanceOf(address)". */
    fun selector(signature: String): ByteArray {
        require(signature.all { it.code in 0x20..0x7E }) { "Not an ASCII signature: $signature" }
        return keccak256(signature.encodeToByteArray()).copyOf(4)
    }

    /**
     * Selector then arguments. Static arguments only: EvmAddress, UInt256, Boolean, a ByteArray of 32 (bytes32) or of 4
     * (bytes4, left-aligned in its word). Anything else is refused with IllegalArgumentException.
     */
    fun call(signature: String, vararg args: Any): ByteArray {
        val out = ByteArray(4 + WORD * args.size)
        selector(signature).copyInto(out)
        for((i, arg) in args.withIndex()) {
            encodeStatic(arg).copyInto(out, 4 + WORD * i)
        }
        return out
    }

    private fun encodeStatic(arg: Any): ByteArray = when {
        arg is EvmAddress -> ByteArray(WORD).also { arg.bytes.copyInto(it, WORD - 20) }
        arg is UInt256 -> arg.toWord()
        arg is Boolean -> (if(arg) UInt256.ONE else UInt256.ZERO).toWord()
        arg is ByteArray && arg.size == WORD -> arg.copyOf()
        arg is ByteArray && arg.size == 4 -> ByteArray(WORD).also { arg.copyInto(it) }
        else -> throw IllegalArgumentException("Unsupported ABI argument: $arg")
    }

    /** Word `index` as a bool: 0 or 1, anything else null. */
    fun bool(result: ByteArray, index: Int = 0): Boolean? {
        val start = wordStart(result, index) ?: return null
        for(i in start until start + WORD - 1) {
            if(result[i].toInt() != 0) {
                return null
            }
        }
        return when(result[start + WORD - 1].toInt()) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    /** Word `index` as an unsigned integer. */
    fun uint(result: ByteArray, index: Int = 0): UInt256? {
        val start = wordStart(result, index) ?: return null
        return UInt256.fromBytes(result.copyOfRange(start, start + WORD))
    }

    /** Word `index` as an address: twelve zero bytes, then the twenty. */
    fun address(result: ByteArray, index: Int = 0): EvmAddress? {
        val start = wordStart(result, index) ?: return null
        for(i in start until start + WORD - 20) {
            if(result[i].toInt() != 0) {
                return null
            }
        }
        return EvmAddress.ofBytes(result.copyOfRange(start + WORD - 20, start + WORD))
    }

    /**
     * A string result of at most `limit` UTF-8 bytes, as drongo's decodeString reads it: a result of exactly 32 bytes is
     * a bytes32 name or symbol (as some early tokens return), trimmed of trailing zero bytes; otherwise word 0 is the
     * offset (any offset that fits) of the length word, then the bytes. Trailing bytes and missing padding are ignored;
     * bytes that are not UTF-8 become U+FFFD, as the desktop's JDK decodes them. Null on anything malformed or longer
     * than the limit.
     */
    fun string(result: ByteArray, limit: Int = MAX_DYNAMIC): String? {
        if(result.size == WORD) {
            var end = WORD
            while(end > 0 && result[end - 1].toInt() == 0) {
                end--
            }
            return Utf8.decodeLenient(result, 0, end)
        }
        val offset = smallWord(result, 0) ?: return null
        if(offset.toLong() + WORD > result.size) {
            return null
        }
        val length = smallWord(result, offset) ?: return null
        if(length > limit || offset.toLong() + WORD + length > result.size) {
            return null
        }
        return Utf8.decodeLenient(result, offset + WORD, offset + WORD + length)
    }

    //The start of word `index`, or null when the result does not hold it
    private fun wordStart(result: ByteArray, index: Int): Int? {
        if(index < 0) {
            return null
        }
        val start = index.toLong() * WORD
        return if(start + WORD > result.size) null else start.toInt()
    }

    //The 32-byte word at byte `start` as a non-negative Int, or null when it does not fit 31 bits or is not all there
    private fun smallWord(result: ByteArray, start: Int): Int? {
        if(start.toLong() + WORD > result.size) {
            return null
        }
        for(i in start until start + WORD - 4) {
            if(result[i].toInt() != 0) {
                return null
            }
        }
        if(result[start + WORD - 4].toInt() and 0x80 != 0) {
            return null
        }
        var value = 0
        for(i in start + WORD - 4 until start + WORD) {
            value = (value shl 8) or (result[i].toInt() and 0xFF)
        }
        return value
    }
}
