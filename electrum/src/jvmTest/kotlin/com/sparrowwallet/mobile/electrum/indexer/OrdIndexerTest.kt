package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OrdIndexer against a local HTTP server that answers per method and path and logs every request. */
class OrdIndexerTest {
    private val inscription = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64i0"
    private val requests = CopyOnWriteArrayList<String>()
    /** Content-Type and Accept of each request, in order. */
    private val headers = CopyOnWriteArrayList<Pair<String?, String?>>()
    private var routes: (method: String, path: String, body: String) -> Pair<Int, String> = { _, _, _ -> 404 to "not found" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += "${ex.requestMethod} ${ex.requestURI.path}"
            headers += ex.requestHeaders.getFirst("Content-Type") to ex.requestHeaders.getFirst("Accept")
            val (status, out) = routes(ex.requestMethod, ex.requestURI.path, body)
            val bytes = out.encodeToByteArray()
            if(status == 429) ex.responseHeaders.add("Retry-After", "5")
            ex.sendResponseHeaders(status, if(bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest fun stop() {
        server.stop(0)
        NetworkPolicy.blocked = false
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/indexer/$name")) { name }.use { it.readBytes().decodeToString() }

    private fun outpoint(n: Int) = n.toString(16).padStart(64, '0') + ":0"
    private val status = """{"chain":"mainnet","height":5,"rune_index":true}"""
    private fun carrying(id: String) = """{"indexed":true,"inscriptions":["$id"],"runes":{},"spent":false,"transaction":"${id.take(64)}"}"""
    private fun clean(txid: String) = """{"indexed":true,"inscriptions":[],"runes":{},"spent":false,"transaction":"$txid"}"""

    @Test fun statusSaysWhetherRunesAreIndexed(): Unit = runBlocking {
        routes = { _, path, _ -> if(path == "/status") 200 to fixture("status-mainnet.json") else 404 to "" }
        val indexer = OrdIndexer(base, null)
        assertFalse(indexer.runesAware)
        val s = indexer.status()
        assertEquals("mainnet", s.chain)
        assertTrue(indexer.runesAware)
        assertEquals("application/json", headers.single().second)
    }

    /** POST /outputs in batches of 100, as a JSON array with Content-Type application/json; answers matched by position. */
    @Test fun outputsArePostedInBatches(): Unit = runBlocking {
        val asked = (1..150).map { outpoint(it) }
        val carrier = asked[42].take(64) + "i0"
        routes = { method, path, body ->
            when {
                path == "/status" -> 200 to status
                method == "POST" && path == "/outputs" -> 200 to Json.parseToJsonElement(body).jsonArray.joinToString(",", "[", "]") {
                    val o = it.jsonPrimitive.content
                    if(o == asked[42]) carrying(carrier) else clean(o.take(64))
                }
                else -> 404 to ""
            }
        }
        val indexer = OrdIndexer(base, null)
        val outputs = indexer.outputs(asked + asked[3])
        assertEquals(asked.toSet(), outputs.keys)
        assertEquals(listOf(carrier), outputs.getValue(asked[42]).inscriptions)
        assertTrue(outputs.getValue(asked[42]).carries)
        assertTrue(outputs.getValue(asked[7]).indexed)
        assertFalse(outputs.getValue(asked[7]).carries)
        assertEquals(listOf("GET /status", "POST /outputs", "POST /outputs"), requests.toList())
        assertEquals(listOf("application/json", "application/json"), headers.drop(1).map { it.first })
        assertTrue(headers.all { it.second == "application/json" })
    }

    @Test fun noOutputsAskedMeansNoRequest(): Unit = runBlocking {
        assertEquals(emptyMap(), OrdIndexer(base, null).outputs(emptyList()))
        assertTrue(requests.isEmpty())
    }

    /** Older servers have no batch route: each output is asked on its own, and one the indexer does not know is unknown. */
    @Test fun noBatchRouteFallsBackToOneRequestPerOutput(): Unit = runBlocking {
        for(refusal in listOf(405, 404)) {
            requests.clear()
            val a = outpoint(1)
            val b = outpoint(2)
            routes = { method, path, _ ->
                when {
                    path == "/status" -> 200 to status
                    method == "POST" -> refusal to "Method Not Allowed"
                    path == "/output/$a" -> 200 to carrying(a.take(64) + "i0")
                    else -> 404 to "output not found"
                }
            }
            val outputs = OrdIndexer(base, null).outputs(listOf(a, b))
            assertEquals(listOf(a.take(64) + "i0"), outputs.getValue(a).inscriptions)
            assertEquals(IndexerOutput.unknown(b), outputs.getValue(b))
            assertEquals(listOf("GET /status", "POST /outputs", "GET /output/$a", "GET /output/$b"), requests.toList(), "$refusal")
        }
    }

    /** Without a rune index an output showing nothing is unknown, not clean. */
    @Test fun noRuneIndexMakesAnEmptyOutputUnknown(): Unit = runBlocking {
        val a = outpoint(1)
        routes = { method, path, _ ->
            when {
                path == "/status" -> 200 to """{"chain":"mainnet","rune_index":false}"""
                method == "POST" -> 200 to "[${clean(a.take(64))}]"
                else -> 404 to ""
            }
        }
        val indexer = OrdIndexer(base, null)
        assertFalse(indexer.outputs(listOf(a)).getValue(a).indexed)
        assertFalse(indexer.runesAware)
    }

    @Test fun rateLimitIsNotWaitedOut(): Unit = runBlocking {
        routes = { _, _, _ -> 429 to "Too Many Requests" }
        val started = System.nanoTime()
        assertFailsWith<RateLimitedException> { OrdIndexer(base, null).outputs(listOf(outpoint(1))) }
        assertEquals(listOf("GET /status"), requests.toList())

        //with the status known, the batch request is the one refused
        requests.clear()
        routes = { _, path, _ -> if(path == "/status") 200 to status else 429 to "Too Many Requests" }
        val indexer = OrdIndexer(base, null).also { it.status() }
        requests.clear()
        assertFailsWith<RateLimitedException> { indexer.outputs(listOf(outpoint(1))) }
        assertEquals(listOf("GET /status", "POST /outputs"), requests.toList())
        requests.clear()
        assertFailsWith<RateLimitedException> { indexer.inscription(inscription) }
        assertEquals(listOf("GET /inscription/$inscription"), requests.toList())

        //and in the one-by-one fallback
        requests.clear()
        routes = { method, path, _ -> if(path == "/status") 200 to status else if(method == "POST") 405 to "" else 429 to "Too Many Requests" }
        assertFailsWith<RateLimitedException> { indexer.outputs(listOf(outpoint(1), outpoint(2))) }
        assertEquals(listOf("GET /status", "POST /outputs", "GET /output/${outpoint(1)}"), requests.toList())
        assertTrue(System.nanoTime() - started < 4_000_000_000L, "no Retry-After wait")
    }

    /** A server error is an error, never an empty answer. */
    @Test fun otherErrorsThrow(): Unit = runBlocking {
        val a = outpoint(1)
        routes = { _, path, _ -> if(path == "/status") 503 to "down" else 404 to "" }
        assertFailsWith<IndexerException> { OrdIndexer(base, null).status() }

        routes = { _, path, _ -> if(path == "/status") 200 to status else 500 to "oops" }
        assertFailsWith<IndexerException> { OrdIndexer(base, null).outputs(listOf(a)) }
        assertFailsWith<IndexerException> { OrdIndexer(base, null).inscription(inscription) }

        routes = { method, path, _ -> if(path == "/status") 200 to status else if(method == "POST") 405 to "" else 500 to "oops" }
        assertFailsWith<IndexerException> { OrdIndexer(base, null).outputs(listOf(a)) }

        routes = { method, path, _ -> if(path == "/status") 200 to status else if(method == "POST") 200 to "[]" else 404 to "" }
        assertFailsWith<IndexerException> { OrdIndexer(base, null).outputs(listOf(a)) }

        routes = { _, _, _ -> 200 to "<html>not ord</html>" }
        assertFailsWith<IndexerException> { OrdIndexer(base, null).status() }
    }

    @Test fun inscriptionIsReadAndMustBeTheOneAsked(): Unit = runBlocking {
        val other = "ab".repeat(32) + "i0"
        routes = { _, path, _ ->
            when(path) {
                "/inscription/$inscription" -> 200 to fixture("inscription-text-mainnet.json")
                "/inscription/$other" -> 200 to fixture("inscription-text-mainnet.json")
                else -> 404 to "inscription not found"
            }
        }
        val indexer = OrdIndexer(base, null)
        assertEquals(IndexerInscription(inscription, 26845519L, "text/plain;charset=utf-8", 63L), indexer.inscription(inscription))
        assertNull(indexer.inscription("cd".repeat(32) + "i1"))
        assertFailsWith<IndexerException> { indexer.inscription(other) }
    }

    @Test fun badIdIsRejected(): Unit = runBlocking {
        val indexer = OrdIndexer(base, null)
        assertFailsWith<IllegalArgumentException> { indexer.inscription("../status") }
        assertFailsWith<IllegalArgumentException> { indexer.inscription("$inscription/../x") }
        assertFailsWith<IllegalArgumentException> { indexer.outputs(listOf("abc:0")) }
        assertFailsWith<IllegalArgumentException> { indexer.outputs(listOf("AB".repeat(32) + ":0")) }
        assertFailsWith<IllegalArgumentException> { indexer.outputs(listOf(outpoint(1) + "/../x")) }
        assertTrue(requests.isEmpty())
    }

    /** A stale rune_index:true must not keep vouching: the status is read again, once, for each outputs call. */
    @Test fun statusIsReadAgainForEachOutputsCall(): Unit = runBlocking {
        val asked = (1..150).map { outpoint(it) }
        var runeIndex = true
        routes = { method, path, body ->
            when {
                path == "/status" -> 200 to """{"chain":"mainnet","rune_index":$runeIndex}"""
                method == "POST" -> 200 to Json.parseToJsonElement(body).jsonArray.joinToString(",", "[", "]") { clean(it.jsonPrimitive.content.take(64)) }
                else -> 404 to ""
            }
        }
        val indexer = OrdIndexer(base, null)
        assertTrue(indexer.outputs(asked).values.all { it.indexed })
        assertEquals(listOf("GET /status", "POST /outputs", "POST /outputs"), requests.toList())
        runeIndex = false
        requests.clear()
        assertTrue(indexer.outputs(asked).values.none { it.indexed })
        assertFalse(indexer.runesAware)
        assertEquals(listOf("GET /status", "POST /outputs", "POST /outputs"), requests.toList())
    }

    /** ord 0.20's answers name the transaction but not the output, so two outputs of one transaction never share a request. */
    @Test fun outputsOfOneTransactionGoInSeparateRequests(): Unit = runBlocking {
        val txid = "ab".repeat(32)
        val asked = listOf("$txid:0", outpoint(1), "$txid:1", outpoint(2), "$txid:2")
        val batches = CopyOnWriteArrayList<List<String>>()
        routes = { method, path, body ->
            when {
                path == "/status" -> 200 to status
                method == "POST" -> {
                    val batch = Json.parseToJsonElement(body).jsonArray.map { it.jsonPrimitive.content }
                    batches += batch
                    200 to batch.joinToString(",", "[", "]") { clean(it.take(64)) }
                }
                else -> 404 to ""
            }
        }
        val outputs = OrdIndexer(base, null).outputs(asked)
        assertEquals(asked.toSet(), outputs.keys)
        assertEquals(listOf(listOf("$txid:0", outpoint(1), outpoint(2)), listOf("$txid:1"), listOf("$txid:2")), batches.toList())
    }

    /** Plain http through the proxy could be read or forged by an exit node: refused unless the server is an onion service. */
    @Test fun plainHttpThroughTheProxyIsRefused() {
        val proxy = com.sparrowwallet.mobile.electrum.SocksProxy("127.0.0.1", 9050)
        val e = assertFailsWith<IndexerException> { OrdIndexer("http://ord.example", proxy) }
        assertEquals("This inscriptions indexer uses plain http, which is not safe through a proxy or Tor. Use an https or .onion indexer.", e.message)
        assertFailsWith<IndexerException> { OrdIndexer("http://ord.example.onion.evil.com", proxy) }
        OrdIndexer("http://abcdefghijklmnop.onion", proxy).close()
        OrdIndexer("http://ABCDEFGHIJKLMNOP.ONION:8080", proxy).close()
        OrdIndexer("https://ordinalslite.com", proxy).close()
        //an own server on the LAN without a proxy
        OrdIndexer("http://192.168.1.20:8080", null).close()
        assertTrue(requests.isEmpty())
    }

    @Test fun airGappedRefuses(): Unit = runBlocking {
        routes = { _, _, _ -> 200 to status }
        NetworkPolicy.blocked = true
        val e = assertFailsWith<AirGappedException> { OrdIndexer(base, null).status() }
        assertEquals("Inscription indexer", e.what)
        assertFailsWith<AirGappedException> { OrdIndexer(base, null).outputs(listOf(outpoint(1))) }
        assertFailsWith<AirGappedException> { OrdIndexer(base, null).inscription(inscription) }
        //Esplora keeps its own name in the message
        assertEquals("Esplora", assertFailsWith<AirGappedException> { HttpEndpoint(base).get("/blocks/tip/height") }.what)
        assertTrue(requests.isEmpty())
    }
}
