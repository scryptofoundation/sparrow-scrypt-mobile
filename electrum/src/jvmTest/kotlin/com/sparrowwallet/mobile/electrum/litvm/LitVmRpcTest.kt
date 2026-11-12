package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.SocksProxy
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The LitVM JSON-RPC client against a raw socket stub (it counts connections and sees every request line, header and
 * body, which an HttpServer hides).
 */
class LitVmRpcTest {
    private class Request(val line: String, val headers: List<String>, val body: String) {
        val method: String = Regex(""""method":"([A-Za-z_]+)"""").find(body)?.groupValues?.get(1) ?: ""
        val id: Long = Regex(""""id":([0-9]+)""").find(body)?.groupValues?.get(1)?.toLong() ?: -1
    }

    private class Answer(val status: Int, val body: String, val delayMs: Long = 0, val close: Boolean = false)

    private val accepts = AtomicInteger()
    private val requests = CopyOnWriteArrayList<Request>()
    @Volatile private var closeEach = false
    /** Answers a request (its method, id and body); eth_chainId gives LiteForge unless a test says otherwise. */
    @Volatile private var answer: (Request) -> Answer = { r -> ok(r, "\"0x01\"") }
    @Volatile private var chainId = "\"0x1159\""
    private val server = ServerSocket(0)

    init {
        thread(isDaemon = true) {
            while(!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                accepts.incrementAndGet()
                thread(isDaemon = true) { runCatching { serve(s) } }
            }
        }
    }

    private fun serve(s: Socket) = s.use {
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        while(true) {
            val head = readHead(input) ?: return
            val length = head.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = ByteArray(length).also { DataInputStream(input).readFully(it) }.decodeToString()
            val request = Request(head.first(), head.drop(1), body)
            requests += request
            val reply = if(request.method == "eth_chainId") ok(request, chainId) else answer(request)
            if(reply.delayMs > 0) Thread.sleep(reply.delayMs)
            val bytes = reply.body.encodeToByteArray()
            out.write("HTTP/1.1 ${reply.status} X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".encodeToByteArray())
            out.write(bytes)
            out.flush()
            if(reply.close || closeEach) return
        }
    }

    private fun readHead(input: BufferedInputStream): List<String>? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while(true) {
            val b = input.read()
            if(b < 0) return null
            if(b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if(text.isEmpty()) return lines
                lines += text
                line.clear()
            } else line.append(b.toChar())
        }
    }

    private fun ok(r: Request, result: String) = Answer(200, """{"jsonrpc":"2.0","id":${r.id},"result":$result}""")

    private fun error(r: Request, code: Int, message: String, data: String? = null) =
        Answer(200, """{"jsonrpc":"2.0","id":${r.id},"error":{"code":$code,"message":"$message"${if(data != null) ",\"data\":\"$data\"" else ""}}}""")

    private val url get() = "http://127.0.0.1:${server.localPort}/"
    private var now = 0L

    private fun rpc(deadline: () -> Long? = { null }, clock: () -> Long = { now }, at: String = url, proxy: SocksProxy? = null) =
        LitVmRpc.forTest(LitVmNetwork.LITEFORGE_TESTNET, proxy, deadline, clock, at)

    private fun calls(method: String) = requests.count { it.method == method }

    @AfterTest fun stop() {
        server.close()
        NetworkPolicy.blocked = false
    }

    @Test fun theNetworkIsLiteForgeTestnet() {
        val n = LitVmNetwork.LITEFORGE_TESTNET
        assertEquals("LitVM LiteForge Testnet", n.title)
        assertEquals(4441L, n.chainId)
        assertEquals("https://rpc.testnet.liteforge.zeeve.net/", n.rpcUrl)
        assertEquals("https://explorer.testnet.liteforge.zeeve.net", n.explorerUrl)
        assertEquals(listOf(n), LitVmNetwork.entries)
    }

