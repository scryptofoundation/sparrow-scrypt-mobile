package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.Network
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerFallbackTest {
    @Test fun aDeadServerFallsThroughToTheNextOne(): Unit = runBlocking {
        val tried = mutableListOf<String>()
        val (used, result) = ServerFallback.firstWorking(listOf("dead", "alive", "unused")) { s ->
            tried += s
            if(s == "dead") throw IOException("Connection refused") else "ok from $s"
        }
        assertEquals("alive", used)
        assertEquals("ok from alive", result)
        assertEquals(listOf("dead", "alive"), tried)
    }

    @Test fun whenEveryServerFailsTheLastErrorIsThrown(): Unit = runBlocking {
        val e = assertFailsWith<IOException> {
            ServerFallback.firstWorking(listOf("a", "b")) { s -> throw IOException("down: $s") }
        }
        assertEquals("down: b", e.message)
    }

    @Test fun airGappedModeStopsAtTheFirstServer(): Unit = runBlocking {
        val tried = mutableListOf<String>()
        assertFailsWith<AirGappedException> {
            ServerFallback.firstWorking(listOf("a", "b")) { s -> tried += s; throw AirGappedException("Electrum") }
        }
        assertEquals(listOf("a"), tried)
    }

    @Test fun lastWorkingServerIsTriedFirst() {
        val ordered = PublicServers.ordered(Network.MAINNET, lastGoodHost = "electrum.petrkr.net")
        assertEquals("electrum.petrkr.net", ordered.first().host)
        assertEquals(PublicServers.forNetwork(Network.MAINNET).size, ordered.size)
    }

    @Test fun defaultMainnetServersUseTrustedCertificatesAndDoNotLeadWithTheDeadOne() {
        val servers = PublicServers.forNetwork(Network.MAINNET)
        assertTrue(servers.first().host != "ltc.rentonisk.com")
        assertTrue(servers.all { it.useTls && !it.allowSelfSigned })
    }
}
