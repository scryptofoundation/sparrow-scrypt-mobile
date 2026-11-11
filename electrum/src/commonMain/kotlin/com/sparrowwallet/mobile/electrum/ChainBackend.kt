package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.track.BlockFee

/** A transaction output: the paid script and its value in the coin's smallest unit. */
data class TxOut(val scriptHex: String, val value: Long)

/**
 * A transaction input. [prevTxid] is null for a coinbase; [prevOut] is the output it spends
 * when the server includes it (Esplora does, Electrum does not).
 */
data class TxIn(val prevTxid: String?, val prevVout: Int?, val prevOut: TxOut?)

/** A transaction as a wallet needs it. [height] <= 0 means unconfirmed or unknown. */
data class TxDetail(
    val txid: String,
    val inputs: List<TxIn>,
    val outputs: List<TxOut>,
    val height: Int,
    val time: Long?,
    val confirmations: Int
)

/**
 * Where a transaction sits in the chain. [height] is 0 and [blockHash] null when it is unconfirmed or the
 * server did not say where; [time] is the block time, 0 when unknown.
 */
data class TxAt(val height: Int, val blockHash: String?, val time: Long)

/** A transaction's serialized bytes (checked to hash to the txid asked for) and where it is mined. */
class PlacedTx(val raw: ByteArray, val at: TxAt)

/**
 * One coin's chain as seen through a server: an Electrum server or an Esplora REST API.
 * Scripts are passed as scriptPubKey bytes; each backend keys them its own way.
 */
interface ChainBackend {
    /** Opens the connection and checks the server answers. Air-gapped mode refuses here. */
    suspend fun connect()

    suspend fun balance(script: ByteArray): Balance

    suspend fun history(script: ByteArray): List<HistoryEntry>

    /** True when the last [history] for [script] stopped before the address's oldest transaction. */
    fun historyTruncated(script: ByteArray): Boolean = false

    suspend fun unspent(script: ByteArray): List<Utxo>

    suspend fun transaction(txid: String): TxDetail

    /**
     * The serialized transaction, checked to hash to [txid] (a previous transaction a PSBT carries
     * for its signer). A server answering another transaction is an error.
     */
    suspend fun rawTransaction(txid: String): ByteArray

    /** Broadcasts a raw transaction and returns its txid. */
    suspend fun broadcast(txHex: String): String

    suspend fun tipHeight(): Long

    /** Estimated fee rate in sats per vbyte to confirm within [targetBlocks], or null when the server has none. */
    suspend fun feeRate(targetBlocks: Int): Double?

    /**
     * The hash of the block at [height] on the server's chain, or null when the server cannot say (no such
     * block, or no call for it). An answer that cannot be read throws.
     */
    suspend fun blockHash(height: Int): String? = null

    /** The txid at [position] of the block at [height] (0 is the coinbase), or null past its end or when unsupported. */
    suspend fun txidAt(height: Int, position: Int): String? = null

    /**
     * The fees of the block's non-coinbase transactions in block order, or null when the server cannot list
     * them. A listing that is incomplete or lacks a fee throws, never a partial list.
     */
    suspend fun blockFees(blockHash: String): List<BlockFee>? = null

    /** As the other [blockFees], telling [extraRequest] before each request past the first (it may throw to stop). */
    suspend fun blockFees(blockHash: String, extraRequest: () -> Unit): List<BlockFee>? = blockFees(blockHash)

    /** Where [txid] is mined, from the server. Unconfirmed gives height 0. */
    suspend fun transactionAt(txid: String): TxAt = throw UnsupportedOperationException("This backend cannot place a transaction in a block")

    /**
     * [rawTransaction] and [transactionAt] together. A backend that can answer both in one server call overrides this;
     * the bytes must still be checked to hash to [txid].
     */
    suspend fun placedTransaction(txid: String): PlacedTx = PlacedTx(rawTransaction(txid), transactionAt(txid))

    fun close()
}