    @Test fun theChainIsVerifiedOnceBeforeAnyOtherAnswer(): Unit = runBlocking {
        answer = { r -> ok(r, if(r.method == "eth_getBalance") "\"0xde0b6b3a7640000\"" else "\"0x01\"") }
        val rpc = rpc()
        assertEquals(UInt256.parseDecimal("1000000000000000000"), rpc.balance(HOLDER))
        assertEquals(listOf("eth_chainId", "eth_getBalance"), requests.map { it.method })
        rpc.balance(HOLDER)
        rpc.call(BADGE, NAME)
        assertEquals(listOf("eth_chainId", "eth_getBalance", "eth_getBalance", "eth_call"), requests.map { it.method })
        //A view with a deadline shares the verification
        rpc.until(Long.MAX_VALUE).call(BADGE, NAME)
        assertEquals(1, calls("eth_chainId"))
    }

    @Test fun aWrongChainIsRefusedAndNothingElseIsAsked(): Unit = runBlocking {
        chainId = "\"0x1\""
        val rpc = rpc()
        val e = assertFailsWith<LitVmException> { rpc.call(BADGE, NAME) }
        assertEquals("This server is for another chain.", e.message)
        assertFailsWith<LitVmException> { rpc.balance(HOLDER) }
        assertFailsWith<LitVmException> { rpc.verifyChain() }
        assertEquals(listOf("eth_chainId"), requests.map { it.method })
        //A chain id that is not a quantity is a failure, and leaves the chain unverified for the next call to ask again
        chainId = "\"4441\""
        val other = rpc()
        assertFailsWith<LitVmException> { other.verifyChain() }
        chainId = "\"0x1159\""
        other.verifyChain()
        assertEquals(3, calls("eth_chainId"))
    }

    @Test fun theRequestIsAFixedJsonRpcBody(): Unit = runBlocking {
        val rpc = rpc()
        rpc.call(BADGE, NAME)
        rpc.balance(HOLDER)
        rpc.hasCode(BADGE)
        assertEquals("POST / HTTP/1.1", requests[1].line)
        assertTrue("Content-Type: application/json" in requests[1].headers, requests[1].headers.toString())
        assertTrue("User-Agent: sparrow-ltc-mobile" in requests[1].headers, requests[1].headers.toString())
        assertEquals("""{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}""", requests[0].body)
        assertEquals("""{"jsonrpc":"2.0","id":2,"method":"eth_call","params":[{"to":"0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3","data":"0x06fdde03"},"latest"]}""",
            requests[1].body)
        assertEquals("""{"jsonrpc":"2.0","id":3,"method":"eth_getBalance","params":["0x840e41D4484041A43f9307a90389edeB3d111fb3","latest"]}""", requests[2].body)
        assertEquals("""{"jsonrpc":"2.0","id":4,"method":"eth_getCode","params":["0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3","latest"]}""", requests[3].body)
        //Nothing else: no cookie, no client name beyond the generic one
        for(request in requests) {
            assertEquals(setOf("host", "user-agent", "accept", "connection", "content-type", "content-length"),
                request.headers.map { it.substringBefore(':').lowercase() }.toSet())
        }
    }

    @Test fun balanceIsAQuantity(): Unit = runBlocking {
        for((result, value) in listOf("\"0x0\"" to "0", "\"0x\"" to "0", "\"0xff\"" to "255", "\"0x" + "f".repeat(64) + "\"" to UInt256.MAX.toString())) {
            answer = { r -> ok(r, result) }
            assertEquals(value, rpc().balance(HOLDER).toString(), result)
        }
        for(result in listOf("\"ff\"", "\"0X10\"", "\"0x" + "1".repeat(65) + "\"", "\"0xzz\"", "\"0x-1\"", "16", "null", "\"\"")) {
            answer = { r -> ok(r, result) }
            assertFailsWith<LitVmException>(result) { rpc().balance(HOLDER) }
        }
    }

    @Test fun aCallGivesTheResultBytes(): Unit = runBlocking {
        answer = { r -> ok(r, "\"0x00ff10\"") }
        assertContentEquals(byteArrayOf(0, -1, 16), rpc().call(BADGE, NAME))
        answer = { r -> ok(r, "\"0x\"") }
        assertContentEquals(ByteArray(0), rpc().call(BADGE, NAME))
        answer = { r -> ok(r, "\"0xABcd\"") }
        assertContentEquals(byteArrayOf(-85, -51), rpc().call(BADGE, NAME))
    }

