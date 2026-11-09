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
import kotlin.test.assertFalse
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

    private val T1 = "01".repeat(32)
    private val T2 = "02".repeat(32)
    private val requests = CopyOnWriteArrayList<String>()
    private var routes: (method: String, path: String, body: String) -> Pair<Int, String> = { _, _, _ -> 404 to "not found" }
    /** Binary answers by path, served before [routes]. */
    private var binary: Map<String, ByteArray> = emptyMap()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += "${ex.requestMethod} ${ex.requestURI.path}"
            val (status, bytes) = binary[ex.requestURI.path]?.let { 200 to it }
                ?: routes(ex.requestMethod, ex.requestURI.path, body).let { (st, out) -> st to out.encodeToByteArray() }
            if(status == 429) ex.responseHeaders.add("Retry-After", "1")
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
                "$address/txs" -> 200 to "[${tx(T2, null, s, 300)},${tx(T1, 101, s, 5000)}]"
                "$address/utxo" -> 200 to """[{"txid":"$T1","vout":0,"value":4000,"status":{"confirmed":true,"block_height":101}}]"""
                "/tx/$T1" -> 200 to tx(T1, 101, s, 5000, prevScript = "0014" + "cc".repeat(20), prevValue = 9000)
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params)
        b.connect()
        assertEquals(110L, b.tipHeight())
        assertEquals(Balance(4000, 300), b.balance(script))
        assertEquals(listOf(HistoryEntry(T2, 0), HistoryEntry(T1, 101)), b.history(script))
        assertEquals(listOf(Utxo(T1, 0, 101, 4000)), b.unspent(script))
        val t1 = b.transaction(T1)
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
                "$scripthash/txs" -> 200 to "[${tx(T1, 3, script.toHex(), 1)}]"
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(listOf(HistoryEntry(T1, 3)), b.history(script))
        assertFalse(b.historyTruncated(script))
    }

    @Test fun historyStopsAtThePageLimit(): Unit = runBlocking {
        var n = 0
        routes = { _, path, _ ->
            when {
                path == "/blocks/tip/height" -> 200 to "1000"
                path.startsWith("$address/txs") -> 200 to (0 until 25).joinToString(",", "[", "]") { tx((n++).toString(16).padStart(64, '0'), 900, "51", 1) }
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(40 * 25, b.history(script).size)
        assertEquals(40, requests.count { it.contains("/txs") })
        assertTrue(b.historyTruncated(script))
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
        assertEquals(1200.0, b.feeRate(2))
    }

    @Test fun feeEstimatesPickTheNearestTargetAtOrBelow(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "/fee-estimates" -> 200 to """{"1":3000.5,"3":2000.0,"6":1500.0}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(3000.5, b.feeRate(2))
        assertEquals(1500.0, b.feeRate(25))
    }

    /** peppool advertises about 1 sat/vB on a chain that mines near 1000: desktop floors at the coin's rate. */
    @Test fun feeEstimateIsFlooredAtTheCoinsRate(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "/fee-estimates" -> 200 to """{"1":1.0,"6":1.0}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(1000.0, b.feeRate(2))
    }

    @Test fun rateLimitedRequestIsRetriedAfterRetryAfter(): Unit = runBlocking {
        var tries = 0
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "$address/txs" -> if(tries++ == 0) 429 to "Too Many Requests" else 200 to "[]"
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(emptyList(), b.history(script))
        assertEquals(2, tries)
    }

    @Test fun unusedAddressSkipsTheHistoryRequest(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                address -> 200 to """{"chain_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"""
                else -> 500 to "should not be asked"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(Balance(0, 0), b.balance(script))
        assertEquals(emptyList(), b.history(script))
        assertEquals(0, requests.count { it.contains("/txs") })
    }

    @Test fun serverTxidThatIsNotHexIsRefused(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "$address/txs" -> 200 to "[" + tx("t1\r\nHost: other.example", 3, "51", 1) + "]"
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertFailsWith<IOException> { b.history(script) }
        assertFailsWith<IOException> { b.transaction("aa bb") }
    }

    /** Headers, then one byte at a time just under the read timeout: the request deadline must end it. */
    @Test fun slowTrickleHitsTheRequestDeadline() {
        ServerSocket(0).use { s ->
            thread(isDaemon = true) {
                while(!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            c.use {
                                it.getInputStream().read(ByteArray(4096))
                                val out = it.getOutputStream()
                                out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".encodeToByteArray())
                                repeat(1000) { _ -> out.write('1'.code); out.flush(); Thread.sleep(100) }
                            }
                        }
                    }
                }
            }
            val started = System.currentTimeMillis()
            val e = assertFailsWith<IOException> { runBlocking { EsploraBackend("http://127.0.0.1:${s.localPort}", params, timeoutMs = 300).connect() } }
            assertEquals("Request took too long", e.message)
            assertTrue(System.currentTimeMillis() - started < 5_000)
        }
    }

    @Test fun oversizedBodyIsRefused(): Unit = runBlocking {
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "5" else 200 to "x".repeat(5 * 1024 * 1024) }
        val b = EsploraBackend(base, params).also { it.connect() }
        val e = assertFailsWith<IOException> { b.transaction("0b".repeat(32)) }
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
        assertEquals(listOf("esplora-api.invalid:8080"), asked) // connect and tipHeight share the kept connection
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

    @Test fun utxoWithATopLevelConfirmedFlagGetsItsHeightFromTheTx(): Unit = runBlocking {
        //pepe-testnet-api.nintondo.io lists {"confirmed":true} on each utxo, with no status block or height
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "100"
                "$address/utxo" -> 200 to """[{"txid":"$T1","vout":1,"confirmed":true,"value":5000},{"txid":"$T2","vout":0,"confirmed":false,"value":7000}]"""
                "/tx/$T1/status" -> 200 to """{"confirmed":true,"block_height":42,"block_hash":"00","block_time":1}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(listOf(Utxo(T1, 1, 42, 5000), Utxo(T2, 0, 0, 7000)), b.unspent(script))
    }

    private val rawA = "0200000001" + "11".repeat(32) + "00000000" + "00" + "fdffffff" + "01" + "e803000000000000" + "0151" + "00000000"
    private val rawB = "0200000001" + "22".repeat(32) + "00000000" + "00" + "fdffffff" + "01" + "e803000000000000" + "0151" + "00000000"

    @Test fun rawTransactionIsFetched(): Unit = runBlocking {
        val txid = com.sparrowwallet.mobile.crypto.RawTx.parse(rawA.hexToBytes()).txid
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "5" else if(path == "/tx/$txid/hex") 200 to rawA else 404 to "" }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(rawA, b.rawTransaction(txid).toHex())
    }

    @Test fun rawTransactionWithAnotherTxidIsRefused(): Unit = runBlocking {
        val txid = com.sparrowwallet.mobile.crypto.RawTx.parse(rawA.hexToBytes()).txid
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "5" else if(path == "/tx/$txid/hex") 200 to rawB else 404 to "" }
        val b = EsploraBackend(base, params).also { it.connect() }
        val e = assertFailsWith<IOException> { b.rawTransaction(txid) }
        assertTrue("different transaction" in e.message!!, e.message)
    }

    @Test fun rawTransactionThatIsNotHexIsRefused() {
        val txid = com.sparrowwallet.mobile.crypto.RawTx.parse(rawA.hexToBytes()).txid
        assertFailsWith<IOException> { verifiedRawTransaction(txid, "zz" + rawA.drop(2)) }
        assertFailsWith<IOException> { verifiedRawTransaction(txid, rawA.dropLast(1)) }
        assertFailsWith<IOException> { verifiedRawTransaction(txid, "") }
        assertEquals(rawA, verifiedRawTransaction(txid, "  " + rawA.uppercase() + "\n").toHex())
    }

    @Test fun rawTransactionAnsweredAsAJsonStringIsRead(): Unit = runBlocking {
        // explorer.junk-coin.com quotes the hex
        val txid = com.sparrowwallet.mobile.crypto.RawTx.parse(rawA.hexToBytes()).txid
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "5" else if(path == "/tx/$txid/hex") 200 to "\"$rawA\"" else 404 to "" }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(rawA, b.rawTransaction(txid).toHex())
    }

    @Test fun rawTransactionTheHexRouteRefusesIsReadAsBytes(): Unit = runBlocking {
        // nintondo and luckyscan answer 400 for a coinbase's hex but serve /raw
        val txid = com.sparrowwallet.mobile.crypto.RawTx.parse(rawA.hexToBytes()).txid
        routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "5" else if(path == "/tx/$txid/hex") 400 to "Invalid hex string" else 404 to "" }
        binary = mapOf("/tx/$txid/raw" to rawA.hexToBytes())
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(rawA, b.rawTransaction(txid).toHex())
        binary = mapOf("/tx/$txid/raw" to rawB.hexToBytes())
        assertFailsWith<IOException> { b.rawTransaction(txid) }
    }

    @Test fun rawTransactionFailureNamesTheHexRouteWhenRawIsNotBytes(): Unit = runBlocking {
        // explorer.junk-coin.com: /hex throttled upstream, /raw answers the bytes escaped as a JSON string
        val txid = com.sparrowwallet.mobile.crypto.RawTx.parse(rawA.hexToBytes()).txid
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "5"
                "/tx/$txid/hex" -> 500 to "{\"error\":\"Request failed with status code 429\"}"
                "/tx/$txid/raw" -> 200 to "\"\\u0002\\u0000\\u0000\\u0000\""
                else -> 404 to ""
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        val e = assertFailsWith<IOException> { b.rawTransaction(txid) }
        assertTrue("HTTP 500" in e.message!! && "429" in e.message!!, e.message)
    }

    private val H = "ab".repeat(32)
    private fun blockTx(txid: String, fee: Long?, coinbase: Boolean = false) =
        """{"txid":"$txid","vin":[{"is_coinbase":$coinbase}],"vout":[]${if(fee != null) ""","fee":$fee""" else ""}}"""
    private fun id(n: Int) = n.toString(16).padStart(64, '0')
    private fun fee(txid: String, fee: Long) = com.sparrowwallet.mobile.crypto.track.BlockFee(txid, fee)

    @Test fun blockCallsReadHeightHashAndFees(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "110"
                "/block-height/101" -> 200 to H
                "/block-height/999" -> 404 to "Block not found"
                "/block/$H/txs/0" -> 200 to "[${blockTx(id(9), null, true)},${blockTx(T1, 300)},${blockTx(T2, 500)}]"
                "/block/$H" -> 200 to """{"id":"$H","tx_count":3}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(H, b.blockHash(101))
        assertNull(b.blockHash(999))
        assertEquals(listOf(fee(T1, 300), fee(T2, 500)), b.blockFees(H))
        assertNull(b.txidAt(101, 1))
    }

    @Test fun blockFeesPagesPastTwentyFive(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "110"
                "/block/$H/txs/0" -> 200 to (0 until 25).joinToString(",", "[", "]") { blockTx(id(it), if(it == 0) null else 10L + it, it == 0) }
                "/block/$H/txs/25" -> 200 to (25 until 30).joinToString(",", "[", "]") { blockTx(id(it), 10L + it) }
                "/block/$H" -> 200 to """{"id":"$H","tx_count":30}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        val fees = b.blockFees(H)!!
        assertEquals(29, fees.size)
        assertEquals(fee(id(1), 11), fees.first())
        assertEquals(fee(id(29), 39), fees.last())
        assertEquals(listOf("GET /blocks/tip/height", "GET /block/$H/txs/0", "GET /block/$H/txs/25", "GET /block/$H"), requests.toList())
    }

    private fun serveBlock(txs: String, countBody: String?) {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "110"
                "/block/$H/txs/0" -> 200 to txs
                "/block/$H" -> if(countBody != null) 200 to countBody else 404 to "no"
                else -> 404 to "not found"
            }
        }
    }

    @Test fun shortPageThatDisagreesWithTheCountThrows(): Unit = runBlocking {
        val page = "[${blockTx(id(9), null, true)},${blockTx(T1, 300)}]"
        serveBlock(page, """{"tx_count":3}""")
        val b = EsploraBackend(base, params).also { it.connect() }
        assertFailsWith<IOException> { b.blockFees(H) }
        serveBlock(page, """{"id":"x"}""")
        assertFailsWith<IOException> { b.blockFees(H) }
        serveBlock(page, null)
        assertFailsWith<IOException> { b.blockFees(H) }
        serveBlock(page, """{"tx_count":2}""")
        assertEquals(listOf(fee(T1, 300)), b.blockFees(H))
    }

    @Test fun emptyFirstPageThrows(): Unit = runBlocking {
        serveBlock("[]", """{"tx_count":0}""")
        val b = EsploraBackend(base, params).also { it.connect() }
        assertFailsWith<IOException> { b.blockFees(H) }
    }

    @Test fun firstTransactionMustBeAnExplicitCoinbase(): Unit = runBlocking {
        val noFlag = """{"txid":"${id(9)}","vin":[{}],"vout":[]}"""
        serveBlock("[$noFlag,${blockTx(T1, 300)}]", """{"tx_count":2}""")
        val b = EsploraBackend(base, params).also { it.connect() }
        assertFailsWith<IOException> { b.blockFees(H) }
    }

    @Test fun transactionAtThrowsOnAnIncompleteStatus(): Unit = runBlocking {
        for(status in listOf(
            """{"confirmed":true,"block_hash":"$H"}""",
            """{"confirmed":true,"block_height":101}""",
            """{"block_height":101,"block_hash":"$H"}""",
            """{"confirmed":"yes","block_height":101,"block_hash":"$H"}"""
        )) {
            routes = { _, path, _ -> if(path == "/blocks/tip/height") 200 to "110" else if(path == "/tx/$T1/status") 200 to status else 404 to "" }
            val b = EsploraBackend(base, params).also { it.connect() }
            assertFailsWith<IOException>(status) { b.transactionAt(T1) }
        }
    }

    @Test fun blockFeesOfAnExactMultipleEndWhereTheCountSaysSo(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "110"
                "/block/$H/txs/0" -> 200 to (0 until 25).joinToString(",", "[", "]") { blockTx(id(it), if(it == 0) null else 1L, it == 0) }
                "/block/$H/txs/25" -> 400 to "start_index out of range"
                "/block/$H" -> 200 to """{"id":"$H","tx_count":25}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(24, b.blockFees(H)!!.size)
    }

    @Test fun blockFeesWithoutBlockRoutesAreUnknownButAMissingFeeThrows(): Unit = runBlocking {
        val other = "cd".repeat(32)
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "110"
                "/block/$H/txs/0" -> 404 to "not found"
                "/block/$other/txs/0" -> 200 to "[${blockTx(id(9), null, true)},${blockTx(T1, 300)},${blockTx(T2, null)}]"
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertNull(b.blockFees(H))
        assertFailsWith<IOException> { b.blockFees(other) }
        assertFailsWith<IOException> { b.blockFees("../etc") }
    }

    @Test fun blockFeesPageThatCannotBeReadThrows(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "110"
                "/block/$H/txs/0" -> 200 to "<html>oops</html>"
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertFailsWith<IOException> { b.blockFees(H) }
    }

    @Test fun transactionAtReadsStatus(): Unit = runBlocking {
        routes = { _, path, _ ->
            when(path) {
                "/blocks/tip/height" -> 200 to "110"
                "/tx/$T1/status" -> 200 to """{"confirmed":true,"block_height":101,"block_hash":"$H","block_time":1761000000}"""
                "/tx/$T2/status" -> 200 to """{"confirmed":false}"""
                else -> 404 to "not found"
            }
        }
        val b = EsploraBackend(base, params).also { it.connect() }
        assertEquals(TxAt(101, H, 1761000000L), b.transactionAt(T1))
        assertEquals(TxAt(0, null, 0), b.transactionAt(T2))
    }

    @Test fun blockCallsRefuseWhenAirGapped(): Unit = runBlocking {
        val b = EsploraBackend(base, params)
        NetworkPolicy.blocked = true
        assertFailsWith<AirGappedException> { b.blockHash(1) }
        assertFailsWith<AirGappedException> { b.blockFees(H) }
        assertFailsWith<AirGappedException> { b.transactionAt(T1) }
    }
}
