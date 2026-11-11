package com.sparrowwallet.mobile

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A minimal local HTTP/1.1 server for the app's unit tests (the JDK's HttpServer is not on the Android test classpath).
 * [route] gets the method, path and body and gives the 200 answer, or null to never reply (a hung server) until [close].
 * Each answer closes its connection.
 */
internal class TinyHttp(private val route: (method: String, path: String, body: String) -> String?) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val release = CountDownLatch(1)
    val url = "http://127.0.0.1:${server.localPort}"

    init {
        Thread {
            while(!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val (method, path) = lines.first().split(" ").let { parts -> parts[0] to parts[1].substringBefore('?') }
            val length = lines.drop(1).firstOrNull { line -> line.lowercase().startsWith("content-length:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = ByteArray(length).also { bytes -> var read = 0; while(read < length) { val n = input.read(bytes, read, length - read); if(n < 0) break; read += n } }
            val answer = route(method, path, body.decodeToString())
            if(answer == null) {
                release.await(30, TimeUnit.SECONDS)
                return
            }
            val bytes = answer.encodeToByteArray()
            it.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                write(bytes)
                flush()
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while(true) {
            val b = input.read()
            if(b < 0) return null
            out.write(b)
            last4 = (last4 shl 8) or b
            if(last4 == 0x0d0a0d0a) return out.toByteArray().decodeToString()
        }
    }

    override fun close() {
        release.countDown()
        server.close()
    }

    companion object {
        /** An ord 0.20 server for Litecoin mainnet: its status and block 0 answer; [rest] decides every other request. */
        fun ord(rest: (method: String, path: String, body: String) -> String?): TinyHttp = TinyHttp { method, path, body ->
            when(path) {
                "/status" -> """{"chain":"mainnet","height":5,"rune_index":true}"""
                "/blockhash/0" -> "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2"
                else -> rest(method, path, body)
            }
        }
    }
}
