package com.sparrowwallet.mobile.electrum

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** [ChainBackend] over an Electrum server (Litecoin, Dogecoin, Dingocoin). */
class ElectrumBackend(private val client: ElectrumClient) : ChainBackend {
    override suspend fun connect() {
        client.connect()
        client.serverVersion()
    }

    /** server.version of the connected server: [software, protocol]. */
    suspend fun serverVersion(): List<String> = client.serverVersion()

    override suspend fun balance(script: ByteArray): Balance = client.getBalance(ElectrumProtocol.scriptHash(script))

    override suspend fun history(script: ByteArray): List<HistoryEntry> = client.getHistory(ElectrumProtocol.scriptHash(script))

    override suspend fun unspent(script: ByteArray): List<Utxo> = client.listUnspent(ElectrumProtocol.scriptHash(script))

    override suspend fun transaction(txid: String): TxDetail = toTxDetail(client.getTransactionVerbose(txid))

    override suspend fun broadcast(txHex: String): String = client.broadcast(txHex)

    override suspend fun tipHeight(): Long = client.tipHeight()

    override suspend fun feeRate(targetBlocks: Int): Double? = feeRateFromCoinPerKb(client.estimateFee(targetBlocks))

    override fun close() = client.close()

    companion object {
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
