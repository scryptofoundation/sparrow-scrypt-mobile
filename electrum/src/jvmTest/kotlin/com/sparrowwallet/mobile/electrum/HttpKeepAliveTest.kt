package com.sparrowwallet.mobile.electrum

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
    private enum class Mode { KEEP_ALIVE, SAYS_CLOSE, CLOSES_SILENTLY, CHUNKED }

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
        while(true) {
            val head = readHead(input) ?: return
            val method = head.first().substringBefore(' ')
            val path = head.first().split(' ')[1]
            val length = head.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
            repeat(length) { input.read() }
            requests.incrementAndGet()
            if(method == "POST") posts.incrementAndGet()
            val (status, body) = if(path == "/blocks/tip/height") 200 to "5" else 404 to "not found"
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

    @Test fun postIsNeverResentAfterAStaleConnection(): Unit = runBlocking {
        mode = Mode.CLOSES_SILENTLY
        val b = backend()
        b.connect()
        Thread.sleep(200)
        assertFailsWith<IOException> { b.broadcast("00") }
        assertEquals(1, accepts.get(), "a broadcast must not open a second connection")
        assertTrue(posts.get() <= 1)
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
}