    @Test fun hasCodeReadsTheCode(): Unit = runBlocking {
        answer = { r -> ok(r, "\"0x6080604052\"") }
        assertTrue(rpc().hasCode(BADGE))
        answer = { r -> ok(r, "\"0x\"") }
        assertFalse(rpc().hasCode(BADGE))
        for(result in listOf("\"0x123\"", "\"6080\"", "null", "1", "\"0xzz\"")) {
            answer = { r -> ok(r, result) }
            assertFailsWith<LitVmException>(result) { rpc().hasCode(BADGE) }
        }
    }

    @Test fun aRevertWithDataAndWithout(): Unit = runBlocking {
        //geth and Nitro report a revert with data as code 3, one without data as -32000 "execution reverted"
        answer = { r -> error(r, 3, "execution reverted", "0x2a81c9a6") }
        assertEquals("execution reverted", assertFailsWith<CallReverted> { rpc().call(BADGE, NAME) }.message)
        answer = { r -> error(r, -32000, "execution reverted") }
        assertFailsWith<CallReverted> { rpc().call(BADGE, NAME) }
        answer = { r -> error(r, 3, "execution reverted: No such token", "0x08c379a0") }
        assertEquals("execution reverted: No such token", assertFailsWith<CallReverted> { rpc().call(BADGE, NAME) }.message)
        //The contract's text is cleaned and capped before it is put in a message
        val hostile = "execution reverted: " + "\\u202e\\u0007Pay " + "x".repeat(300)
        answer = { r -> error(r, 3, hostile) }
        val message = assertFailsWith<CallReverted> { rpc().call(BADGE, NAME) }.message!!
        assertEquals(120, message.length)
        assertTrue(message.startsWith("execution reverted: Pay xxx"), message)
        assertTrue(message.all { it.code in 0x20..0x7E }, message)
    }

    @Test fun aNodeErrorIsNotARevert(): Unit = runBlocking {
        answer = { r -> error(r, -32005, "request limit exceeded") }
        val e = assertFailsWith<LitVmException> { rpc().call(BADGE, NAME) }
        assertFalse(e is CallReverted)
        assertEquals("request limit exceeded", e.message)
        answer = { r -> error(r, -32000, "") }
        assertFalse(assertFailsWith<LitVmException> { rpc().call(BADGE, NAME) } is CallReverted)
        //An error that is not an object is not JSON-RPC
        answer = { r -> Answer(200, """{"jsonrpc":"2.0","id":${r.id},"error":"boom"}""") }
        assertFalse(assertFailsWith<LitVmException> { rpc().call(BADGE, NAME) } is CallReverted)
    }

    @Test fun http429ThrowsAtOnceAndOtherStatusesFail(): Unit = runBlocking {
        answer = { _ -> Answer(429, "slow down") }
        val rpc = rpc()
        rpc.verifyChain()
        assertFailsWith<RateLimitedException> { rpc.call(BADGE, NAME) }
        assertEquals(1, calls("eth_call"))
        answer = { _ -> Answer(502, "bad gateway") }
        assertEquals("The LitVM server answered HTTP 502", assertFailsWith<LitVmException> { rpc.call(BADGE, NAME) }.message)
    }

    @Test fun aMalformedReplyIsAFailure(): Unit = runBlocking {
        val bodies = listOf<(Request) -> String>(
            { r -> """{"jsonrpc":"2.0","id":${r.id},"result":"0xzz"}""" },
            { r -> """{"jsonrpc":"2.0","id":${r.id},"result":"0x123"}""" },
            { r -> """{"jsonrpc":"2.0","id":${r.id},"result":"1234"}""" },
            { r -> """{"jsonrpc":"2.0","id":${r.id},"result":12}""" },
            { r -> """{"jsonrpc":"2.0","id":${r.id},"result":null}""" },
            { r -> """{"jsonrpc":"2.0","id":${r.id}}""" },
            { _ -> "<html>bad gateway</html>" },
            { _ -> "[1]" },
            { r -> """{"jsonrpc":"2.0","id":${r.id},"result":"0x00"} trailing""" },
            { r -> """{"jsonrpc":"2.0","id":${r.id},"result":"0x00",}""" })
        for(body in bodies) {
            answer = { r -> Answer(200, body(r)) }
            val e = assertFailsWith<LitVmException> { rpc().call(BADGE, NAME) }
            assertFalse(e is CallReverted || e is ResultTooLarge, e.toString())
        }
    }

