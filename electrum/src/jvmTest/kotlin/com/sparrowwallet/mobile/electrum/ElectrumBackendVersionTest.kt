package com.sparrowwallet.mobile.electrum

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** ElectrumX answers server.version once per connection and refuses a second one. */
class ElectrumBackendVersionTest {
    private fun electrumX(server: ServerSocket) = thread(isDaemon = true) {
        server.accept().use { peer ->
            val reader = peer.getInputStream().bufferedReader()
            val writer = peer.getOutputStream().bufferedWriter()
            var versions = 0
            while(true) {
                val line = reader.readLine() ?: break
                val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(line)!!.groupValues[1]
                val answer = if("server.version" in line && versions++ == 0) {
                    "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":[\"ElectrumX 1.16.0\",\"1.4\"]}"
                } else {
                    "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":1,\"message\":\"server.version already sent\"}}"
                }
                writer.write(answer + "\n")
                writer.flush()
            }
        }
    }

    @Test fun serverVersionAfterConnectDoesNotAskAgain(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            electrumX(server)
            val backend = ElectrumBackend(ElectrumClient("127.0.0.1", server.localPort, useTls = false))
            backend.connect()
            assertEquals(listOf("ElectrumX 1.16.0", "1.4"), backend.serverVersion())
            backend.close()
        }
    }
}
