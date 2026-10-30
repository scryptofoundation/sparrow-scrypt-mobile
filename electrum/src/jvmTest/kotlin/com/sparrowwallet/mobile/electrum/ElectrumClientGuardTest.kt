package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

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
