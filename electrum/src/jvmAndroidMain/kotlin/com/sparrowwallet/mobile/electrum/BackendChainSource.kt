package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.track.BlockFee
import com.sparrowwallet.mobile.crypto.track.ChainSource
import com.sparrowwallet.mobile.crypto.track.ChainTx
import kotlinx.coroutines.withContext

/**
 * The tracer's view of the chain through a coin's server. Transactions come as bytes already checked to hash to
 * the txid they were asked for, with the block they sit in; the other reads pass straight to the backend, which
 * answers null when its server cannot say and throws when an answer cannot be trusted. Every call runs under
 * [NoRateLimitWait]: a rate-limited server fails the call at once (the tracer reads it as a server error and the run
 * stops) instead of being waited out, while the wallet's own calls on the same backend keep their retries.
 */
class BackendChainSource(private val backend: ChainBackend) : ChainSource {
    override suspend fun transaction(txid: String): ChainTx = withContext(NoRateLimitWait) {
        val parsed = RawTx.parse(backend.rawTransaction(txid))
        val at = backend.transactionAt(txid)
        ChainTx(parsed, at.height, at.blockHash, at.time, fromServer = true)
    }

    override suspend fun blockFees(height: Int, blockHash: String): List<BlockFee>? = blockFees(height, blockHash) {}

    override suspend fun blockFees(height: Int, blockHash: String, extraRequest: () -> Unit): List<BlockFee>? =
        withContext(NoRateLimitWait) { backend.blockFees(blockHash, extraRequest) }

    override suspend fun txidAt(height: Int, position: Int): String? = withContext(NoRateLimitWait) { backend.txidAt(height, position) }

    override suspend fun blockHash(height: Int): String? = withContext(NoRateLimitWait) { backend.blockHash(height) }
}
