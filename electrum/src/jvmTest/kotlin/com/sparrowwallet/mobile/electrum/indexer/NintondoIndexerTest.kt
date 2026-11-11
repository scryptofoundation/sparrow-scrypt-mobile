package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.SocksProxy
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NintondoIndexer against a local HTTP server standing in for both the api and the search host. */
class NintondoIndexerTest {
    private val dogeId = "b5f9d2759c65df72dff76bbab840ff3d34d99c6679bad26148cb18344ff03f71i0"
    private val requests = CopyOnWriteArrayList<String>()
    private var routes: (method: String, path: String) -> Pair<Int, String> = { _, _ -> 404 to "not found" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            ex.requestBody.readBytes()
            requests += "${ex.requestMethod} ${ex.requestURI.path}"
            val (status, out) = routes(ex.requestMethod, ex.requestURI.path)
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
    private fun indexer(coin: Coin = Coin.DOGECOIN, network: Network = Network.MAINNET) = NintondoIndexer(base, null, coin, network, "$base/pub")

    /** Nintondo has no rune or dune index: never runes-aware, whatever its status says. */
    @Test fun neverRunesAware(): Unit = runBlocking {
        routes = { _, path -> if(path == "/status") 200 to """{"network":"Dogecoin","ord_height":5,"rune_index":true}""" else 404 to "" }
        val indexer = indexer()
        assertFalse(indexer.runesAware)
        val s = indexer.status()
        assertEquals(IndexerStatus("mainnet", false, 5L), s)
        assertFalse(indexer.runesAware)
    }

    /** One find_meta per output, as txid i vout; a 404 is unknown; an empty answer is unknown; every asked output gets an entry. */
    @Test fun outputsAskFindMetaPerOutput(): Unit = runBlocking {
        val carrier = dogeId.take(64) + ":0"
        val empty = outpoint(1)
        val missing = outpoint(2)
        routes = { _, path ->
            when(path) {
                "/find_meta/${dogeId.take(64)}i0" -> 200 to fixture("nintondo-doge-find-meta.json")
                "/find_meta/${empty.take(64)}i0" -> 200 to "[]"
                else -> 404 to "not found"
            }
        }
        val outputs = indexer().outputs(listOf(carrier, empty, missing, carrier))
        assertEquals(listOf(carrier, empty, missing), outputs.keys.toList())
        assertEquals(listOf(dogeId), outputs.getValue(carrier).inscriptions)
        assertTrue(outputs.getValue(carrier).indexed)
        assertEquals(IndexerOutput.unknown(empty), outputs.getValue(empty))
        assertEquals(IndexerOutput.unknown(missing), outputs.getValue(missing))
        assertEquals(listOf("GET /find_meta/${dogeId.take(64)}i0", "GET /find_meta/${empty.take(64)}i0", "GET /find_meta/${missing.take(64)}i0"), requests.toList())
    }

    @Test fun noOutputsAskedMeansNoRequest(): Unit = runBlocking {
        assertEquals(emptyMap(), indexer().outputs(emptyList()))
        assertTrue(requests.isEmpty())
    }

    @Test fun rateLimitIsNotWaitedOut(): Unit = runBlocking {
        routes = { _, _ -> 429 to "Too Many Requests" }
        val started = System.nanoTime()
        assertFailsWith<RateLimitedException> { indexer().outputs(listOf(outpoint(1), outpoint(2))) }
        assertEquals(listOf("GET /find_meta/${outpoint(1).take(64)}i0"), requests.toList())
        requests.clear()
        assertFailsWith<RateLimitedException> { indexer().status() }
        requests.clear()
        assertFailsWith<RateLimitedException> { indexer().inscription(dogeId) }
        assertEquals(listOf("GET /pub/$dogeId/info"), requests.toList())
        assertTrue(System.nanoTime() - started < 4_000_000_000L, "no Retry-After wait")
    }

    /** A server error or an unreadable answer is an error, never an empty answer. */
    @Test fun otherErrorsThrow(): Unit = runBlocking {
        routes = { _, _ -> 500 to "oops" }
        assertFailsWith<IndexerException> { indexer().outputs(listOf(outpoint(1))) }
        assertFailsWith<IndexerException> { indexer().status() }
        assertFailsWith<IndexerException> { indexer().inscription(dogeId) }
        routes = { _, _ -> 200 to """{"error":"Invalid URL: Wrong type"}""" }
        assertFailsWith<IndexerException> { indexer().outputs(listOf(outpoint(1))) }
        routes = { _, _ -> 200 to "<html>" }
        assertFailsWith<IndexerException> { indexer().outputs(listOf(outpoint(1))) }
        assertFailsWith<IndexerException> { indexer().status() }
        assertFailsWith<IndexerException> { indexer().inscription(dogeId) }
    }

    /**
     * Details come from the search host's info alone, which must be for the id asked; an unknown inscription is null.
     * The api host's location is not asked: the phone finds where an inscription is on the chain, and the details hold no
     * place.
     */
    @Test fun inscriptionReadsInfoOnly(): Unit = runBlocking {
        routes = { _, path -> if(path == "/pub/$dogeId/info") 200 to fixture("nintondo-doge-info.json") else 404 to "not found" }
        assertEquals(IndexerInscription(dogeId, 180433203L, "text/plain; charset=utf-8", 15L), indexer().inscription(dogeId))
        assertEquals(listOf("GET /pub/$dogeId/info"), requests.toList())
        assertNull(indexer().inscription("cd".repeat(32) + "i1"))

        //info for another inscription is refused
        val other = "ab".repeat(32) + "i0"
        routes = { _, _ -> 200 to fixture("nintondo-doge-info.json") }
        assertFailsWith<IndexerException> { indexer().inscription(other) }
        assertTrue(requests.none { it.startsWith("GET /location/") })
    }

    /** A host that is not Nintondo's api shape has no search host: no request is made for details. */
    @Test fun noSearchHostMeansNoDetails(): Unit = runBlocking {
        routes = { _, _ -> 200 to fixture("nintondo-doge-location.json") }
        assertFailsWith<IndexerException> { NintondoIndexer(base, null, Coin.DOGECOIN, Network.MAINNET).inscription(dogeId) }
        assertTrue(requests.isEmpty())
    }

    @Test fun badIdIsRejected(): Unit = runBlocking {
        val indexer = indexer()
        assertFailsWith<IllegalArgumentException> { indexer.inscription("../status") }
        assertFailsWith<IllegalArgumentException> { indexer.outputs(listOf("abc:0")) }
        assertFailsWith<IllegalArgumentException> { indexer.outputs(listOf(outpoint(1) + "/../x")) }
        assertTrue(requests.isEmpty())
    }

    /** The search and content hosts are the api host's siblings, with its scheme and port. */
    @Test fun nintondoHostsDerivedFromApi() {
        assertEquals("https://doge-mainnet-search.nintondo.io/pub", NintondoIndexer.searchBase("https://doge-mainnet-api.nintondo.io"))
        assertEquals("https://bells-testnet-search.nintondo.io/pub", NintondoIndexer.searchBase("https://bells-testnet-api.nintondo.io/"))
        assertEquals("http://pepe-mainnet-search.nintondo.io:8080/pub", NintondoIndexer.searchBase("http://pepe-mainnet-api.nintondo.io:8080"))
        assertNull(NintondoIndexer.searchBase("https://nintondo.io"))
        assertNull(NintondoIndexer.searchBase("https://-api.nintondo.io"))
        assertNull(NintondoIndexer.searchBase("https://doge-mainnet-search.nintondo.io"))
        assertNull(NintondoIndexer.searchBase("not a url"))
    }

    /** The same rule as the ord reader: plain http through the proxy is refused unless the server is an onion service. */
    @Test fun plainHttpThroughTheProxyIsRefused() {
        val proxy = SocksProxy("127.0.0.1", 9050)
        val e = assertFailsWith<IndexerException> { NintondoIndexer("http://doge-mainnet-api.nintondo.io", proxy, Coin.DOGECOIN, Network.MAINNET) }
        assertEquals("This inscriptions indexer uses plain http, which is not safe through a proxy or Tor. Use an https or .onion indexer.", e.message)
        NintondoIndexer("https://doge-mainnet-api.nintondo.io", proxy, Coin.DOGECOIN, Network.MAINNET).close()
        NintondoIndexer("http://doge-mainnet-api.nintondo.io", null, Coin.DOGECOIN, Network.MAINNET).close()
        assertTrue(requests.isEmpty())
    }

    @Test fun airGappedRefuses(): Unit = runBlocking {
        routes = { _, _ -> 200 to "[]" }
        NetworkPolicy.blocked = true
        assertEquals("Inscription indexer", assertFailsWith<AirGappedException> { indexer().status() }.what)
        assertFailsWith<AirGappedException> { indexer().outputs(listOf(outpoint(1))) }
        assertFailsWith<AirGappedException> { indexer().inscription(dogeId) }
        assertTrue(requests.isEmpty())
    }
}
