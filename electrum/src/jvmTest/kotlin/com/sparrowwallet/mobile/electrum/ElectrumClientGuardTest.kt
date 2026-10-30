package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A local ServerSocket proves no connection is even attempted while air-gapped. */
class ElectrumClientGuardTest {
    @AfterTest fun reset() { NetworkPolicy.blocked = false }

    @Test fun connectRefusedWithoutOpeningSocketWhenBlocked(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 500
            NetworkPolicy.blocked = true
            assertFailsWith<AirGappedException> {
                ElectrumClient("127.0.0.1", server.localPort, useTls = false).connect()
            }
            assertFailsWith<SocketTimeoutException> { server.accept() }
        }
    }

    @Test fun callInFlightIsCutWhenAirGapped(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 2_000
            val client = ElectrumClient("127.0.0.1", server.localPort, useTls = false)
            client.connect()
            server.accept().use { // never answers
                val call = async(Dispatchers.IO) { runCatching { client.tipHeight() } }
                delay(300)
                NetworkPolicy.blocked = true
                val result = withTimeout(2_000) { call.await() }
                assertTrue(result.isFailure)
            }
        }
    }

    @Test fun connectedClientSendsNothingOnceAirGapped(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 2_000
            val client = ElectrumClient("127.0.0.1", server.localPort, useTls = false)
            client.connect()
            server.accept().use { peer ->
                NetworkPolicy.blocked = true
                assertFailsWith<AirGappedException> { client.tipHeight() }
                peer.soTimeout = 500
                assertEquals(-1, runCatching { peer.getInputStream().read() }.getOrDefault(-1))
            }
        }
    }

    @Test fun connectStillWorksWhenOpen(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 2_000
            val client = ElectrumClient("127.0.0.1", server.localPort, useTls = false)
            client.connect()
            server.accept().close()
            client.close()
        }
    }
}
