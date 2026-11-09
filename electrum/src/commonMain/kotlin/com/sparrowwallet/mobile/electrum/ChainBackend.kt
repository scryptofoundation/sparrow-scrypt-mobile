package com.sparrowwallet.mobile.electrum

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

    fun close()
}
