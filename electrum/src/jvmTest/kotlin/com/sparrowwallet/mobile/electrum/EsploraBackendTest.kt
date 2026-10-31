package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.sha256
import com.sparrowwallet.mobile.crypto.toHex
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EsploraBackend against a local HTTP server that answers recorded JSON per path. */
class EsploraBackendTest {
    private val params = ChainParams.of(Coin.PEPECOIN, Network.TESTNET)
    private val key = HDKey.fromSeed(Bip39.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))
        .derivePath("m/44'/1'/0'/0/0")
    private val script = Scripts.p2pkhOutput(key.pubKey)
    private val address = "/address/" + com.sparrowwallet.mobile.crypto.Addresses.forKey(key.pubKey, com.sparrowwallet.mobile.crypto.ScriptType.P2PKH, params)
    private val scripthash = "/scripthash/" + sha256(script).toHex()

    private val requests = CopyOnWriteArrayList<String>()
    private var routes: (method: String, path: String, body: String) -> Pair<Int, String> = { _, _, _ -> 404 to "not found" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += "${ex.requestMethod} ${ex.requestURI.path}"
            val (status, out) = routes(ex.requestMethod, ex.requestURI.path, body)
            val bytes = out.encodeToByteArray()
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

    private fun tx(txid: String, height: Int?, outScript: String, value: Long, prevScript: String? = null, prevValue: Long = 0) = """
        {"txid":"$txid","vin":[${if(prevScript == null) """{"txid":"${"00".repeat(32)}","vout":4294967295,"is_coinbase":true,"prevout":null}"""
            else """{"txid":"${"ab".repeat(32)}","vout":0,"is_coinbase":false,"prevout":{"scriptpubkey":"$prevScript","value":$prevValue}}"""}],
         "vout":[{"scriptpubkey":"$outScript","value":$value}],
         "status":${if(height == null) """{"confirmed":false}""" else """{"confirmed":true,"block_height":$height,"block_time":1761000000}"""}}
    """.trimIndent()

    @Test fun syncReadsBalanceHistoryAndTransactions(): Unit = runBlocking {
        val s = script.toHex()
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "110"
                address -> 200 to """{"chain_stats":{"funded_txo_sum":5000,"spent_txo_sum":1000},"mempool_stats":{"funded_txo_sum":300,"spent_txo_sum":0}}"""
                "$address/txs" -> 200 to "[${tx("t2", null, s, 300)},${tx("t1", 101, s, 5000)}]"
                "$address/utxo" -> 200 to """[{"txid":"t1","vout":0,"value":4000,"status":{"confirmed":true,"block_height":101}}]"""
                "/tx/t1" -> 200 to tx("t1", 101, s, 5000, prevScript = "0014" + "cc".repeat(20), prevValue = 9000)
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params)
        b.connect()
        assertEquals(110L, b.tipHeight())
        assertEquals(Balance(4000, 300), b.balance(script))
        assertEquals(listOf(HistoryEntry("t2", 0), HistoryEntry("t1", 101)), b.history(script))
        assertEquals(listOf(Utxo("t1", 0, 101, 4000)), b.unspent(script))
        val t1 = b.transaction("t1")
        assertEquals(listOf(TxOut(s, 5000)), t1.outputs)
        assertEquals(TxOut("0014" + "cc".repeat(20), 9000), t1.inputs[0].prevOut)
        assertEquals(101, t1.height)
        assertEquals(1761000000L, t1.time)
        assertEquals(10, t1.confirmations)
    }

    @Test fun addressRefusedFallsBackToScripthash(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "$address/txs" -> 400 to "Address on invalid network"
                "$scripthash/txs" -> 200 to "[${tx("t1", 3, script.toHex(), 1)}]"
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(listOf(HistoryEntry("t1", 3)), b.history(script))
    }

    @Test fun historyStopsAtThePageLimit(): Unit = runBlocking {
        var n = 0
        routes = { _, path, _ ->
            when {
                path == "/blocks/tip/height" -> 200 to "1000"
                path.startsWith("$address/txs") -> 200 to (0 until 25).joinToString(",", "[", "]") { tx("p${n++}", 900, "51", 1) }
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(40 * 25, b.history(script).size)
        assertEquals(40, requests.count { it.contains("/txs") })
    }

    @Test fun alreadyKnownBroadcastReturnsTxid(): Unit = runBlocking {
        val rawTx = "0200000001" + "11".repeat(32) + "00000000" + "00" + "fdffffff" + "01" + "e803000000000000" + "0151" + "00000000"
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "/tx" -> 400 to "sendrawtransaction RPC error: {\"code\":-27,\"message\":\"Transaction already in block chain\"}"
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(com.sparrowwallet.mobile.crypto.RawTx.parse(rawTx.hexToBytes()).txid, b.broadcast(rawTx))
    }

    @Test fun serverWithoutBroadcastSaysSo(): Unit = runBlocking {
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "5" else 404 to "Cannot POST /tx" }
        val b = EsploraBackend(base, params).also { it.connect() }
        val e = assertFailsWith<IOException> { b.broadcast("00") }
        assertEquals("The server at $base does not accept transaction broadcasts.", e.message)
    }

    @Test fun feeRateFallsBackToMempoolDialect(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "/v1/fees/recommended" -> 200 to """{"fastestFee":1200,"halfHourFee":1100,"hourFee":1000,"economyFee":1000}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(1100.0, b.feeRate(2))
    }

    @Test fun feeEstimatesPickTheNearestTargetAtOrAbove(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "/fee-estimates" -> 200 to """{"1":30.5,"3":20.0,"6":10.0}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(20.0, b.feeRate(2))
        assertEquals(10.0, b.feeRate(25))
    }

    @Test fun oversizedBodyIsRefused(): Unit = runBlocking {
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "5" else 200 to "x".repeat(5 * 1024 * 1024) }
        val b = EsploraBackend(base, params).also { it.connect() }
        val e = assertFailsWith<IOException> { b.transaction("big") }
        assertEquals("Response too large", e.message)
    }

    @Test fun airGappedModeOpensNoConnection(): Unit = runBlocking {
        routes = { _, _, _ -> 200 to "5" }
        NetworkPolicy.blocked = true
        assertFailsWith<AirGappedException> { EsploraBackend(base, params).connect() }
        assertTrue(requests.isEmpty())
    }

    @Test fun proxyReceivesTheHostname(): Unit = runBlocking {
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "77" else 404 to "" }
        val asked = CopyOnWriteArrayList<String>()
        ServerSocket(0).use { socks ->
            thread(isDaemon = true) {
                while(!socks.isClosed) {
                    val c = runCatching { socks.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serveSocks5(c, asked, server.address.port) } }
                }
            }
            // an unresolvable name: the backend must hand it to the proxy, never look it up itself
            val b = EsploraBackend("http://esplora-api.invalid:8080", params, SocksProxy("127.0.0.1", socks.localPort))
            b.connect()
            assertEquals(77L, b.tipHeight())
        }
        assertEquals(listOf("esplora-api.invalid:8080", "esplora-api.invalid:8080"), asked) // connect, then tipHeight
    }

    /** Serves one fixed raw HTTP response per connection, for framing a well-behaved server would never send. */
    private fun rawServer(response: String, block: (String) -> Unit) {
        ServerSocket(0).use { s ->
            thread(isDaemon = true) {
                while(!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    runCatching { c.use { it.getInputStream().read(ByteArray(4096)); it.getOutputStream().write(response.encodeToByteArray()) } }
                }
            }
            block("http://127.0.0.1:${s.localPort}")
        }
    }

    @Test fun negativeChunkSizeIsRefused() = rawServer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\nx\r\n0\r\n\r\n") { url ->
        val e = assertFailsWith<IOException> { runBlocking { EsploraBackend(url, params).connect() } }
        assertEquals("Bad chunk size", e.message)
    }

    @Test fun negativeContentLengthIsRefused() = rawServer("HTTP/1.1 200 OK\r\nContent-Length: -5\r\n\r\n12") { url ->
        val e = assertFailsWith<IOException> { runBlocking { EsploraBackend(url, params).connect() } }
        assertEquals("Bad Content-Length", e.message)
    }

    @Test fun noFeeEstimateIsNull(): Unit = runBlocking {
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "5" else 404 to "" }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertNull(b.feeRate(2))
    }

    /** Minimal SOCKS5 (no auth, CONNECT by domain): records the requested host and relays to the local server. */
    private fun serveSocks5(client: Socket, asked: MutableList<String>, targetPort: Int) {
        client.use { c ->
            val input = DataInputStream(c.getInputStream())
            val out = c.getOutputStream()
            input.readByte()
            repeat(input.readUnsignedByte()) { input.readByte() }
            out.write(byteArrayOf(5, 0))
            input.readByte(); input.readByte(); input.readByte()
            require(input.readUnsignedByte() == 3) { "proxy was given an IP, not a hostname" }
            val name = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
            val port = input.readUnsignedShort()
            asked += "$name:$port"
            Socket("127.0.0.1", targetPort).use { target ->
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                val up = thread(isDaemon = true) { runCatching { input.copyTo(target.getOutputStream()) } }
                target.getInputStream().copyTo(out)
                up.join(1000)
            }
        }
    }
}
