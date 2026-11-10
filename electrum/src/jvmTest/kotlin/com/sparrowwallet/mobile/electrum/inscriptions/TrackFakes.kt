package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.track.BlockFee
import com.sparrowwallet.mobile.crypto.track.ChainSource
import com.sparrowwallet.mobile.crypto.track.ChainTx
import java.io.IOException

/**
 * A small in-memory chain and transaction builders for the run tests (the minimum of core-crypto's test fixtures,
 * which the electrum module cannot see).
 */
object TrackFakes {
    private val PLAIN = byteArrayOf(0x51)
    private val XONLY = ByteArray(32) { 4 }

    fun hash(n: Int): String = n.toString(16).padStart(64, '0')

    fun block(height: Int): String = (0xb10c0000L + height).toString(16).padStart(64, '0')

    private fun varInt(n: Int): ByteArray = byteArrayOf(n.toByte())

    private fun le(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { ((value shr (8 * it)) and 0xFF).toByte() }

    private fun push(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data

    /** One input per spend (txid, vout), paying the outputs to a plain script; witnesses make it segwit. */
    fun tx(
        spends: List<Pair<String, Int>>,
        outputs: List<Long>,
        scriptSigs: List<ByteArray>? = null,
        witnesses: List<List<ByteArray>>? = null
    ): RawTx.Parsed {
        val segwit = witnesses != null && witnesses.any { it.isNotEmpty() }
        val body = ArrayList<Byte>()
        fun add(b: ByteArray) = b.forEach { body.add(it) }
        add(le(2, 4))
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
        add(le(0, 4))
        return RawTx.parse(body.toByteArray())
    }

    /** A spend of output 0 of prev. */
    fun spend(prev: String, vararg outputs: Long): RawTx.Parsed = tx(listOf(prev to 0), outputs.toList())

    /** A spend of output 0 of prev whose witness reveals an ord text inscription (on the first unit of its first input). */
    fun reveal(prev: String, text: String, vararg outputs: Long): RawTx.Parsed {
        val script = byteArrayOf(0x20) + XONLY + byteArrayOf(0xac.toByte(), 0x00, 0x63) + push("ord".encodeToByteArray()) +
            push(byteArrayOf(1)) + push("text/plain;charset=utf-8".encodeToByteArray()) + byteArrayOf(0x00) +
            push(text.encodeToByteArray()) + byteArrayOf(0x68)
        return tx(listOf(prev to 0), outputs.toList(), witnesses = listOf(listOf(ByteArray(64), script, ByteArray(33))))
    }

    /** A coinbase paying the outputs. */
    fun coinbase(vararg outputs: Long): RawTx.Parsed =
        tx(listOf(hash(0) to -1), outputs.toList(), scriptSigs = listOf(byteArrayOf(0x01, 0x64)))

    class Chain : ChainSource {
        val txs = HashMap<String, ChainTx>()
        val positions = HashMap<Int, List<String>>()
        val failing = HashSet<String>()
        var calls = 0
        var positionCalls = 0

        fun add(tx: RawTx.Parsed, height: Int): RawTx.Parsed {
            txs[tx.txid] = ChainTx(tx, height, if(height == 0) null else block(height), 1_700_000_000L + height, true)
            return tx
        }

        override suspend fun transaction(txid: String): ChainTx {
            calls++
            if(txid in failing) {
                throw IOException("connection reset")
            }
            return txs[txid] ?: throw IOException("No such transaction $txid")
        }

        override suspend fun blockFees(height: Int, blockHash: String): List<BlockFee>? = null

        override suspend fun txidAt(height: Int, position: Int): String? {
            positionCalls++
            val list = positions[height]
            return if(list == null || position >= list.size) null else list[position]
        }
    }
}
