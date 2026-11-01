package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal HTTP/1.1 client for REST servers, built on a plain socket the way [ElectrumClient]
 * is, so a SOCKS proxy (Tor) gets the hostname unresolved and nothing is looked up locally
 * (the JDK's HttpURLConnection resolves the host before handing it to a SOCKS proxy). TLS
 * checks the certificate and the hostname. Bodies are capped, since the server is untrusted.
 *
 * One connection is kept open between requests (a request on the emulator spent ~1 s of every
 * ~1.1 s on DNS, connect and the TLS handshake). A kept connection the server has since closed is
 * reopened only for a GET that got no answer byte; a POST (a broadcast) is never sent twice.
 * Air-gapped mode cuts the kept connection whether or not a request is running.
 */
class HttpEndpoint(
    baseUrl: String,
    private val proxy: SocksProxy? = null,
    private val timeoutMs: Int = 30_000,
    /** The whole request, however slowly the server trickles its answer. */
    private val deadlineMs: Long = timeoutMs * 2L
) {
    /** [headers] are keyed by lowercase name. */
    class Response(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private val uri = URI(baseUrl.trimEnd('/'))
    private val tls = when(uri.scheme) {
        "https" -> true
        "http" -> false
        else -> throw IllegalArgumentException("Not an http(s) URL: $baseUrl")
    }
    private val host: String = requireNotNull(uri.host) { "No host in $baseUrl" }
    private val port = if(uri.port > 0) uri.port else if(tls) 443 else 80
    private val prefix = uri.rawPath ?: ""

    val url: String = baseUrl.trimEnd('/')

    suspend fun get(path: String): Response = request("GET", path, null)

    suspend fun post(path: String, body: String): Response = request("POST", path, body)

    private class Conn(val raw: Socket, val socket: Socket, val input: InputStream, val release: () -> Unit) {
        fun close() {
            runCatching { socket.close() }
            runCatching { raw.close() }
            release()
        }
    }

    /** A kept connection that died before answering: safe to retry a GET on a new one. */
    private class StaleConnection(cause: IOException) : IOException(cause)

    private val lock = Mutex()
    @Volatile private var conn: Conn? = null

    /** Closes the kept connection, if any. */
    fun close() {
        conn?.let { drop(it) }
    }

    private fun drop(c: Conn) {
        c.close()
        if(conn === c) conn = null
    }

    private suspend fun request(method: String, path: String, body: String?): Response = lock.withLock {
        withContext(Dispatchers.IO) {
            NetworkPolicy.check("Esplora")
            //a broadcast goes on a fresh connection: a kept one may have died while idle, and a POST is never retried
            if(method != "GET") close()
            try {
                exchange(method, path, body)
            } catch(e: StaleConnection) {
                NetworkPolicy.check("Esplora")
                exchange(method, path, body)
            }
        }
    }

    /**
     * Connects (and handshakes) on [raw]. Air-gapped mode is watched from before the connect, so a
     * connect or handshake in progress is cut too.
     */
    private fun open(raw: Socket): Conn {
        var made: Conn? = null
        val release = NetworkPolicy.whileOpen {
            runCatching { raw.close() }
            made?.let { if(conn === it) conn = null }
        }
        try {
            val endpoint = if(proxy != null) InetSocketAddress.createUnresolved(host, port) else InetSocketAddress(host, port)
            raw.connect(endpoint, timeoutMs)
            raw.soTimeout = timeoutMs
            val socket = if(tls) {
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket).also { s ->
                    s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    s.startHandshake()
                }
            } else raw
            return Conn(raw, socket, BufferedInputStream(socket.getInputStream(), 16 * 1024), release).also { made = it }
        } catch(e: Throwable) {
            runCatching { raw.close() }
            release()
            throw e
        }
    }

    private fun exchange(method: String, path: String, body: String?): Response {
        val started = System.nanoTime()
        val kept = conn
        val raw = kept?.raw ?: if(proxy != null) Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port))) else Socket()
        //the deadline covers connect and handshake too, however slowly the server trickles
        val expired = AtomicBoolean(false)
        val deadline = DEADLINES.schedule({ expired.set(true); runCatching { raw.close() } }, deadlineMs, TimeUnit.MILLISECONDS)
        var c: Conn? = kept
        var opened = started
        var answered = false
        var status = -1
        try {
            if(c == null) c = open(raw).also { conn = it }
            opened = System.nanoTime()
            val payload = body?.encodeToByteArray()
            val hostHeader = if((tls && port == 443) || (!tls && port == 80)) host else "$host:$port"
            val head = buildString {
                append("$method $prefix$path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("User-Agent: sparrow-ltc-mobile\r\n")
                append("Accept: */*\r\n")
                append("Connection: keep-alive\r\n")
                if(payload != null) {
                    append("Content-Type: text/plain\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                }
                append("\r\n")
            }
            c.socket.getOutputStream().apply {
                write(head.encodeToByteArray())
                if(payload != null) write(payload)
                flush()
            }
            val (response, reusable) = readResponse(c.input) { answered = true }
            status = response.status
            if(!reusable) drop(c)
            return response
        } catch(e: IOException) {
            c?.let { drop(it) } ?: runCatching { raw.close() }
            if(NetworkPolicy.blocked) throw AirGappedException("Esplora")
            if(expired.get()) throw IOException("Request took too long", e)
            //a dead kept socket ends or resets at once; a slow server times out and must not get the GET twice
            if(kept != null && !answered && e !is java.net.SocketTimeoutException) throw StaleConnection(e)
            throw e
        } catch(e: Throwable) {
            c?.let { drop(it) } ?: runCatching { raw.close() }
            throw e
        } finally {
            deadline.cancel(false)
            //the deadline may have closed the socket just after the answer was read
            if(expired.get()) c?.let { drop(it) }
            timingLog?.let { log ->
                val end = System.nanoTime()
                log("$method ${route(path)} $status ${if(kept != null) "reused" else "open=${(opened - started) / 1_000_000}"} " +
                    "response=${(end - opened) / 1_000_000} total=${(end - started) / 1_000_000}")
            }
        }
    }

    /** The response, and whether the connection can carry another request. */
    private fun readResponse(input: InputStream, onFirstByte: () -> Unit): Pair<Response, Boolean> {
        var interim = 0
        while(true) {
            val (lines, status) = readHead(input, onFirstByte)
            //1xx answers are interim: the real one follows on the same connection
            if(status in 100..199) {
                if(++interim > 8) throw IOException("Too many interim answers")
                continue
            }
            return readRest(input, lines, status)
        }
    }

    private fun readHead(input: InputStream, onFirstByte: () -> Unit): Pair<List<String>, Int> {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while(matched < 4) {
            val b = input.read()
            if(b < 0) throw IOException("Connection closed in the response headers")
            if(headerBytes.size() == 0) onFirstByte()
            headerBytes.write(b)
            if(headerBytes.size() > MAX_HEADERS) throw IOException("Response headers too large")
            matched = if(b == "\r\n\r\n"[matched].code) matched + 1 else if(b == '\r'.code) 1 else 0
        }
        val lines = headerBytes.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val status = lines[0].split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Not an HTTP response")
        return lines to status
    }

    private fun readRest(input: InputStream, lines: List<String>, status: Int): Pair<Response, Boolean> {
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if(colon > 0) line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim() else null
        }.toMap()
        var framed = true
        val body = when {
            status == 204 || status == 304 -> ByteArray(0) // never carry a body
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input)
            headers["content-length"] != null -> {
                val text = headers["content-length"]!!
                if(text.isEmpty() || text.length > 12 || !text.all { it.isDigit() }) throw IOException("Bad Content-Length")
                val length = text.toLong()
                if(length > MAX_BODY) throw IOException("Response too large")
                readExactly(input, length.toInt())
            }
            else -> { framed = false; readToEnd(input) }
        }
        //an HTTP/1.0 answer closes unless it says otherwise
        val connection = headers["connection"]?.lowercase() ?: ""
        val keep = framed && !connection.contains("close") && (!lines[0].startsWith("HTTP/1.0") || connection.contains("keep-alive"))
        return Response(status, body.decodeToString(), headers) to keep
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while(read < length) {
            val n = input.read(out, read, length - read)
            if(n < 0) throw IOException("Connection closed in the response body")
            read += n
        }
        return out
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while(true) {
            val n = input.read(buf)
            if(n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if(out.size() > MAX_BODY) throw IOException("Response too large")
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while(true) {
            val sizeText = readLine(input).substringBefore(';').trim()
            if(sizeText.isEmpty() || sizeText.length > 8 || !sizeText.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw IOException("Bad chunk size")
            }
            val size = sizeText.toLong(16)
            if(size == 0L) {
                //trailer fields end with an empty line; it must be read, or a kept connection's next answer starts mid-message
                var trailers = 0
                while(readLine(input).isNotEmpty()) {
                    if(++trailers > 32) throw IOException("Too many chunk trailers")
                }
                return out.toByteArray()
            }
            if(out.size() + size > MAX_BODY) throw IOException("Response too large")
            out.write(readExactly(input, size.toInt()))
            readLine(input) // CRLF after the chunk
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while(true) {
            val b = input.read()
            if(b < 0) throw IOException("Connection closed in a chunk")
            if(b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
            if(line.length > 1024) throw IOException("Chunk header too long")
        }
    }

    companion object {
        /**
         * Debug builds set this to time each request; addresses and txids are blanked from the route
         * so the log names no wallet.
         */
        @Volatile var timingLog: ((String) -> Unit)? = null

        internal fun route(path: String): String =
            path.replace(Regex("/(address|scripthash)/[^/]+"), "/$1/*").replace(Regex("/[0-9a-fA-F]{64}(?=/|$)"), "/*")

        /** Closes sockets whose request outlived its deadline; one daemon thread for all endpoints. */
        private val DEADLINES = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "http-deadline").apply { isDaemon = true } }

        const val MAX_BODY = 4 * 1024 * 1024
        private const val MAX_HEADERS = 64 * 1024
    }
}