    @Test fun aWrongOrMissingIdIsAFailure(): Unit = runBlocking {
        for(id in listOf("99", "\"2\"", "null", "2.0")) {
            answer = { _ -> Answer(200, """{"jsonrpc":"2.0","id":$id,"result":"0x00"}""") }
            assertFailsWith<LitVmException>(id) { rpc().call(BADGE, NAME) }
        }
        answer = { _ -> Answer(200, """{"jsonrpc":"2.0","result":"0x00"}""") }
        assertFailsWith<LitVmException> { rpc().call(BADGE, NAME) }
    }

    @Test fun theResultCapIsTheCallersAndNeverAboveOneHundredThousand(): Unit = runBlocking {
        val rpc = rpc()
        answer = { r -> ok(r, "\"0x" + "ab".repeat(8_193) + "\"") }
        assertFailsWith<ResultTooLarge> { rpc.call(BADGE, NAME) }
        answer = { r -> ok(r, "\"0x" + "ab".repeat(8_192) + "\"") }
        assertEquals(8_192, rpc.call(BADGE, NAME).size)
        answer = { r -> ok(r, "\"0x" + "ab".repeat(100_000) + "\"") }
        assertEquals(100_000, rpc.call(BADGE, NAME, 100_000).size)
        assertFailsWith<ResultTooLarge> { rpc.call(BADGE, NAME, 99_999) }
        //A cap above 100,000 is held to 100,000
        answer = { r -> ok(r, "\"0x" + "ab".repeat(100_001) + "\"") }
        assertFailsWith<ResultTooLarge> { rpc.call(BADGE, NAME, 200_000) }
        assertFailsWith<ResultTooLarge> { rpc.call(BADGE, NAME, Int.MAX_VALUE) }
        //Over the cap is told before the digits are read
        answer = { r -> ok(r, "\"0x" + "zz".repeat(8_193) + "\"") }
        assertFailsWith<ResultTooLarge> { rpc.call(BADGE, NAME) }
    }

    @Test fun anOversizedResponseIsAFailureNotATooLargeAnswer(): Unit = runBlocking {
        val rpc = rpc()
        rpc.verifyChain()
        //1,000,001 characters in all
        answer = { r ->
            val head = """{"jsonrpc":"2.0","id":${r.id},"result":"0x"""
            val tail = "\"}"
            Answer(200, head + "a".repeat(1_000_001 - head.length - tail.length) + tail)
        }
        val e = assertFailsWith<LitVmException> { rpc.call(BADGE, NAME, 100_000) }
        assertFalse(e is ResultTooLarge)
        assertEquals("The LitVM server's answer is too large", e.message)
    }

    @Test fun noRequestStartsAfterTheDeadline(): Unit = runBlocking {
        now = 1_000
        val rpc = rpc(deadline = { 1_000 })
        assertEquals("Timed out", assertFailsWith<TimedOut> { rpc.call(BADGE, NAME) }.message)
        assertFailsWith<TimedOut> { rpc.balance(HOLDER) }
        assertTrue(requests.isEmpty())
        assertEquals(0, accepts.get())
        //A view keeps the earlier of the two deadlines
        now = 0
        val open = rpc(deadline = { 5_000 })
        open.verifyChain()
        now = 2_000
        assertFailsWith<TimedOut> { open.until(2_000).call(BADGE, NAME) }
        assertFailsWith<TimedOut> { rpc(deadline = { 1_000 }).until(9_000).call(BADGE, NAME) }
        open.until(3_000).call(BADGE, NAME)
        assertEquals(1, calls("eth_call"))
    }

