package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.sha256d
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * [ChainBackend] over an Electrum server (Litecoin, Dogecoin, Dingocoin). [pause] waits (cancellably) while the server's
 * tip catches up with its daemon; tests inject one that does not wait.
 */
class ElectrumBackend(
    private val client: ElectrumClient,
    private val pause: suspend (millis: Long) -> Unit = { delay(it) }
) : ChainBackend {
    private var version: List<String>? = null

    override suspend fun connect() {
        client.connect()
        version = client.serverVersion()
    }

    /**
     * server.version of the connected server: [software, protocol]. The handshake's answer:
     * ElectrumX refuses a second server.version on the same connection.
     */
    suspend fun serverVersion(): List<String> = version ?: client.serverVersion().also { version = it }

    override suspend fun balance(script: ByteArray): Balance = client.getBalance(ElectrumProtocol.scriptHash(script))

    override suspend fun history(script: ByteArray): List<HistoryEntry> = client.getHistory(ElectrumProtocol.scriptHash(script))

    override suspend fun unspent(script: ByteArray): List<Utxo> = client.listUnspent(ElectrumProtocol.scriptHash(script))

    override suspend fun transaction(txid: String): TxDetail = toTxDetail(client.getTransactionVerbose(txid))

    override suspend fun rawTransaction(txid: String): ByteArray = verifiedRawTransaction(txid, client.getTransactionHex(txid))

    override suspend fun broadcast(txHex: String): String = client.broadcast(txHex)

    override suspend fun tipHeight(): Long = client.tipHeight()

    override suspend fun feeRate(targetBlocks: Int): Double? = feeRateFromCoinPerKb(client.estimateFee(targetBlocks))

    /** Block hashes read so far by height, so several transactions of one block cost one header call. */
    private val hashes = HashMap<Int, String>()

    /** The tip [transactionAt] places transactions against, read on first use and on a block-hash mismatch. */
    private var placingTip: Long? = null

    /** The block hash from the header at [height]: the double SHA-256 of its first 80 bytes, reversed. */
    override suspend fun blockHash(height: Int): String =
        hashes[height] ?: blockHashOfHeader(client.getBlockHeader(height)).also { hashes[height] = it }

    override suspend fun txidAt(height: Int, position: Int): String? {
        val txid = client.getTransactionIdAt(height, position) ?: return null
        return requireHash(txid, "transaction id")
    }

    /**
     * Where the transaction is mined: height is the tip minus confirmations plus one, and the verbose answer's
     * blockhash must equal the hash of the header at that height, or the tip and the daemon disagree (a block
     * one side has and the other lacks). The tip is read once per backend (one check run) and kept. On a mismatch the
     * tip is read again and the transaction placed again, up to [MAX_PLACING_RETRIES] times per call; when the fresh tip
     * equals the one just used (the server's tip trails its daemon for a few seconds after a block, while the verbose
     * confirmations already count it), the backend first waits [TIP_LAG_PAUSE_MS]. Only a hash equality places a
     * transaction; still mismatching after the retries throws.
     * Without confirmations the height is 0 and the block hash null; confirmations without a blockhash throw.
     */
    override suspend fun transactionAt(txid: String): TxAt = place(txid, null)

    /**
     * The bytes and the place from one verbose transaction.get. The verbose answer's top-level hex is used only when it
     * passes the txid hash check [rawTransaction] applies; without hex, or when it is not valid or hashes to another
     * transaction, the bytes come from the (checked) raw call. The placement is [transactionAt]'s, on this same answer, with the tip read before it.
     */
    override suspend fun placedTransaction(txid: String): PlacedTx {
        //The tip first, as [transactionAt] reads it. If a block lands between the two reads, the height comes out one
        //behind the tip and costs a retry; safety is unaffected
        placingTip ?: client.tipHeight().also { placingTip = it }
        val json = client.getTransactionVerbose(txid)
        val hex = json["hex"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        val raw = hex?.let { try { verifiedRawTransaction(txid, it) } catch(e: IOException) { null } } ?: rawTransaction(txid)
        return PlacedTx(raw, place(txid, json))
    }

    /** [transactionAt]'s placement loop; [first] is a verbose answer already read for [txid], used for the first pass. */
    private suspend fun place(txid: String, first: JsonObject?): TxAt {
        var tip = placingTip ?: client.tipHeight().also { placingTip = it }
        var retries = 0
        var pending = first
        while(true) {
            val json = pending ?: client.getTransactionVerbose(txid)
            pending = null
            val confirmations = json["confirmations"]?.jsonPrimitive?.longOrNull ?: 0L
            if(confirmations <= 0L) return TxAt(0, null, 0)
            val time = (json["blocktime"] ?: json["time"])?.jsonPrimitive?.longOrNull ?: 0L
            val claimed = requireHash(json["blockhash"]?.jsonPrimitive?.content
                ?: throw IOException("The server gives $txid $confirmations confirmations but no block hash"), "block hash")
            val height = tip - confirmations + 1
            if(height < 1 || height > Int.MAX_VALUE) throw IOException("The server's confirmations ($confirmations) do not fit its tip ($tip) for $txid")
            if(blockHash(height.toInt()) == claimed) return TxAt(height.toInt(), claimed, time)
            if(retries++ >= MAX_PLACING_RETRIES) throw IOException("The height of $txid does not match its block hash")
            var fresh = client.tipHeight()
            if(fresh == tip) {
                pause(TIP_LAG_PAUSE_MS)
                fresh = client.tipHeight()
            }
            tip = fresh
            placingTip = fresh
        }
    }

    override fun close() = client.close()

    companion object {
        /** How many times one placement re-reads the tip after a block-hash mismatch before it gives up. */
        const val MAX_PLACING_RETRIES = 3

        /** The wait before reading the tip again when it has not moved (the server catching up with its daemon). */
        const val TIP_LAG_PAUSE_MS = 2_500L

        /** The block hash a header gives; fewer than 80 bytes, or anything not hex, is an error. */
        fun blockHashOfHeader(headerHex: String): String {
            val clean = headerHex.trim().lowercase()
            if(clean.length % 2 != 0 || !clean.all { it in '0'..'9' || it in 'a'..'f' }) throw IOException("The server's block header is not valid hex")
            val header = clean.hexToBytes()
            if(header.size < 80) throw IOException("The server's block header is ${header.size} bytes, not at least 80")
            return sha256d(header.copyOfRange(0, 80)).reversedArray().toHex()
        }

        private fun requireHash(value: String, what: String): String {
            val v = value.trim().lowercase()
            if(v.length != 64 || !v.all { it in '0'..'9' || it in 'a'..'f' }) throw IOException("The server's $what is not 64 hex characters")
            return v
        }

        /** Electrum's estimatefee is coins per kB, -1 when the server has no estimate. */
        fun feeRateFromCoinPerKb(perKb: Double): Double? = if(perKb <= 0) null else perKb * 100_000

        /**
         * A verbose transaction (the daemon's getrawtransaction JSON) as a [TxDetail]. It carries
         * no block height and no spent outputs: callers take the height from history and fetch
         * the previous transactions.
         */
        fun toTxDetail(json: JsonObject): TxDetail = TxDetail(
            txid = json["txid"]!!.jsonPrimitive.content,
            inputs = json["vin"]!!.jsonArray.map {
                val i = it.jsonObject
                TxIn(i["txid"]?.jsonPrimitive?.content, i["vout"]?.jsonPrimitive?.intOrNull, null)
            },
            outputs = json["vout"]!!.jsonArray.map {
                val o = it.jsonObject
                TxOut(
                    o["scriptPubKey"]?.jsonObject?.get("hex")?.jsonPrimitive?.content ?: "",
                    ElectrumProtocol.ltcToLitoshis(o["value"]!!.jsonPrimitive.content)
                )
            },
            height = 0,
            time = json["time"]?.jsonPrimitive?.longOrNull,
            confirmations = json["confirmations"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }
}
