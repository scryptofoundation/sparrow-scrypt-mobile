package com.sparrowwallet.mobile.electrum

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Closing an endpoint while it connects: a connect or TLS handshake in progress is cut at once, as air-gapped mode cuts
 * it, rather than running to its timeout (a LitVM screen left during a handshake held its read for up to 30 seconds).
 */
class HttpEndpointCloseTest {
    //Accepts and then says nothing, so a TLS handshake waits for the server's hello
    private val server = ServerSocket(0)
    private val accepted = CountDownLatch(1)
    private val held = ArrayList<Socket>()

    init {
        thread(isDaemon = true) {
            while(!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(held) { held.add(s) }
                accepted.countDown()
            }
        }
    }

    @AfterTest
    fun stop() {
        server.close()
        synchronized(held) { held.forEach { runCatching { it.close() } } }
    }

    @Test
    fun closingCutsAHandshakeInProgress(): Unit = runBlocking {
        val endpoint = HttpEndpoint("https://127.0.0.1:${server.localPort}", timeoutMs = 20_000, label = "Test")
        val started = System.nanoTime()
        val request = async(Dispatchers.IO) { runCatching { endpoint.get("/") } }
        assertTrue(accepted.await(5, TimeUnit.SECONDS), "the endpoint never connected")
        Thread.sleep(200)
        endpoint.close()
        val result = withTimeout(5_000) { request.await() }
        assertIs<IOException>(result.exceptionOrNull())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "the handshake ran on after close")
    }

    /**
     * A close landing after the request's own check but before the connect is marked cuts nothing, so the endpoint asks
     * again once it is marked: here the owner is closed in that window, and no connection is ever made.
     */
    @Test
    fun aCloseJustBeforeTheConnectStopsIt(): Unit = runBlocking {
        var asked = 0
        val endpoint = HttpEndpoint("http://127.0.0.1:${server.localPort}", timeoutMs = 5_000, label = "Test", usable = { ++asked <= 1 })
        val error = runCatching { endpoint.get("/") }.exceptionOrNull()
        assertIs<IOException>(error)
        assertTrue(!accepted.await(500, TimeUnit.MILLISECONDS), "a connection was made after the close")
    }

    /** A request whose whole deadline runs out mid-answer fails with its own type, so a caller can say it took too long. */
    @Test
    fun aRequestThatRunsOutOfTimeSaysSoByType(): Unit = runBlocking {
        val endpoint = HttpEndpoint("http://127.0.0.1:${server.localPort}", timeoutMs = 5_000, deadlineMs = 300, label = "Test")
        val error = runCatching { endpoint.get("/") }.exceptionOrNull()
        assertIs<HttpEndpoint.TookTooLong>(error)
        assertTrue(error.message == "Request took too long")
    }
}
