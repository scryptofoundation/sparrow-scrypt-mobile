package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx
import java.io.IOException

/** An in-memory chain for the tracer tests (port of drongo's FakeChainSource). */
class FakeChainSource : ChainSource {
    val txs = HashMap<String, ChainTx>()
    val fees = HashMap<Int, List<BlockFee>>()
    val positions = HashMap<Int, List<String>>()
    val failing = HashSet<String>()
    //Block hashes the server gives by height, for transactions it returned without one
    val hashes = HashMap<Int, String>()
    var transactionCalls = 0
    var blockFeeCalls = 0
    var positionCalls = 0

    /** Adds a confirmed transaction served by the server, block hash derived from the height. */
    fun add(tx: RawTx.Parsed, height: Int, fromServer: Boolean = true): RawTx.Parsed {
        txs[tx.txid] = ChainTx(tx, height, if(height == 0) null else heightHash(height), 1_700_000_000L + height, fromServer)
        return tx
    }

    /** Adds a confirmed transaction the server returns without its block hash (the app's verbose answers drop it). */
    fun addWithoutHash(tx: RawTx.Parsed, height: Int): RawTx.Parsed {
        txs[tx.txid] = ChainTx(tx, height, null, 1_700_000_000L + height, true)
        return tx
    }

    override suspend fun transaction(txid: String): ChainTx {
        transactionCalls++
        if(txid in failing) {
            throw IOException("connection reset")
        }
        return txs[txid] ?: throw IOException("No such transaction $txid")
    }

    override suspend fun blockFees(height: Int, blockHash: String): List<BlockFee>? {
        blockFeeCalls++
        //Like Esplora: a block's fee list is asked for by its hash
        return fees[height]
    }

    /** Requests past the first that a paged fee listing (Esplora's) makes, each reported to the tracer's counter. */
    var extraFeeRequests = 0

    override suspend fun blockFees(height: Int, blockHash: String, extraRequest: () -> Unit): List<BlockFee>? {
        val fees = blockFees(height, blockHash)
        repeat(extraFeeRequests) { extraRequest() }
        return fees
    }

    override suspend fun blockHash(height: Int): String? = hashes[height]

    override suspend fun txidAt(height: Int, position: Int): String? {
        positionCalls++
        val block = positions[height]
        return if(block == null || position >= block.size) null else block[position]
    }

    companion object {
        fun heightHash(height: Int): String = (0xb10c0000L + height).toString(16).padStart(64, '0')
    }
}
