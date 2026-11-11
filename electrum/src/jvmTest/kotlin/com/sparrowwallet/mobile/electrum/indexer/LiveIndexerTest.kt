package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.InscriptionContent
import com.sparrowwallet.mobile.electrum.BackendChainSource
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.ElectrumBackend
import com.sparrowwallet.mobile.electrum.ElectrumClient
import com.sparrowwallet.mobile.electrum.EsploraBackend
import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.RateLimitedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in (LIVE_INDEXER=1), read only: the phone's indexer readers against the public Litecoin (ord, ordinalslite) and
 * Bellscoin (Nintondo) mainnet indexers, opened through [Indexers.open] so the chain checks run against the real servers.
 * For each: one output the indexer reports as inscribed and one with nothing on it, the inscription's details, and its
 * content read from the chain through the phone's own backend, compared with the indexer's type and length. Outputs and
 * ids are public facts taken from the indexers themselves, never from a wallet. A 429, an HTTP error answer or an
 * unreachable host skips the leg with a message; a refusal of the public URL (wrong chain, block 0, not a supported
 * kind) fails it. Nothing is written to any chain.
 */
class LiveIndexerTest {
    private val enabled = System.getenv("LIVE_INDEXER") == "1"

    private class Skip(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun getJson(http: HttpEndpoint, path: String) = http.get(path, "application/json").let {
        if(it.status == 429) throw Skip("429 from ${http.url}$path")
        if(it.status != 200) throw Skip("HTTP ${it.status} from ${http.url}$path")
        json.parseToJsonElement(it.body)
    }

    /**
     * Skips on what says nothing about the port (rate limited, an HTTP error page, no connection); any other
     * [IndexerException] is the reader refusing the public server, which is a failure.
     */
    private fun guarded(label: String, body: suspend () -> Unit) = runBlocking {
        try {
            body()
        } catch(e: Skip) {
            println("LIVEINDEXER $label SKIPPED: ${e.message}")
        } catch(e: RateLimitedException) {
            println("LIVEINDEXER $label SKIPPED (rate limited): ${e.message}")
        } catch(e: IndexerException) {
            if(e.message?.contains("answered HTTP") != true) throw e
            println("LIVEINDEXER $label SKIPPED (HTTP error): ${e.message}")
        } catch(e: IOException) {
            println("LIVEINDEXER $label SKIPPED (server unreachable): $e")
        }
    }

    private fun mediaType(type: String?) = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)

    /** The inscription's details from the indexer, and its content from the chain, compared. */
    private suspend fun checkContent(label: String, indexer: InscriptionIndexer, backend: ChainBackend, id: String) {
        val info = indexer.inscription(id)
        assertNotNull(info, "$label: the indexer does not know $id")
        println("LIVEINDEXER $label inscription $id number=${info.number} type=${info.contentType} length=${info.contentLength}")
        assertNotNull(info.number, "$label: no number for $id")
        assertNotNull(info.contentType, "$label: no content type for $id")
        //The reveal is the id's transaction; a one-step Doginals chain's last step is that same transaction
        val content = InscriptionContent.readFromChain(id, id.substringBefore('i'), BackendChainSource(backend), MAX_CONTENT)
        assertNotNull(content, "$label: no content read from the chain for $id")
        println("LIVEINDEXER $label content from chain type=${content.contentType} encoding=${content.contentEncoding} " +
            "size=${content.size} complete=${content.complete}")
        assertEquals(mediaType(info.contentType), mediaType(content.contentType), "$label: content type")
        info.contentLength?.let { assertEquals(it, content.size, "$label: content length") }
            ?: println("LIVEINDEXER $label the indexer gave no length to compare")
    }

