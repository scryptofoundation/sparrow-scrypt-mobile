package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.InscriptionTracer
import com.sparrowwallet.mobile.crypto.track.KnownResults
import com.sparrowwallet.mobile.crypto.track.TraceResult
import com.sparrowwallet.mobile.crypto.track.TxCache
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.electrum.BackendChainSource
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.ElectrumBackend
import com.sparrowwallet.mobile.electrum.ElectrumClient
import com.sparrowwallet.mobile.electrum.EsploraBackend
import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.indexer.Indexers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Opt-in (LIVE_TRACE=1), read only: the phone's own trace, with no indexer, against inscriptions that exist now on
 * Bellscoin and Pepecoin mainnet (Esplora backends, Nintondo indexers) and Litecoin mainnet (Electrum backend,
 * ordinalslite). The outpoints are looked up from the public indexers at test time (a recent inscription's current
 * location), so nothing is hard-coded that may move. Junkcoin has no public indexer: its leg traces a recent mined coin
 * and reports what comes back. Each Esplora leg also reports whether the server's block listing carries fees. A 429 or
 * an unreachable public server skips the case with a message: it says nothing about the port. Nothing is written to any
 * chain.
 */
class LiveTraceTest {
    private val enabled = System.getenv("LIVE_TRACE") == "1"

