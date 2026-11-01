package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Connection reuse in HttpEndpoint, against a raw socket server that counts connections and
 * requests (an HttpServer hides both). Each request on the emulator paid ~1 s of DNS and TLS.
 */
class HttpKeepAliveTest {
    private enum class Mode { KEEP_ALIVE, SAYS_CLOSE, CLOSES_SILENTLY, CHUNKED, HANGS_AFTER_FIRST, DROPS_POSTS }

    private val params = ChainParams.of(Coin.PEPECOIN, Network.TESTNET)
    private val accepts = AtomicInteger()
    private val requests = AtomicInteger()
    private val posts = AtomicInteger()
    private var mode = Mode.KEEP_ALIVE
    private val server = ServerSocket(0)

    init {
        thread(isDaemon = true) {
            while(!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                accepts.incrementAndGet()
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) = s.use {
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        var served = 0
        while(true) {
            val head = readHead(input) ?: return
            val method = head.first().substringBefore(' ')
            val path = head.first().split(' ')[1]
            val length = head.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
            repeat(length) { input.read() }
            requests.incrementAndGet()
            if(method == "POST") posts.incrementAndGet()
            if(mode == Mode.HANGS_AFTER_FIRST && served > 0) { Thread.sleep(10_000); return }
            if(mode == Mode.DROPS_POSTS && method == "POST") return
            served++
            when(path) {
                "/204" -> { out.write("HTTP/1.1 204 X\r\n\r\n".encodeToByteArray()); out.flush(); continue }
                "/100" -> { out.write("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 X\r\nContent-Length: 1\r\n\r\n5".encodeToByteArray()); out.flush(); continue }
                "/huge" -> { out.write("HTTP/1.1 200 X\r\nContent-Length: 5000000\r\n\r\n".encodeToByteArray()); out.flush(); continue }
                "/slow" -> { Thread.sleep(1_500); out.write("HTTP/1.1 200 X\r\nContent-Length: 1\r\n\r\n5".encodeToByteArray()); out.flush(); continue }
            }
            val (status, body) = when {
                path == "/blocks/tip/height" -> 200 to "5"
                method == "POST" && path == "/tx" -> 200 to "ab".repeat(32)
                else -> 404 to "not found"
            }
            val close = mode == Mode.SAYS_CLOSE
            if(mode == Mode.CHUNKED) {
                //as peppool.space answers: the last chunk is followed by an empty trailer line
                out.write(("HTTP/1.1 $status X\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n").encodeToByteArray())
            } else out.write(("HTTP/1.1 $status X\r\nContent-Length: ${body.length}\r\n" +
                (if(close) "Connection: close\r\n" else "") + "\r\n$body").encodeToByteArray())
            out.flush()
            if(close || mode == Mode.CLOSES_SILENTLY) return
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

    private fun backend() = EsploraBackend("http://127.0.0.1:${server.localPort}", params)

    @AfterTest fun stop() {
        server.close()
        NetworkPolicy.blocked = false
    }

    @Test fun requestsShareOneConnection(): Unit = runBlocking {
        val b = backend()
        b.connect()
        b.feeRate(2) // three more requests, all 404
        b.connect()
        b.close()
        assertTrue(requests.get() >= 5, "requests ${requests.get()}")
        assertEquals(1, accepts.get())
    }

    @Test fun serverThatClosesGetsANewConnection(): Unit = runBlocking {
        mode = Mode.SAYS_CLOSE
        val b = backend()
        b.connect()
        b.connect()
        assertEquals(2, accepts.get())
    }

    @Test fun staleConnectionIsReopenedForGets(): Unit = runBlocking {
        mode = Mode.CLOSES_SILENTLY
        val b = backend()
        b.connect()
        Thread.sleep(200) // let the server's close arrive
        b.connect() // the kept socket is dead: a GET is retried once on a new connection
        assertEquals(2, accepts.get())
    }

    @Test fun broadcastAfterAnIdleCloseUsesAFreshConnection(): Unit = runBlocking {
        //the server closed the kept socket while idle: a broadcast must not ride it and fail
        mode = Mode.CLOSES_SILENTLY
        val b = backend()
        b.connect()
        Thread.sleep(200)
        assertEquals("ab".repeat(32), b.broadcast("00"))
        assertEquals(1, posts.get())
        assertEquals(2, accepts.get())
    }

    @Test fun broadcastIsNeverSentTwice(): Unit = runBlocking {
        mode = Mode.DROPS_POSTS
        val b = backend()
        b.connect()
        assertFailsWith<IOException> { b.broadcast("00") }
        assertEquals(1, posts.get())
        assertEquals(2, accepts.get(), "the GET connection, then one fresh one for the POST")
    }

    @Test fun airGapDuringAWaitingRequestOpensNoNewConnection(): Unit = runBlocking {
        mode = Mode.HANGS_AFTER_FIRST
        val b = backend()
        b.connect()
        thread(isDaemon = true) { Thread.sleep(300); NetworkPolicy.blocked = true }
        assertFailsWith<AirGappedException> { b.connect() }
        Thread.sleep(200)
        assertEquals(1, accepts.get(), "no connection after the switch")
    }

    @Test fun slowHandshakeHitsTheDeadline() {
        //a server that trickles a byte every 100 ms never finishes the TLS handshake; soTimeout alone never fires
        ServerSocket(0).use { tls ->
            thread(isDaemon = true) {
                val c = runCatching { tls.accept() }.getOrNull() ?: return@thread
                runCatching { repeat(100) { c.getOutputStream().apply { write(0x16); flush() }; Thread.sleep(100) } }
                runCatching { c.close() }
            }
            val endpoint = HttpEndpoint("https://localhost:${tls.localPort}", timeoutMs = 1_000, deadlineMs = 1_500)
            val started = System.currentTimeMillis()
            assertFailsWith<IOException> { runBlocking { endpoint.get("/blocks/tip/height") } }
            assertTrue(System.currentTimeMillis() - started < 4_000, "took ${System.currentTimeMillis() - started} ms")
        }
    }

    @Test fun airGappedModeClosesAnIdleConnection(): Unit = runBlocking {
        val b = backend()
        b.connect()
        NetworkPolicy.blocked = true
        NetworkPolicy.blocked = false
        b.connect()
        assertEquals(2, accepts.get(), "the idle socket was cut when the mode turned on")
    }

    @Test fun chunkedAnswersShareOneConnection(): Unit = runBlocking {
        mode = Mode.CHUNKED
        val b = backend()
        b.connect()
        b.connect()
        assertEquals(5L, b.tipHeight())
        assertEquals(1, accepts.get())
    }

    private fun endpoint(timeoutMs: Int = 5_000) =
        HttpEndpoint("http://127.0.0.1:${server.localPort}", timeoutMs = timeoutMs, deadlineMs = 10_000L)

    @Test fun bodilessAnswerDoesNotWait(): Unit = runBlocking {
        val e = endpoint()
        val started = System.currentTimeMillis()
        assertEquals(204, e.get("/204").status)
        assertTrue(System.currentTimeMillis() - started < 2_000, "a 204 has no body to wait for")
        assertEquals("5", e.get("/blocks/tip/height").body)
        assertEquals(1, accepts.get())
    }

    @Test fun interimAnswerIsSkipped(): Unit = runBlocking {
        val r = endpoint().get("/100")
        assertEquals(200, r.status)
        assertEquals("5", r.body)
    }

    @Test fun slowFirstByteIsNotSentAgain(): Unit = runBlocking {
        val e = endpoint(timeoutMs = 500)
        e.get("/blocks/tip/height")
        assertFailsWith<IOException> { e.get("/slow") }
        assertEquals(2, requests.get(), "a slow server is not a dead one: the GET is not resent")
    }

    @Test fun overCapAnswerDropsTheConnection(): Unit = runBlocking {
        val e = endpoint()
        assertFailsWith<IOException> { e.get("/huge") }
        assertEquals("5", e.get("/blocks/tip/height").body)
        assertEquals(2, accepts.get())
    }

    @Test fun timingLogBlanksTxidsInEveryRoute() {
        val txid = "ab".repeat(32)
        assertEquals("/address/*/txs/chain/*", HttpEndpoint.route("/address/xyz/txs/chain/$txid"))
        assertEquals("/tx/*/status", HttpEndpoint.route("/tx/$txid/status"))
        assertEquals("/scripthash/*/utxo", HttpEndpoint.route("/scripthash/${"cd".repeat(32)}/utxo"))
    }
}