    @Test fun litecoinOrdIndexerAgreesWithTheChain() {
        if(!enabled) return
        guarded("litecoin") {
            val url = Indexers.publicUrl(Coin.LITECOIN, Network.MAINNET)!!
            val raw = HttpEndpoint(url)
            var indexer: InscriptionIndexer? = null
            val client = listOf("electrum1.cipig.net" to 20063, "fallacy.fiatfaucet.com" to 50002, "electrum.jhoenicke.de" to 50091)
                .firstNotNullOfOrNull { (host, port) -> runCatching { ElectrumBackend(ElectrumClient(host, port, true)).also { it.connect() } }.getOrNull() }
                ?: throw Skip("no Litecoin Electrum server reachable")
            try {
                //Status and block 0 are checked against the real server here
                val ord = Indexers.open(url, Coin.LITECOIN, Network.MAINNET, null).also { indexer = it }
                val status = ord.status()
                println("LIVEINDEXER litecoin opened $url chain=${status.chain} height=${status.height} runeIndex=${status.runeIndex}")
                val source = BackendChainSource(client)
                //A recent inscription whose current transaction has another output to ask about
                val ids = getJson(raw, "/inscriptions").jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                var picked: Triple<String, String, String>? = null
                for(id in ids.take(5)) {
                    val satpoint = getJson(raw, "/inscription/$id").jsonObject["satpoint"]!!.jsonPrimitive.content.split(":")
                    val tx = source.transaction(satpoint[0]).tx
                    if(tx.outputs.size < 2) continue
                    val other = (0 until tx.outputs.size).first { it != satpoint[1].toInt() }
                    picked = Triple(id, "${satpoint[0]}:${satpoint[1]}", "${satpoint[0]}:$other")
                    break
                }
                val (id, inscribed, empty) = picked ?: throw Skip("none of the 5 newest inscriptions sits in a transaction with a second output")
                //What ord itself says of the other output, to know what the reader may vouch for
                val emptyRaw = getJson(raw, "/output/$empty").jsonObject
                val rawInscriptions = emptyRaw["inscriptions"]?.jsonArray?.size ?: 0
                val rawRunes = (emptyRaw["runes"] as? JsonObject)?.size ?: (emptyRaw["runes"]?.let { runCatching { it.jsonArray.size }.getOrNull() } ?: 0)
                val spent = emptyRaw["spent"]?.jsonPrimitive?.content
                val rawIndexed = emptyRaw["indexed"]?.jsonPrimitive?.content
                if(rawInscriptions > 0 || rawRunes > 0) throw Skip("the other output $empty carries something per ord")

                val outputs = ord.outputs(listOf(inscribed, empty))
                val hit = outputs.getValue(inscribed)
                val none = outputs.getValue(empty)
                println("LIVEINDEXER litecoin inscribed $inscribed -> indexed=${hit.indexed} inscriptions=${hit.inscriptions} runes=${hit.runes.keys} unlisted=${hit.unlisted}")
                println("LIVEINDEXER litecoin empty $empty (ord: indexed=$rawIndexed spent=$spent) -> indexed=${none.indexed} carries=${none.carries}")
                assertTrue(hit.indexed && hit.carries, "the inscribed output carries")
                assertTrue(id in hit.inscriptions, "$id is listed on $inscribed")
                assertFalse(none.carries, "the empty output carries nothing")
                //Empty is vouched for only with the rune index on, and only for an unspent, indexed output
                val vouched = ord.runesAware && rawIndexed == "true" && spent == "false"
                assertEquals(vouched, none.indexed, "the empty output is ${if(vouched) "indexed (clean)" else "unknown"}")
                println("LIVEINDEXER litecoin empty output read as ${if(none.indexed) "clean (rune index on, unspent)" else "unknown"}")

                checkContent("litecoin", ord, client, id)
            } finally {
                indexer?.close()
                client.close()
                raw.close()
            }
        }
    }

    @Test fun bellscoinNintondoIndexerAgreesWithTheChain() {
        if(!enabled) return
        guarded("bellscoin") {
            val url = Indexers.publicUrl(Coin.BELLSCOIN, Network.MAINNET)!!
            val raw = HttpEndpoint(url)
            val backend = EsploraBackend("https://api.nintondo.io", ChainParams.of(Coin.BELLSCOIN, Network.MAINNET))
            var indexer: InscriptionIndexer? = null
            try {
                val nintondo = Indexers.open(url, Coin.BELLSCOIN, Network.MAINNET, null).also { indexer = it }
                val status = nintondo.status()
                println("LIVEINDEXER bellscoin opened $url chain=${status.chain} height=${status.height} runeIndex=${status.runeIndex}")
                //A known public inscription (the recorded fixture's id); where it is now is asked live
                val fixture = javaClass.getResourceAsStream("/indexer/nintondo-bells-info.json")!!.use { it.readBytes().decodeToString() }
                val id = json.parseToJsonElement(fixture).jsonObject["id"]!!.jsonPrimitive.content
                val location = raw.get("/location/$id", "application/json").let {
                    if(it.status != 200) throw Skip("HTTP ${it.status} for /location/$id")
                    NintondoParse.location(it.body) ?: throw Skip("no location for $id")
                }
                val inscribed = "${location.first}:${location.second}"
                val tx = BackendChainSource(backend).transaction(location.first).tx
                //Another output of the same transaction, or else the output it spends: neither holds this inscription
                val empty = (0 until tx.outputs.size).firstOrNull { it != location.second }?.let { "${location.first}:$it" }
                    ?: "${tx.inputs[0].prevTxid}:${tx.inputs[0].prevVout}"

                val outputs = nintondo.outputs(listOf(inscribed, empty))
                val hit = outputs.getValue(inscribed)
                val none = outputs.getValue(empty)
                println("LIVEINDEXER bellscoin inscribed $inscribed -> indexed=${hit.indexed} inscriptions=${hit.inscriptions} unlisted=${hit.unlisted}")
                println("LIVEINDEXER bellscoin empty $empty -> indexed=${none.indexed} inscriptions=${none.inscriptions} carries=${none.carries}")
                assertTrue(hit.indexed && hit.carries, "the inscribed output carries")
                assertTrue(id in hit.inscriptions, "$id is listed on $inscribed")
                if(none.carries) throw Skip("Nintondo lists something on $empty, so it is not an empty output")
                //Nintondo's empty answer cannot tell clean from not indexed: unknown, never clean
                assertFalse(none.indexed, "Nintondo's empty answer is unknown, not clean")

                checkContent("bellscoin", nintondo, backend, id)
            } finally {
                indexer?.close()
                backend.close()
                raw.close()
            }
        }
    }

    private companion object {
        //The preview limit (PreviewRules.MAX_CONTENT_BYTES in the app); a larger body is counted, not assembled
        const val MAX_CONTENT = 400_000
    }
}
