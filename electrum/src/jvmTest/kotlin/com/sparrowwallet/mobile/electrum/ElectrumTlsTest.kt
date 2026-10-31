package com.sparrowwallet.mobile.electrum

import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A certificate that chains to a trusted root is still refused when it names another host. */
class ElectrumTlsTest {
    private fun keystoreFor(name: String): KeyStore {
        val file = File.createTempFile("electrum-tls", ".p12").also { it.delete(); it.deleteOnExit() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=$name", "-ext", "SAN=dns:$name", "-validity", "2",
            "-keystore", file.path, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"
        ).redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readBytes().decodeToString() }
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "changeit".toCharArray()) } }
    }

    @Test fun certificateForAnotherHostIsRefused() {
        val ks = keystoreFor("other.example")
        val serverCtx = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }.keyManagers, null, null)
        }
        val trusting = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers, null)
        }
        (serverCtx.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { server ->
            thread(isDaemon = true) {
                while(!server.isClosed) {
                    val c = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { c.use { it.getInputStream().read() } } }
                }
            }
            assertFailsWith<SSLHandshakeException> {
                runBlocking { ElectrumClient("127.0.0.1", server.localPort, useTls = true, tlsFactory = trusting.socketFactory).connect() }
            }
        }
    }
}
