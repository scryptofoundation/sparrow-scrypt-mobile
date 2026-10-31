package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.Addresses
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.sha256
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/**
 * [ChainBackend] over an Esplora REST API (Pepecoin, Luckycoin, Junkcoin, Bellscoin), with the
 * routes and quirks desktop's EsploraClient handles: address routes, retried by scripthash when
 * a server refuses the address (jkc-testnet-api.s3na.xyz answers 400 for Junkcoin P2SH);
 * transaction dates from status.block_time; fee estimates with the mempool.space dialect as a
 * fallback; an "already known" broadcast reported as sent.
 */
class EsploraBackend(
    baseUrl: String,
    private val params: ChainParams,
    proxy: SocksProxy? = null,
    timeoutMs: Int = 30_000
) : ChainBackend {
    private val http = HttpEndpoint(baseUrl, proxy, timeoutMs)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var tip: Long? = null

    override suspend fun connect() {
        tip = readTip()
    }

    override suspend fun tipHeight(): Long = readTip().also { tip = it }

    private suspend fun readTip(): Long {
        val r = http.get("/blocks/tip/height")
        val text = r.body.trim()
        if(r.status != 200 || text.isEmpty() || !text.all { it.isDigit() } || text.length > 12) {
            throw IOException("Not an Esplora server: ${http.url}")
        }
        return text.toLong()
    }

    /** GET [route] for the script by address, falling back to the scripthash form on 400 or when it has no address. */
    private suspend fun scriptGet(script: ByteArray, route: String): String {
        val address = Addresses.fromScript(script, params)
        if(address != null) {
            val r = http.get("/address/$address$route")
            if(r.status == 200) return r.body
            if(r.status != 400) throw IOException(failure(r))
        }
        val r = http.get("/scripthash/${sha256(script).toHex()}$route")
        if(r.status != 200) throw IOException(failure(r))
        return r.body
    }

    override suspend fun balance(script: ByteArray): Balance {
        val o = json.parseToJsonElement(scriptGet(script, "")).jsonObject
        fun net(stats: String) = o[stats]?.jsonObject?.let {
            it["funded_txo_sum"]!!.jsonPrimitive.long - it["spent_txo_sum"]!!.jsonPrimitive.long
        } ?: 0L
        return Balance(net("chain_stats"), net("mempool_stats"))
    }

    override suspend fun history(script: ByteArray): List<HistoryEntry> {
        val entries = ArrayList<HistoryEntry>()
        var route = "/txs"
        for(page in 0 until MAX_PAGES) {
            val txs = json.parseToJsonElement(scriptGet(script, route)).jsonArray
            var confirmed = 0
            var lastConfirmed: String? = null
            for(t in txs) {
                val o = t.jsonObject
                val txid = o["txid"]!!.jsonPrimitive.content
                val height = blockHeight(o)
                entries += HistoryEntry(txid, height)
                if(height > 0) {
                    confirmed++
                    lastConfirmed = txid
                }
            }
            if(confirmed < PAGE_SIZE || lastConfirmed == null) break
            route = "/txs/chain/$lastConfirmed"
        }
        return entries
    }

    override suspend fun unspent(script: ByteArray): List<Utxo> =
        json.parseToJsonElement(scriptGet(script, "/utxo")).jsonArray.map {
            val o = it.jsonObject
            Utxo(o["txid"]!!.jsonPrimitive.content, o["vout"]!!.jsonPrimitive.int, blockHeight(o), o["value"]!!.jsonPrimitive.long)
        }

    override suspend fun transaction(txid: String): TxDetail {
        val r = http.get("/tx/$txid")
        if(r.status != 200) throw IOException(failure(r))
        val o = json.parseToJsonElement(r.body).jsonObject
        val height = blockHeight(o)
        val tipNow = tip
        return TxDetail(
            txid = o["txid"]!!.jsonPrimitive.content,
            inputs = o["vin"]!!.jsonArray.map {
                val i = it.jsonObject
                if(i["is_coinbase"]?.jsonPrimitive?.booleanOrNull == true) {
                    TxIn(null, null, null)
                } else {
                    val prev = (i["prevout"] as? JsonObject)?.let { p ->
                        TxOut(p["scriptpubkey"]!!.jsonPrimitive.content, p["value"]!!.jsonPrimitive.long)
                    }
                    TxIn(i["txid"]?.jsonPrimitive?.content, i["vout"]?.jsonPrimitive?.intOrNull, prev)
                }
            },
            outputs = o["vout"]!!.jsonArray.map {
                val out = it.jsonObject
                TxOut(out["scriptpubkey"]!!.jsonPrimitive.content, out["value"]!!.jsonPrimitive.long)
            },
            height = height,
            time = o["status"]?.jsonObject?.get("block_time")?.jsonPrimitive?.longOrNull,
            confirmations = if(height > 0 && tipNow != null) (tipNow - height + 1).toInt().coerceAtLeast(1) else 0
        )
    }

    override suspend fun broadcast(txHex: String): String {
        val r = http.post("/tx", txHex)
        if(r.status == 404 || r.status == 405) {
            throw IOException("The server at ${http.url} does not accept transaction broadcasts.")
        }
        if(r.status != 200) {
            val reason = r.body.trim()
            val lower = reason.lowercase()
            if(ALREADY.any { lower.contains(it) }) {
                return RawTx.parse(txHex.hexToBytes()).txid
            }
            throw IOException(reason.ifEmpty { "Broadcast refused (HTTP ${r.status})" })
        }
        return r.body.trim().trim('"')
    }

    override suspend fun feeRate(targetBlocks: Int): Double? {
        val estimates = sortedMapOf<Int, Double>()
        runCatching {
            val r = http.get("/fee-estimates")
            if(r.status == 200) {
                for((k, v) in json.parseToJsonElement(r.body).jsonObject) {
                    val target = k.toIntOrNull() ?: continue
                    v.jsonPrimitive.doubleOrNull?.let { estimates[target] = it }
                }
            }
        }
        if(estimates.isEmpty()) {
            for(path in listOf("/v1/fees/recommended", "/fees/recommended")) {
                runCatching {
                    val r = http.get(path)
                    if(r.status == 200) {
                        val o = json.parseToJsonElement(r.body).jsonObject
                        for((name, target) in MEMPOOL_TARGETS) {
                            o[name]?.jsonPrimitive?.doubleOrNull?.let { estimates[target] = it }
                        }
                    }
                }
                if(estimates.isNotEmpty()) break
            }
        }
        if(estimates.isEmpty()) return null
        return estimates.entries.firstOrNull { it.key >= targetBlocks }?.value ?: estimates[estimates.lastKey()]
    }

    override fun close() {}

    private fun blockHeight(o: JsonObject): Int {
        val status = o["status"]?.jsonObject ?: return 0
        if(status["confirmed"]?.jsonPrimitive?.boolean != true) return 0
        return status["block_height"]?.jsonPrimitive?.intOrNull ?: 0
    }

    private fun failure(r: HttpEndpoint.Response) = "${http.url} answered HTTP ${r.status}: ${r.body.take(200)}"

    companion object {
        private const val PAGE_SIZE = 25
        /** A wallet address with more than 1,000 confirmed transactions is cut off rather than paged forever. */
        private const val MAX_PAGES = 40
        private val ALREADY = listOf("already known", "already-known", "already in", "already exists", "txn-already")
        private val MEMPOOL_TARGETS = listOf("fastestFee" to 1, "halfHourFee" to 3, "hourFee" to 6, "economyFee" to 25)
    }
}
