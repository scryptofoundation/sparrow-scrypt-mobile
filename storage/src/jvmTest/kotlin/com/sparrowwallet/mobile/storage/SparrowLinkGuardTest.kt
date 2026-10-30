package com.sparrowwallet.mobile.storage

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SparrowLinkGuardTest {
    @AfterTest fun reset() { NetworkPolicy.blocked = false }

    @Test fun receiveRefusedWithoutOpeningSocketWhenBlocked() {
        ServerSocket(0).use { server ->
            server.soTimeout = 500
            NetworkPolicy.blocked = true
            val pairing = SparrowLink.Pairing("wallet", listOf("127.0.0.1"), server.localPort, ByteArray(32))
            assertFailsWith<AirGappedException> { SparrowLink.receiveWallet(pairing) }
            assertFailsWith<SocketTimeoutException> { server.accept() }
        }
    }
}