    @Test fun eachRequestIsCutToTheTimeLeft(): Unit = runBlocking {
        answer = { r -> Answer(200, """{"jsonrpc":"2.0","id":${r.id},"result":"0x00"}""", delayMs = 5_000) }
        val clock = { System.nanoTime() / 1_000_000 }
        val rpc = rpc(clock = clock)
        rpc.verifyChain()
        val bounded = rpc.until(clock() + 300)
        val started = System.nanoTime()
        assertFailsWith<IOException> { bounded.call(BADGE, NAME) }
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue(took < 2_000, "took $took ms")
    }

    @Test fun airGappedModeRefusesBeforeAnySocket(): Unit = runBlocking {
        NetworkPolicy.blocked = true
        val e = assertFailsWith<AirGappedException> { rpc().call(BADGE, NAME) }
        assertEquals("LitVM", e.what)
        //The same words the other endpoints show
        assertEquals("Air-gapped mode is on: LitVM is disabled", e.message)
        assertEquals(AirGappedException("Esplora").message!!.replace("Esplora", "LitVM"), e.message)
        assertEquals(0, accepts.get())
        assertTrue(requests.isEmpty())
    }

    @Test fun theProxyGetsTheHostname(): Unit = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        ServerSocket(0).use { socks ->
            thread(isDaemon = true) {
                while(!socks.isClosed) {
                    val c = runCatching { socks.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serveSocks5(c, asked, server.localPort) } }
                }
            }
            //An unresolvable name: the client must hand it to the proxy, never look it up itself
            val rpc = rpc(at = "http://litvm-rpc.invalid:8545/", proxy = SocksProxy("127.0.0.1", socks.localPort))
            assertContentEquals(byteArrayOf(1), rpc.call(BADGE, NAME))
            rpc.close()
        }
        assertEquals(listOf("litvm-rpc.invalid:8545"), asked)
        assertTrue(requests.first().headers.contains("Host: litvm-rpc.invalid:8545"))
    }

    @Test fun aClosedClientAsksNothing(): Unit = runBlocking {
        val rpc = rpc()
        rpc.verifyChain()
        rpc.close()
        assertEquals("The LitVM connection was closed", assertFailsWith<LitVmException> { rpc.call(BADGE, NAME) }.message)
        assertFailsWith<LitVmException> { rpc.balance(HOLDER) }
        assertFailsWith<LitVmException> { rpc.until(Long.MAX_VALUE).hasCode(BADGE) }
        assertEquals(1, requests.size)
    }

    @Test fun sequentialCallsShareOneConnection(): Unit = runBlocking {
        val rpc = rpc()
        repeat(10) { rpc.call(BADGE, NAME) }
        rpc.close()
        assertEquals(11, requests.size)
        assertEquals(1, accepts.get(), "connections for 11 requests")
    }

    @Test fun anEndpointWithoutTheFlagStillOpensOneConnectionPerPost(): Unit = runBlocking {
        //Today's behaviour for Esplora's broadcast and the indexers, unchanged
        val http = HttpEndpoint(url, label = "Esplora")
        repeat(5) { http.post("/", """{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}""", "application/json") }
        http.close()
        assertEquals(5, accepts.get())
    }

    @Test fun aDeadKeptConnectionIsRetriedOnceForAReadOnlyPost(): Unit = runBlocking {
        closeEach = true
        val rpc = rpc()
        rpc.verifyChain()
        Thread.sleep(200) // let the server's close arrive
        assertContentEquals(byteArrayOf(1), rpc.call(BADGE, NAME))
        assertEquals(2, accepts.get())
        assertEquals(1, calls("eth_call"))
    }

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

    private companion object {
        val BADGE = EvmAddress.parse("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3")!!
        val HOLDER = EvmAddress.parse("0x840e41D4484041A43f9307a90389edeB3d111fb3")!!
        val NAME = byteArrayOf(0x06, -3, -34, 0x03)
    }
}
