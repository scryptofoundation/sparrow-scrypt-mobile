package com.sparrowwallet.mobile

import android.content.ContextWrapper
import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppGuardsTest {
    @AfterTest fun reset() { NetworkPolicy.blocked = false }

    @Test fun priceFetchOpensNoConnectionWhenBlocked(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 500
            NetworkPolicy.blocked = true
            assertNull(PriceService.fetch("http://127.0.0.1:${server.localPort}/"))
            assertFailsWith<SocketTimeoutException> { server.accept() }
        }
    }

    @Test fun mwebdRefusesToStartWhenBlocked() {
        NetworkPolicy.blocked = true
        assertFailsWith<AirGappedException> { MwebdService.get(ContextWrapper(null), Network.TESTNET, null) }
    }

    // WorkManager.getInstance throws in a JVM test, so returning quietly proves the guard ran first
    @Test fun scheduleIsNoOpWhenBlocked() {
        NetworkPolicy.blocked = true
        SyncWorker.schedule(ContextWrapper(null), 15)
        SyncWorker.runNow(ContextWrapper(null))
    }

    @Test fun stopAllWithNothingRunningIsSafe() {
        MwebdService.stopAll()
    }

    // SyncStore(null context) would throw, so false without an exception means nothing was touched
    @Test fun syncWorkerDoesNothingWhenBlocked(): Unit = runBlocking {
        NetworkPolicy.blocked = true
        assertFalse(SyncWorker.runChecks(ContextWrapper(null)))
    }
}
