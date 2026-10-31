package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.NetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal HTTP/1.1 client for REST servers, built on a plain socket the way [ElectrumClient]
 * is, so a SOCKS proxy (Tor) gets the hostname unresolved and nothing is looked up locally
 * (the JDK's HttpURLConnection resolves the host before handing it to a SOCKS proxy). TLS
 * checks the certificate and the hostname. One request per connection; bodies are capped,
 * since the server is untrusted.
 */
class HttpEndpoint(baseUrl: String, private val proxy: SocksProxy? = null, private val timeoutMs: Int = 30_000) {
    class Response(val status: Int, val body: String)

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

    private suspend fun request(method: String, path: String, body: String?): Response = withContext(Dispatchers.IO) {
        NetworkPolicy.check("Esplora")
        val raw = if(proxy != null) Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port))) else Socket()
        val release = NetworkPolicy.whileOpen { runCatching { raw.close() } } // air-gapped mode cuts it mid-request too
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
            val payload = body?.encodeToByteArray()
            val hostHeader = if((tls && port == 443) || (!tls && port == 80)) host else "$host:$port"
            val head = buildString {
                append("$method $prefix$path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("User-Agent: sparrow-ltc-mobile\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n")
                if(payload != null) {
                    append("Content-Type: text/plain\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                }
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.encodeToByteArray())
                if(payload != null) write(payload)
                flush()
            }
            readResponse(socket.getInputStream())
        } finally {
            release()
            runCatching { raw.close() }
        }
    }

    private fun readResponse(input: InputStream): Response {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while(matched < 4) {
            val b = input.read()
            if(b < 0) throw IOException("Connection closed in the response headers")
            headerBytes.write(b)
            if(headerBytes.size() > MAX_HEADERS) throw IOException("Response headers too large")
            matched = if(b == "\r\n\r\n"[matched].code) matched + 1 else if(b == '\r'.code) 1 else 0
        }
        val lines = headerBytes.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val status = lines[0].split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Not an HTTP response")
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if(colon > 0) line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim() else null
        }.toMap()
        val body = when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input)
            headers["content-length"] != null -> {
                val length = headers["content-length"]!!.toLongOrNull() ?: throw IOException("Bad Content-Length")
                if(length > MAX_BODY) throw IOException("Response too large")
                readExactly(input, length.toInt())
            }
            else -> readToEnd(input)
        }
        return Response(status, body.decodeToString())
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
            val sizeLine = readLine(input)
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("Bad chunk size")
            if(size == 0L) return out.toByteArray()
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
        const val MAX_BODY = 4 * 1024 * 1024
        private const val MAX_HEADERS = 64 * 1024
    }
}
