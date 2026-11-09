package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes

/** Transactions for the tracker tests, built as raw bytes and read back with [RawTx.parse]. */
object TrackFixtures {
    /** A one byte output script (OP_1). */
    val PLAIN = byteArrayOf(0x51)

    /** A 64 character txid made of the number n. */
    fun hash(n: Int): String = n.toString(16).padStart(64, '0')

    private fun varInt(n: Int): ByteArray = when {
        n < 0xFD -> byteArrayOf(n.toByte())
        else -> byteArrayOf(0xFD.toByte(), (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
    }

    private fun le(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { ((value shr (8 * it)) and 0xFF).toByte() }

    /**
     * One input per entry of spends (txid, vout), paying the given output values to [PLAIN]. scriptSigs and witnesses are
     * per input (null for none: empty scriptSig, no witness); any non-empty witness list makes the transaction segwit.
     */
    fun tx(
        spends: List<Pair<String, Int>>,
        outputs: List<Long>,
        scriptSigs: List<ByteArray>? = null,
        witnesses: List<List<ByteArray>>? = null,
        version: Int = 2,
        locktime: Long = 0
    ): RawTx.Parsed {
        val segwit = witnesses != null && witnesses.any { it.isNotEmpty() }
        val body = ArrayList<Byte>()
        fun add(b: ByteArray) = b.forEach { body.add(it) }
        add(le(version.toLong(), 4))
        if(segwit) add(byteArrayOf(0, 1))
        add(varInt(spends.size))
        spends.forEachIndexed { i, (txid, vout) ->
            add(txid.hexToBytes().reversedArray())
            add(le(vout.toLong(), 4))
            val sig = scriptSigs?.get(i) ?: ByteArray(0)
            add(varInt(sig.size)); add(sig)
            add(le(0xFFFFFFFFL, 4))
        }
        add(varInt(outputs.size))
        for(value in outputs) {
            add(le(value, 8)); add(varInt(PLAIN.size)); add(PLAIN)
        }
        if(segwit) {
            for(i in spends.indices) {
                val items = witnesses!!.getOrElse(i) { emptyList() }
                add(varInt(items.size))
                items.forEach { add(varInt(it.size)); add(it) }
            }
        }
        add(le(locktime, 4))
        return RawTx.parse(body.toByteArray())
    }

    /** A plain spend of the given outpoints. */
    fun spend(spends: List<Pair<String, Int>>, vararg outputs: Long): RawTx.Parsed = tx(spends, outputs.toList())

    /** A transaction spending output 0 of each given previous txid. */
    fun spendZero(prevs: List<String>, vararg outputs: Long): RawTx.Parsed = tx(prevs.map { it to 0 }, outputs.toList())
}
