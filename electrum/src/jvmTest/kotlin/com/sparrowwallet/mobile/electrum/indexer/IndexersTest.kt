package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Indexers.open against a local HTTP server: the right reader for the URL, and the chain checked before any answer is used. */
class IndexersTest {
    private val requests = CopyOnWriteArrayList<String>()
    private var routes: (method: String, path: String) -> Pair<Int, String> = { _, _ -> 404 to "not found" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            ex.requestBody.readBytes()
            requests += "${ex.requestMethod} ${ex.requestURI.path}"
            val (status, out) = routes(ex.requestMethod, ex.requestURI.path)
            val bytes = out.encodeToByteArray()
            ex.sendResponseHeaders(status, if(bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest fun stop() = server.stop(0)

    private val litecoinGenesis = "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2"
    private val litecoinTestnetGenesis = "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0"
    private val bitcoinGenesis = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
    private val dogeGenesis = "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691"
    private val mainnetStatus = """{"chain":"mainnet","height":5,"rune_index":true}"""

    private suspend fun open(coin: Coin, network: Network = Network.MAINNET) = Indexers.open(base, coin, network, null)

    @Test fun ordIsOpenedFromAStatusWithAChain(): Unit = runBlocking {
        routes = { _, path -> when(path) { "/status" -> 200 to mainnetStatus; "/blockhash/0" -> 200 to litecoinGenesis; else -> 404 to "" } }
        val indexer = open(Coin.LITECOIN)
        assertIs<OrdIndexer>(indexer)
        assertTrue(indexer.runesAware)
        //Litecoin too is checked by its network name and its genesis block (stricter than the desktop)
        assertEquals(listOf("GET /status", "GET /blockhash/0"), requests.toList())
        indexer.close()
        routes = { _, path -> when(path) { "/status" -> 200 to """{"chain":"testnet","rune_index":true}"""; "/blockhash/0" -> 200 to litecoinTestnetGenesis; else -> 404 to "" } }
        open(Coin.LITECOIN, Network.TESTNET).close()

        //every other coin by its genesis block too: /blockhash/0, quoted or bare
        for(answer in listOf(dogeGenesis, "\"$dogeGenesis\"", "$dogeGenesis\n")) {
            requests.clear()
            routes = { _, path -> when(path) { "/status" -> 200 to mainnetStatus; "/blockhash/0" -> 200 to answer; else -> 404 to "" } }
            open(Coin.DOGECOIN).close()
            assertEquals(listOf("GET /status", "GET /blockhash/0"), requests.toList(), answer)
        }

        //a server without /blockhash/0 names its block 0 in /block/0
        requests.clear()
        routes = { _, path -> when(path) { "/status" -> 200 to mainnetStatus; "/block/0" -> 200 to """{"hash":"$dogeGenesis","height":0}"""; else -> 404 to "" } }
        open(Coin.DOGECOIN).close()
        assertEquals(listOf("GET /status", "GET /blockhash/0", "GET /block/0"), requests.toList())

        //Bellscoin shares its genesis between mainnet and testnet: the status names the network
        requests.clear()
        val bellsGenesis = "e5be24df57c43a82d15c2f06bda961296948f8f8eb48501bed1efb929afe0698"
        routes = { _, path -> when(path) { "/status" -> 200 to """{"chain":"testnet"}"""; "/blockhash/0" -> 200 to bellsGenesis; else -> 404 to "" } }
        assertFalse(open(Coin.BELLSCOIN, Network.TESTNET).runesAware)
    }

    @Test fun wrongChainIsRefused(): Unit = runBlocking {
        //an ord status for another network
        routes = { _, path -> if(path == "/status") 200 to """{"chain":"testnet","rune_index":true}""" else 404 to "" }
        assertEquals("Inscriptions server is on testnet, wallet is on Mainnet", assertFailsWith<IndexerException> { open(Coin.LITECOIN) }.message)
        routes = { _, path -> if(path == "/status") 200 to mainnetStatus else 404 to "" }
        assertEquals("Inscriptions server is on mainnet, wallet is on Testnet", assertFailsWith<IndexerException> { open(Coin.LITECOIN, Network.TESTNET) }.message)

        //a non-Litecoin coin whose genesis hash differs: an ord-litecoin server set for Dogecoin
        requests.clear()
        routes = { _, path -> when(path) { "/status" -> 200 to mainnetStatus; "/blockhash/0" -> 200 to litecoinGenesis; else -> 404 to "" } }
        assertEquals("Inscriptions server is for another chain (its block 0 is 12a765e31ffd4059...), not a Dogecoin Mainnet server",
            assertFailsWith<IndexerException> { open(Coin.DOGECOIN) }.message)
        assertEquals(listOf("GET /status", "GET /blockhash/0"), requests.toList())
        //Dogecoin testnet's genesis is not mainnet's
        routes = { _, path -> when(path) { "/status" -> 200 to """{"chain":"testnet"}"""; "/blockhash/0" -> 200 to dogeGenesis; else -> 404 to "" } }
        assertEquals("Inscriptions server is for another chain (its block 0 is 1a91e3dace36e2be...), not a Dogecoin Testnet server",
            assertFailsWith<IndexerException> { open(Coin.DOGECOIN, Network.TESTNET) }.message)

        //the chain name must match exactly
        routes = { _, path -> when(path) { "/status" -> 200 to """{"chain":"MAINNET","rune_index":true}"""; "/blockhash/0" -> 200 to litecoinGenesis; else -> 404 to "" } }
        assertEquals("Inscriptions server is on MAINNET, wallet is on Mainnet", assertFailsWith<IndexerException> { open(Coin.LITECOIN) }.message)
        routes = { _, path -> when(path) { "/status" -> 200 to """{"chain":"mainnet "}"""; "/blockhash/0" -> 200 to dogeGenesis; else -> 404 to "" } }
        assertFailsWith<IndexerException> { open(Coin.DOGECOIN) }

        //no first block hash given, or one that is not a hash
        val noHash = "Inscriptions server did not give its first block hash, so it cannot be checked to be a Pepecoin server"
        for(blockhash in listOf(404 to "", 500 to "oops", 200 to "not a hash", 200 to dogeGenesis.uppercase())) {
            routes = { _, path -> when(path) { "/status" -> 200 to mainnetStatus; "/blockhash/0" -> blockhash; else -> 404 to "" } }
            assertEquals(noHash, assertFailsWith<IndexerException> { open(Coin.PEPECOIN) }.message, "$blockhash")
        }
        routes = { _, path -> when(path) { "/status" -> 200 to mainnetStatus; "/block/0" -> 200 to """{"height":0}"""; else -> 404 to "" } }
        assertEquals(noHash, assertFailsWith<IndexerException> { open(Coin.PEPECOIN) }.message)

        //a coin whose genesis the phone does not know cannot be checked, so it is refused without asking
        requests.clear()
        routes = { _, path -> if(path == "/status") 200 to mainnetStatus else 200 to dogeGenesis }
        for(coin in listOf(Coin.LUCKYCOIN, Coin.DINGOCOIN)) {
            assertEquals("Cannot check that this inscriptions server is for ${coin.displayName} Mainnet",
                assertFailsWith<IndexerException> { open(coin) }.message)
        }
        assertEquals(listOf("GET /status", "GET /status"), requests.toList())
    }

    /**
     * Bitcoin's ord (or ord-dogecoin) also says {"chain":"mainnet"}, and Litecoin is where ord's empty answer can clear a
     * coin: its block 0 must be Litecoin's for the network asked, and when it cannot be read the server is refused.
     */
    @Test fun aBitcoinOrdIsRefusedForLitecoin(): Unit = runBlocking {
        val status = """{"chain":"mainnet","rune_index":true}"""
        routes = { _, path -> when(path) { "/status" -> 200 to status; "/blockhash/0" -> 200 to bitcoinGenesis; else -> 404 to "" } }
        assertEquals("Inscriptions server is for another chain (its block 0 is 000000000019d668...), not a Litecoin Mainnet server",
            assertFailsWith<IndexerException> { open(Coin.LITECOIN) }.message)
        routes = { _, path -> when(path) { "/status" -> 200 to status; "/blockhash/0" -> 200 to dogeGenesis; else -> 404 to "" } }
        assertFailsWith<IndexerException> { open(Coin.LITECOIN) }
        //Litecoin testnet's genesis does not pass for mainnet, nor mainnet's for testnet
        routes = { _, path -> when(path) { "/status" -> 200 to status; "/blockhash/0" -> 200 to litecoinTestnetGenesis; else -> 404 to "" } }
        assertFailsWith<IndexerException> { open(Coin.LITECOIN) }
        routes = { _, path -> when(path) { "/status" -> 200 to """{"chain":"testnet"}"""; "/blockhash/0" -> 200 to litecoinGenesis; else -> 404 to "" } }
        assertFailsWith<IndexerException> { open(Coin.LITECOIN, Network.TESTNET) }

        //a missing (404 on /blockhash/0 and /block/0), failed or malformed hash cannot prove the chain
        val noHash = "Inscriptions server did not give its first block hash, so it cannot be checked to be a Litecoin server"
        for(blockhash in listOf(404 to "", 500 to "oops", 200 to "", 200 to "not a hash", 200 to litecoinGenesis.uppercase(), 200 to litecoinGenesis.take(63))) {
            requests.clear()
            routes = { _, path -> when(path) { "/status" -> 200 to status; "/blockhash/0" -> blockhash; else -> 404 to "" } }
            assertEquals(noHash, assertFailsWith<IndexerException> { open(Coin.LITECOIN) }.message, "$blockhash")
            assertTrue(requests.none { it.startsWith("POST") || it.contains("/output") }, "$requests")
        }
    }

    /** A server's chain name reaches the message shown on Save as printable ASCII only: no bidi overrides or controls. */
    @Test fun aChainNameIsShownAsPlainText(): Unit = runBlocking {
        routes = { _, path -> if(path == "/status") 200 to """{"chain":"\u202etenniam\u0007x"}""" else 404 to "" }
        assertEquals("Inscriptions server is on ?tenniam?x, wallet is on Mainnet", assertFailsWith<IndexerException> { open(Coin.LITECOIN) }.message)
        routes = { _, path -> if(path == "/status") 200 to """{"network":"Doge\u202ecoin\u200f"}""" else 404 to "" }
        assertEquals("Inscriptions server is for Doge?coin?, wallet is Pepecoin",
            assertFailsWith<IndexerException> { Indexers.open(base, Coin.PEPECOIN, Network.MAINNET, null, nintondo = true) }.message)
    }

    /** A refused server is never asked about outputs. */
    @Test fun refusedOpenAsksNothingElse(): Unit = runBlocking {
        routes = { _, path -> when(path) { "/status" -> 200 to mainnetStatus; "/blockhash/0" -> 200 to litecoinGenesis; else -> 200 to "[]" } }
        assertFailsWith<IndexerException> { open(Coin.DOGECOIN) }
        assertTrue(requests.none { it.contains("/output") || it.contains("/find_meta") || it.contains("/inscription") }, "$requests")
    }

    /** An ord reader opened for a network keeps checking it each time it reads the status again. */
    @Test fun aServerThatChangesChainIsRefusedLater(): Unit = runBlocking {
        var chain = "mainnet"
        routes = { _, path -> when(path) { "/status" -> 200 to """{"chain":"$chain","rune_index":true}"""; "/blockhash/0" -> 200 to litecoinGenesis; else -> 200 to "[]" } }
        val indexer = open(Coin.LITECOIN)
        assertTrue(indexer.runesAware)
        chain = "regtest"
        assertEquals("Inscriptions server is on regtest, wallet is on Mainnet",
            assertFailsWith<IndexerException> { indexer.outputs(listOf("ab".repeat(32) + ":0")) }.message)
        assertTrue(requests.none { it.startsWith("POST") })
        //a refused status leaves nothing vouching for runes
        assertFalse(indexer.runesAware)
    }

    @Test fun unsupportedKindIsRefused(): Unit = runBlocking {
        val unsupported = "This kind of indexer is not supported on the phone yet. Use Built-in."
        assertEquals(unsupported, Indexers.UNSUPPORTED)
        for(status in listOf(200 to "OK", 200 to "OK\n", 200 to "<html>ord</html>", 200 to """{"height":5}""", 200 to """{"chain":5}""", 200 to "[]", 200 to "", 404 to "Not Found")) {
            routes = { _, path -> if(path == "/status") status else 200 to "{}" }
            assertEquals(unsupported, assertFailsWith<IndexerException> { open(Coin.LITECOIN) }.message, "$status")
            assertEquals(unsupported, assertFailsWith<IndexerException> { open(Coin.JUNKCOIN) }.message, "$status")
        }
    }

    /** Failures that are not about the kind of server keep their own error. */
    @Test fun otherFailuresAreNotCalledUnsupported(): Unit = runBlocking {
        routes = { _, _ -> 429 to "" }
        assertFailsWith<RateLimitedException> { open(Coin.LITECOIN) }
        routes = { _, path -> when(path) { "/status" -> 200 to mainnetStatus; else -> 429 to "" } }
        assertFailsWith<RateLimitedException> { open(Coin.DOGECOIN) }
        assertFailsWith<RateLimitedException> { open(Coin.LITECOIN) }
        routes = { _, _ -> 503 to "down" }
        val e = assertFailsWith<IndexerException> { open(Coin.LITECOIN) }
        assertTrue(e.message!!.contains("503"), e.message)
        assertFailsWith<IndexerException> { Indexers.open("not a url", Coin.LITECOIN, Network.MAINNET, null) }
        assertFailsWith<IndexerException> { Indexers.open("ftp://example.com", Coin.LITECOIN, Network.MAINNET, null) }
        val proxy = SocksProxy("127.0.0.1", 9050)
        assertEquals("This inscriptions indexer uses plain http, which is not safe through a proxy or Tor. Use an https or .onion indexer.",
            assertFailsWith<IndexerException> { Indexers.open(base, Coin.LITECOIN, Network.MAINNET, proxy) }.message)
    }

    @Test fun nintondoStatusForAnotherCoinIsRefused(): Unit = runBlocking {
        routes = { _, path -> if(path == "/status") 200 to """{"network":"Dogecoin","ord_height":5}""" else 200 to "[]" }
        assertEquals("Inscriptions server is for Dogecoin, wallet is Pepecoin",
            assertFailsWith<IndexerException> { Indexers.open(base, Coin.PEPECOIN, Network.MAINNET, null, nintondo = true) }.message)
        assertEquals("Inscriptions server is for Dogecoin, wallet is Litecoin",
            assertFailsWith<IndexerException> { Indexers.open(base, Coin.LITECOIN, Network.MAINNET, null, nintondo = true) }.message)
        assertEquals("Inscriptions server is on mainnet, wallet is on Testnet",
            assertFailsWith<IndexerException> { Indexers.open(base, Coin.DOGECOIN, Network.TESTNET, null, nintondo = true) }.message)
        assertEquals(listOf("GET /status", "GET /status", "GET /status"), requests.toList())

        requests.clear()
        val indexer = Indexers.open(base, Coin.DOGECOIN, Network.MAINNET, null, nintondo = true)
        assertIs<NintondoIndexer>(indexer)
        assertFalse(indexer.runesAware)
        assertEquals(listOf("GET /status"), requests.toList())
    }

    /** Nintondo is told by host name only; look-alikes are not Nintondo. */
    @Test fun nintondoIsKnownByHost() {
        assertTrue(Indexers.isNintondo("https://pepe-mainnet-api.nintondo.io"))
        assertTrue(Indexers.isNintondo("https://DOGE-mainnet-api.Nintondo.IO/"))
        assertFalse(Indexers.isNintondo("https://nintondo.io"))
        assertFalse(Indexers.isNintondo("https://doge-mainnet-api.nintondo.io.evil.com"))
        assertFalse(Indexers.isNintondo("https://evil.com/x.nintondo.io"))
        assertFalse(Indexers.isNintondo("https://ordinalslite.com"))
        assertFalse(Indexers.isNintondo("not a url"))
    }

    @Test fun publicServersPerCoin() {
        assertEquals("https://ordinalslite.com", Indexers.publicUrl(Coin.LITECOIN, Network.MAINNET))
        assertEquals("https://bells-mainnet-api.nintondo.io", Indexers.publicUrl(Coin.BELLSCOIN, Network.MAINNET))
        assertEquals("https://bells-testnet-api.nintondo.io", Indexers.publicUrl(Coin.BELLSCOIN, Network.TESTNET))
        assertEquals("https://doge-mainnet-api.nintondo.io", Indexers.publicUrl(Coin.DOGECOIN, Network.MAINNET))
        assertEquals("https://pepe-mainnet-api.nintondo.io", Indexers.publicUrl(Coin.PEPECOIN, Network.MAINNET))
        assertEquals("https://pepe-testnet-api.nintondo.io", Indexers.publicUrl(Coin.PEPECOIN, Network.TESTNET))
        assertNull(Indexers.publicUrl(Coin.DOGECOIN, Network.TESTNET))
        assertNull(Indexers.publicUrl(Coin.LITECOIN, Network.TESTNET))
        for(coin in listOf(Coin.JUNKCOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN)) {
            for(network in Network.entries) assertNull(Indexers.publicUrl(coin, network))
        }
    }

    /**
     * Litecoin's rows are the hashes of the genesis headers compiled into drongo's Network (getGenesisHeaderHex): block
     * hashes are double SHA-256 in display order. The phone's own chain parameters carry no genesis.
     */
    @Test fun litecoinGenesisIsTheHashOfDrongosHeaders() {
        fun hash(header: String): String {
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = header.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return sha.digest(sha.digest(bytes)).reversed().joinToString("") { "%02x".format(it) }
        }
        assertEquals(Indexers.genesis(Coin.LITECOIN, Network.MAINNET), hash("010000000000000000000000000000000000000000000000000000000000000000000000" +
            "d9ced4ed1130f7b7faad9be25323ffafa33232a17c3edf6cfd97bee6bafbdd97b9aa8e4ef0ff0f1ecd513f7c"))
        assertEquals(Indexers.genesis(Coin.LITECOIN, Network.TESTNET), hash("010000000000000000000000000000000000000000000000000000000000000000000000" +
            "d9ced4ed1130f7b7faad9be25323ffafa33232a17c3edf6cfd97bee6bafbdd97f60ba158f0ff0f1ee1790400"))
    }

    /** The other coins' genesis hashes as the desktop's ChainGenesis has them. */
    @Test fun genesisHashes() {
        assertEquals(litecoinGenesis, Indexers.genesis(Coin.LITECOIN, Network.MAINNET))
        assertEquals(litecoinTestnetGenesis, Indexers.genesis(Coin.LITECOIN, Network.TESTNET))
        assertEquals(dogeGenesis, Indexers.genesis(Coin.DOGECOIN, Network.MAINNET))
        assertEquals("bb0a78264637406b6360aad926284d544d7049f45189db5664f3c4d07350559e", Indexers.genesis(Coin.DOGECOIN, Network.TESTNET))
        assertEquals("37981c0c48b8d48965376c8a42ece9a0838daadb93ff975cb091f57f8c2a5faa", Indexers.genesis(Coin.PEPECOIN, Network.MAINNET))
        assertEquals("f9f4ea4ae7f6ea4c55040ede2019ba0a53e262f46ec9bce3dcda2cb11f96fc52", Indexers.genesis(Coin.PEPECOIN, Network.TESTNET))
        for(network in Network.entries) {
            assertEquals("e5be24df57c43a82d15c2f06bda961296948f8f8eb48501bed1efb929afe0698", Indexers.genesis(Coin.BELLSCOIN, network))
            assertEquals("a2effa738145e377e08a61d76179c21703e13e48910b30a2a87f0dfe794b64c6", Indexers.genesis(Coin.JUNKCOIN, network))
            assertNull(Indexers.genesis(Coin.LUCKYCOIN, network))
            assertNull(Indexers.genesis(Coin.DINGOCOIN, network))
        }
    }
}
