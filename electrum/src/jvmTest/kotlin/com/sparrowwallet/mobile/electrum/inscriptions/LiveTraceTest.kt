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
import com.sparrowwallet.mobile.electrum.InscriptionIndex
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
 * Bellscoin mainnet (Esplora backend, Nintondo indexer) and Litecoin mainnet (Electrum backend, ordinalslite). The
 * outpoints are looked up from the public indexers at test time (a recent inscription's current location), so nothing
 * is hard-coded that may move. A 429 or an unreachable public server skips the case with a message: it says nothing
 * about the port. Nothing is written to any chain.
 */
class LiveTraceTest {
    private val enabled = System.getenv("LIVE_TRACE") == "1"

    private class Skip(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun getJson(http: HttpEndpoint, path: String) = http.get(path, "application/json").let {
        if(it.status == 429) throw Skip("429 from ${http.url}$path")
        if(it.status != 200) throw Skip("HTTP ${it.status} from ${http.url}$path")
        json.parseToJsonElement(it.body)
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

    @Test fun bellscoinInscriptionIsTraced() {
        if(!enabled) return
        guarded("bellscoin") {
            val indexUrl = InscriptionIndex.publicUrl("bellscoin", "mainnet")!!
            val index = HttpEndpoint(indexUrl)
            val backend = EsploraBackend("https://api.nintondo.io", ChainParams.of(Coin.BELLSCOIN, Network.MAINNET))
            try {
                //Walk back from the tip until an output the indexer says carries inscriptions, and keep one that carries none
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
                                    val metas = runCatching { getJson(index, "/find_meta/${txid}i$vout") }.getOrNull() as? JsonArray ?: continue
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
                val result = trace(Coin.BELLSCOIN, backend, found.first, found.second)
                report("bellscoin inscribed", "${found.first}:${found.second}", found.third, result)
                if(result.reason?.startsWith(InscriptionTracer.SERVER_ERROR_PREFIX) == true && result.reason!!.contains("429")) {
                    throw Skip("429 while tracing: ${result.reason}")
                }
                assertEquals(Verdict.INSCRIBED, result.verdict)
                assertTrue(result.found.any { it.inscriptionId in found.third }, "found ${result.found.map { it.inscriptionId }} vs indexer ${found.third}")

                clean?.let {
                    val neg = trace(Coin.BELLSCOIN, backend, it.first, it.second)
                    report("bellscoin indexer-clean", "${it.first}:${it.second}", emptyList(), neg)
                    assertNotEquals(Verdict.INSCRIBED, neg.verdict)
                } ?: println("LIVETRACE bellscoin no indexer-clean output seen in the same transaction")
            } finally {
                backend.close()
                index.close()
            }
        }
    }

    @Test fun litecoinInscriptionIsTraced() {
        if(!enabled) return
        guarded("litecoin") {
            val ord = HttpEndpoint(InscriptionIndex.publicUrl("litecoin", "mainnet")!!)
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
                    report("litecoin indexer-clean", "${it.first}:${it.second}", emptyList(), neg)
                    assertNotEquals(Verdict.INSCRIBED, neg.verdict)
                } ?: println("LIVETRACE litecoin no indexer-clean sibling output among the newest inscription transactions")
            } finally {
                client.close()
                ord.close()
            }
        }
    }
}
