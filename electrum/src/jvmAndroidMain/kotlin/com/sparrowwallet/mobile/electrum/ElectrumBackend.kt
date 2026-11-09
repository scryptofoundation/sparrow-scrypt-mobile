package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.sha256d
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** [ChainBackend] over an Electrum server (Litecoin, Dogecoin, Dingocoin). */
class ElectrumBackend(private val client: ElectrumClient) : ChainBackend {
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

    /** The block hash from the header at [height]: the double SHA-256 of its first 80 bytes, reversed. */
    override suspend fun blockHash(height: Int): String = blockHashOfHeader(client.getBlockHeader(height))

    override suspend fun txidAt(height: Int, position: Int): String? {
        val txid = client.getTransactionIdAt(height, position) ?: return null
        return requireHash(txid, "transaction id")
    }

    /**
     * Where the transaction is mined, from the verbose answer's confirmations: height is the tip minus
     * confirmations plus one. The tip is read before and after, and the two must agree, or the height could be
     * off by the blocks mined in between. Without usable confirmations the height is 0 and the block hash null.
     */
    override suspend fun transactionAt(txid: String): TxAt {
        repeat(2) {
            val before = client.tipHeight()
            val json = client.getTransactionVerbose(txid)
            val confirmations = json["confirmations"]?.jsonPrimitive?.longOrNull ?: 0L
            val time = (json["blocktime"] ?: json["time"])?.jsonPrimitive?.longOrNull ?: 0L
            if(confirmations <= 0L) return TxAt(0, null, 0)
            val after = client.tipHeight()
            if(before != after) return@repeat
            val height = after - confirmations + 1
            if(height < 1 || height > Int.MAX_VALUE) throw IOException("The server's confirmations ($confirmations) do not fit its tip ($after) for $txid")
            val hash = json["blockhash"]?.jsonPrimitive?.content?.let { requireHash(it, "block hash") }
            return TxAt(height.toInt(), hash, time)
        }
        throw IOException("The chain tip moved while placing $txid")
    }

    override fun close() = client.close()

    companion object {
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
