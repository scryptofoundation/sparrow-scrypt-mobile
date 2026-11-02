package com.sparrowwallet.mobile.crypto

/**
 * Port of drongo's CarrierScan: decides, without an indexer, whether a coin may carry an inscription, runes or dunes.
 * Two hints:
 * - The transaction that created it writes one: an ord envelope in a witness (Litecoin, Bellscoin), a doginals-style
 *   envelope in a scriptSig (Dogecoin, Pepecoin, Junkcoin, early Bellscoin), a runestone or a dunestone. Every output
 *   of such a transaction but its OP_RETURNs is flagged.
 * - Its value is the coin's usual inscription postage. An inscription passed on by others leaves no trace in the
 *   transaction that delivers it, so the value is the only local hint left.
 * Both err towards flagging: a flagged coin is only held back. Dingocoin NFTs need no rule: their link outputs pay
 * platform-held asset addresses. vectors/carrier-vectors.txt is shared with drongo.
 */
object CarrierScan {
    // OP_FALSE OP_IF OP_PUSHBYTES_3 "ord"
    private val ENVELOPE = byteArrayOf(0x00, 0x63, 0x03, 0x6f, 0x72, 0x64)
    private val ORD = "ord".encodeToByteArray()

    // ord-litecoin writes 10000; doginals.js and its forks write 100000; Bellscoin's Taproot inscriptions 1000, its
    // earlier P2SH ones 100000
    private val POSTAGE = mapOf(
        Coin.LITECOIN to setOf(10_000L),
        Coin.BELLSCOIN to setOf(1_000L, 100_000L),
        Coin.DOGECOIN to setOf(100_000L),
        Coin.PEPECOIN to setOf(100_000L),
        Coin.JUNKCOIN to setOf(100_000L)
    )

    /** True if the coin may carry something; [creatingTx] may be null when it is not at hand. */
    fun mayCarry(coin: Coin, creatingTx: RawTx.Parsed?, index: Int, value: Long): Boolean {
        if(creatingTx != null) {
            if(index in creatingTx.outputs.indices && isOpReturn(creatingTx.outputs[index].script)) return false
            if(marksOutputs(creatingTx)) return true
        }
        return value in postage(coin)
    }

    fun postage(coin: Coin): Set<Long> = POSTAGE[coin] ?: emptySet()

    /** True if the transaction writes an inscription, runes or dunes. */
    fun marksOutputs(tx: RawTx.Parsed): Boolean =
        hasEnvelope(tx) || hasScriptSigEnvelope(tx) || hasRunestone(tx) || hasDunestone(tx)

    fun hasEnvelope(tx: RawTx.Parsed): Boolean = tx.witnesses.any { witness -> witness.any { contains(it, ENVELOPE) } }

    /**
     * A doginals-style scriptSig: it starts with a push of "ord", or (later transactions of a long inscription) ends
     * with the partial lock script, pubkey CHECKSIGVERIFY, OP_DROPs, OP_TRUE.
     */
    fun hasScriptSigEnvelope(tx: RawTx.Parsed): Boolean = tx.scriptSigs.any { scriptSig ->
        val pushes = pushes(scriptSig) ?: return@any false
        pushes.isNotEmpty() && (pushes.first()?.contentEquals(ORD) == true || pushes.last()?.let(::isPartialLockScript) == true)
    }

    fun hasRunestone(tx: RawTx.Parsed): Boolean =
        tx.outputs.any { it.script.size >= 2 && it.script[0] == 0x6a.toByte() && it.script[1] == 0x5d.toByte() }

    fun hasDunestone(tx: RawTx.Parsed): Boolean =
        tx.outputs.any { it.script.size >= 3 && it.script[0] == 0x6a.toByte() && it.script[1] == 0x01.toByte() && it.script[2] == 0x44.toByte() }

    private fun isPartialLockScript(script: ByteArray): Boolean {
        if(script.size < 36 || script[0] != 0x21.toByte() || script[34] != 0xad.toByte() || script.last() != 0x51.toByte()) return false
        return (35 until script.size - 1).all { script[it] == 0x75.toByte() }
    }

    private fun isOpReturn(script: ByteArray): Boolean = script.isNotEmpty() && script[0] == 0x6a.toByte()

    /** The script's chunks: the pushed data, or null for an opcode. Null overall when a push runs past the end. */
    private fun pushes(script: ByteArray): List<ByteArray?>? {
        val chunks = mutableListOf<ByteArray?>()
        var i = 0
        while(i < script.size) {
            val op = script[i].toInt() and 0xFF
            i++
            val length = when {
                op == 0x00 -> 0
                op in 0x01..0x4b -> op
                op == 0x4c -> { if(i + 1 > script.size) return null; (script[i].toInt() and 0xFF).also { i += 1 } }
                op == 0x4d -> { if(i + 2 > script.size) return null; ((script[i].toInt() and 0xFF) or ((script[i + 1].toInt() and 0xFF) shl 8)).also { i += 2 } }
                op == 0x4e -> {
                    if(i + 4 > script.size) return null
                    val n = (0 until 4).fold(0L) { acc, k -> acc or ((script[i + k].toLong() and 0xFF) shl (8 * k)) }
                    i += 4
                    if(n > script.size) return null
                    n.toInt()
                }
                else -> { chunks.add(null); continue }
            }
            if(i + length > script.size) return null
            chunks.add(script.copyOfRange(i, i + length))
            i += length
        }
        return chunks
    }

    private fun contains(data: ByteArray, pattern: ByteArray): Boolean {
        if(data.size < pattern.size) return false
        outer@ for(i in 0..data.size - pattern.size) {
            for(j in pattern.indices) if(data[i + j] != pattern[j]) continue@outer
            return true
        }
        return false
    }
}
