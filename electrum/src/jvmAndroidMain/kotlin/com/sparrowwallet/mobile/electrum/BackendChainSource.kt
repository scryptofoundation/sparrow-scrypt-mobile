package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.track.BlockFee
import com.sparrowwallet.mobile.crypto.track.ChainSource
import com.sparrowwallet.mobile.crypto.track.ChainTx

/**
 * The tracer's view of the chain through a coin's server. Transactions come as bytes already checked to hash to
 * the txid they were asked for, with the block they sit in; the other reads pass straight to the backend, which
 * answers null when its server cannot say and throws when an answer cannot be trusted.
 */
class BackendChainSource(private val backend: ChainBackend) : ChainSource {
    override suspend fun transaction(txid: String): ChainTx {
        val parsed = RawTx.parse(backend.rawTransaction(txid))
        val at = backend.transactionAt(txid)
        return ChainTx(parsed, at.height, at.blockHash, at.time, fromServer = true)
    }

    override suspend fun blockFees(height: Int, blockHash: String): List<BlockFee>? = backend.blockFees(blockHash)

    override suspend fun txidAt(height: Int, position: Int): String? = backend.txidAt(height, position)

    override suspend fun blockHash(height: Int): String? = backend.blockHash(height)
}