    private class Skip(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun getJson(http: HttpEndpoint, path: String) = getJsonOrNone(http, path)
        ?: throw Skip("HTTP 404 from ${http.url}$path")

    /** The answer, or null for a 404 (nothing there); a 429 or any other failure skips the leg, never reads as "none". */
    private suspend fun getJsonOrNone(http: HttpEndpoint, path: String) = http.get(path, "application/json").let {
        if(it.status == 429) throw Skip("429 from ${http.url}$path")
        if(it.status == 404) return@let null
        if(it.status != 200) throw Skip("HTTP ${it.status} from ${http.url}$path")
        json.parseToJsonElement(it.body)
    }

    /**
     * One output's find_meta: the answer, or null when the indexer cannot say (any status but 200 and 429, as the app's
     * old InscriptionIndex read it), which skips that output only. A 429 still skips the whole leg.
     */
    private suspend fun findMeta(http: HttpEndpoint, path: String) = http.get(path, "application/json").let {
        if(it.status == 429) throw Skip("429 from ${http.url}$path")
        if(it.status != 200) null else json.parseToJsonElement(it.body)
    }

    /** A trace that did not reach a verdict is inconclusive, not a pass: say so and why. */
    private fun reportNegative(label: String, outpoint: String, r: TraceResult) {
        report(label, outpoint, emptyList(), r)
        if(r.verdict == Verdict.UNCHECKED) {
            println("LIVETRACE $label INCONCLUSIVE: the trace left $outpoint Unchecked (${r.reason})")
        }
    }

    /** Review M3: whether the Esplora server's /block/:hash/txs answers carry each transaction's fee. */
    private suspend fun reportFees(label: String, esplora: HttpEndpoint) {
        //a recent block with more than its coinbase (/blocks lists the latest ten), so the answer says something
        val block = getJson(esplora, "/blocks").jsonArray.map { it.jsonObject }
            .firstOrNull { (it["tx_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) >= 2 }
            ?: throw Skip("none of the latest blocks holds more than its coinbase")
        val hash = block["id"]!!.jsonPrimitive.content
        val txs = getJson(esplora, "/block/$hash/txs/0").jsonArray
        val nonCoinbase = txs.drop(1).map { it.jsonObject }
        val withFee = nonCoinbase.count { "fee" in it }
        println("LIVETRACE $label block-fees ${esplora.url}/block/$hash/txs/0 (height ${block["height"]}): ${txs.size} txs, " +
            "$withFee of ${nonCoinbase.size} non-coinbase carry fee")
    }

    private suspend fun trace(coin: Coin, backend: ChainBackend, txid: String, vout: Int): TraceResult {
        val tracer = InscriptionTracer(coin, Network.MAINNET, BackendChainSource(backend), KnownResults.NONE, TxCache())
        return tracer.trace(txid, vout, emptyList(), InscriptionTracer.DEEPER_BUDGET, true)
    }

    private fun report(label: String, outpoint: String, expected: List<String>, r: TraceResult) =
        println("LIVETRACE $label $outpoint expected=$expected -> ${r.verdict} found=${r.found.map { it.inscriptionId }} lookups=${r.lookups} reason=${r.reason}")

    private fun guarded(label: String, body: suspend () -> Unit) = runBlocking {
        try {
            body()
        } catch(e: Skip) {
            println("LIVETRACE $label SKIPPED: ${e.message}")
        } catch(e: java.io.IOException) {
            println("LIVETRACE $label SKIPPED (server unreachable): $e")
        }
    }

    /**
     * Walks back from the indexer's tip until an output it says carries a few inscriptions, and keeps a sibling it says
     * carries none; traces both through the coin's Esplora backend.
     */
    private fun nintondoLeg(label: String, coin: Coin, esploraUrl: String) = guarded(label) {
        val index = HttpEndpoint(Indexers.publicUrl(coin, Network.MAINNET)!!)
        val backend = EsploraBackend(esploraUrl, ChainParams.of(coin, Network.MAINNET))
        try {
            var height = (getJson(index, "/status").jsonObject["tip_height"]!!.jsonPrimitive.content).toInt()
            var hit: Triple<String, Int, List<String>>? = null
            var clean: Pair<String, Int>? = null
            var scanned = 0
            while(height > 0 && (hit == null || clean == null) && scanned < 4000) {
                val blocks = getJson(index, "/blocks/$height").jsonArray
                if(blocks.isEmpty()) break
                for(b in blocks) {
                    val block = b.jsonObject
                    height = block["height"]!!.jsonPrimitive.content.toInt() - 1
                    scanned++
                    val count = block["tx_count"]!!.jsonPrimitive.content.toInt()
                    if(count < 2) continue
                    for(start in 0 until count step 25) {
                        for(t in getJson(index, "/block/${block["id"]!!.jsonPrimitive.content}/txs/$start").jsonArray) {
                            val tx = t.jsonObject
                            val txid = tx["txid"]!!.jsonPrimitive.content
                            for(vout in 0 until tx["vout"]!!.jsonArray.size) {
                                //An output the indexer cannot say about is passed over; a 429 skips the leg rather than reading as clean
                                val metas = findMeta(index, "/find_meta/${txid}i$vout") as? JsonArray ?: continue
                                //An output holding a dozen inscriptions is a collector's coin with a long history; a small holder is the useful probe
                                if(metas.isNotEmpty() && metas.size <= 3 && hit == null) {
                                    hit = Triple(txid, vout, metas.map { it.jsonObject["genesis"]!!.jsonPrimitive.content })
                                } else if(metas.isEmpty() && clean == null && hit?.first == txid) {
                                    clean = txid to vout
                                }
                            }
                        }
                    }
                }
            }
            val found = hit ?: throw Skip("no inscribed output found in the last $scanned blocks")
            val result = trace(coin, backend, found.first, found.second)
            report("$label inscribed", "${found.first}:${found.second}", found.third, result)
            if(result.reason?.startsWith(InscriptionTracer.SERVER_ERROR_PREFIX) == true && result.reason!!.contains("429")) {
                throw Skip("429 while tracing: ${result.reason}")
            }
            assertEquals(Verdict.INSCRIBED, result.verdict)
            assertTrue(result.found.any { it.inscriptionId in found.third }, "found ${result.found.map { it.inscriptionId }} vs indexer ${found.third}")

            clean?.let {
                val neg = trace(coin, backend, it.first, it.second)
                reportNegative("$label indexer-clean", "${it.first}:${it.second}", neg)
                assertNotEquals(Verdict.INSCRIBED, neg.verdict)
            } ?: println("LIVETRACE $label no indexer-clean output seen in the same transaction")
        } finally {
            backend.close()
            index.close()
        }
    }

    @Test fun bellscoinInscriptionIsTraced() {
        if(!enabled) return
        nintondoLeg("bellscoin", Coin.BELLSCOIN, "https://api.nintondo.io")
    }

    @Test fun pepecoinInscriptionIsTraced() {
        if(!enabled) return
        nintondoLeg("pepecoin", Coin.PEPECOIN, "https://peppool.space/api")
    }

    /**
     * Junkcoin has no public indexer, so nothing is asserted: a mined coin from a recent block (its coinbase output 0,
     * so the trace must place the block's fees) is traced through the Esplora backend and its verdict reported.
     */
    @Test fun junkcoinMinedCoinIsReported() {
        if(!enabled) return
        guarded("junkcoin") {
            val esplora = HttpEndpoint("https://junk-api.s3na.xyz")
            val backend = EsploraBackend("https://junk-api.s3na.xyz", ChainParams.of(Coin.JUNKCOIN, Network.MAINNET))
            try {
                val tip = esplora.get("/blocks/tip/height").let { if(it.status == 200) it.body.trim().toInt() else throw Skip("HTTP ${it.status} for the tip") }
                val hash = esplora.get("/block-height/${tip - 120}").let { if(it.status == 200) it.body.trim() else throw Skip("HTTP ${it.status} for a block hash") }
                val coinbase = getJson(esplora, "/block/$hash/txs/0").jsonArray.first().jsonObject["txid"]!!.jsonPrimitive.content
                val result = trace(Coin.JUNKCOIN, backend, coinbase, 0)
                report("junkcoin mined", "$coinbase:0 (block ${tip - 120})", emptyList(), result)
                if(result.verdict == Verdict.UNCHECKED) {
                    println("LIVETRACE junkcoin INCONCLUSIVE: ${result.reason}")
                }
            } finally {
                backend.close()
                esplora.close()
            }
        }
    }

    /** Review M3: whether each Esplora server's block listing carries fees (the tracer needs them for mined coins). */
    @Test fun esploraBlockListingsReportFees() {
        if(!enabled) return
        for((label, url) in listOf("bellscoin" to "https://api.nintondo.io", "pepecoin" to "https://peppool.space/api",
                                   "junkcoin" to "https://junk-api.s3na.xyz")) {
            guarded("$label fees") {
                val esplora = HttpEndpoint(url)
                try {
                    reportFees(label, esplora)
                } finally {
                    esplora.close()
                }
            }
        }
    }

    @Test fun litecoinInscriptionIsTraced() {
        if(!enabled) return
        guarded("litecoin") {
            val ord = HttpEndpoint(Indexers.publicUrl(Coin.LITECOIN, Network.MAINNET)!!)
            val client = listOf("electrum1.cipig.net" to 20063, "fallacy.fiatfaucet.com" to 50002, "electrum.jhoenicke.de" to 50091)
                .firstNotNullOfOrNull { (host, port) -> runCatching { ElectrumBackend(ElectrumClient(host, port, true)).also { it.connect() } }.getOrNull() }
                ?: throw Skip("no Litecoin Electrum server reachable")
            try {
                val ids = getJson(ord, "/inscriptions").jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                var hit: Triple<String, Int, List<String>>? = null
                var clean: Pair<String, Int>? = null
                for(id in ids.take(12)) {
                    val satpoint = getJson(ord, "/inscription/$id").jsonObject["satpoint"]!!.jsonPrimitive.content.split(":")
                    val txid = satpoint[0]
                    val vout = satpoint[1].toInt()
                    val out = getJson(ord, "/output/$txid:$vout").jsonObject
                    val carried = out["inscriptions"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    if(carried.isEmpty()) continue
                    if(hit == null) {
                        hit = Triple(txid, vout, carried)
                    }
                    if(clean == null) {
                        val outputs = com.sparrowwallet.mobile.crypto.RawTx.parse(client.rawTransaction(txid)).outputs.size
                        for(other in 0 until outputs) {
                            val o = getJson(ord, "/output/$txid:$other").jsonObject
                            if(other != vout && o["indexed"]?.jsonPrimitive?.content == "true" && (o["inscriptions"]?.jsonArray?.isEmpty() ?: true)) {
                                clean = txid to other
                                break
                            }
                        }
                    }
                    if(hit != null && clean != null) break
                }
                val found = hit ?: throw Skip("no indexed inscription output among the newest ids")
                val result = trace(Coin.LITECOIN, client, found.first, found.second)
                report("litecoin inscribed", "${found.first}:${found.second}", found.third, result)
                if(result.reason?.contains("429") == true) throw Skip("429 while tracing: ${result.reason}")
                assertEquals(Verdict.INSCRIBED, result.verdict)
                assertTrue(result.found.any { it.inscriptionId in found.third }, "found ${result.found.map { it.inscriptionId }} vs indexer ${found.third}")

                clean?.let {
                    val neg = trace(Coin.LITECOIN, client, it.first, it.second)
                    reportNegative("litecoin indexer-clean", "${it.first}:${it.second}", neg)
                    assertNotEquals(Verdict.INSCRIBED, neg.verdict)
                } ?: println("LIVETRACE litecoin no indexer-clean sibling output among the newest inscription transactions")
            } finally {
                client.close()
                ord.close()
            }
        }
    }
}
