package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.BackendKind
import com.sparrowwallet.mobile.crypto.Coin
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

    @Test fun everyCoinHasItsDesktopServers() {
        fun hosts(coin: Coin, network: Network) = PublicServers.forCoin(coin, network).map { it.host }
        assertEquals(PublicServers.forNetwork(Network.MAINNET), PublicServers.forCoin(Coin.LITECOIN, Network.MAINNET))
        assertEquals(listOf("doge.electrum1.cipig.net", "doge.electrum2.cipig.net", "dogecoin.stackwallet.com", "doge.aftrek.org"),
            hosts(Coin.DOGECOIN, Network.MAINNET))
        assertTrue(PublicServers.forCoin(Coin.DOGECOIN, Network.MAINNET).last().allowSelfSigned)
        assertEquals(listOf("seed1.dingocoin.com", "seed3.dingocoin.com", "elecx2.kmsoftware.org"), hosts(Coin.DINGOCOIN, Network.MAINNET))
        assertEquals(listOf("https://api.nintondo.io"), PublicServers.forCoin(Coin.BELLSCOIN, Network.MAINNET).map { it.esploraUrl })
        assertEquals(listOf("https://peppool.space/api"), PublicServers.forCoin(Coin.PEPECOIN, Network.MAINNET).map { it.esploraUrl })
        assertEquals(listOf("https://luckyscan.org/api"), PublicServers.forCoin(Coin.LUCKYCOIN, Network.MAINNET).map { it.esploraUrl })
        assertEquals(listOf("https://explorer.junk-coin.com/api"), PublicServers.forCoin(Coin.JUNKCOIN, Network.MAINNET).map { it.esploraUrl })
        assertEquals(listOf("https://pepe-testnet-api.nintondo.io"), PublicServers.forCoin(Coin.PEPECOIN, Network.TESTNET).map { it.esploraUrl })
        assertEquals(listOf("https://jkc-testnet-api.s3na.xyz"), PublicServers.forCoin(Coin.JUNKCOIN, Network.TESTNET).map { it.esploraUrl })
        for(coin in listOf(Coin.DOGECOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN, Coin.BELLSCOIN)) {
            assertTrue(PublicServers.forCoin(coin, Network.TESTNET).isEmpty(), "$coin testnet")
        }
    }

    @Test fun esploraCoinsUseUrls() {
        for(coin in Coin.entries) for(network in Network.entries) {
            for(s in PublicServers.forCoin(coin, network)) {
                assertEquals(coin.backend == BackendKind.ESPLORA, s.esploraUrl != null, "$coin $network ${s.host}")
            }
        }
    }

    @Test fun lastWorkingServerIsTriedFirstPerCoin() {
        val ordered = PublicServers.ordered(Coin.DOGECOIN, Network.MAINNET, lastGoodHost = "dogecoin.stackwallet.com")
        assertEquals("dogecoin.stackwallet.com", ordered.first().host)
    }

    @Test fun defaultMainnetServersUseTrustedCertificatesAndDoNotLeadWithTheDeadOne() {
        val servers = PublicServers.forNetwork(Network.MAINNET)
        assertTrue(servers.first().host != "ltc.rentonisk.com")
        assertTrue(servers.all { it.useTls && !it.allowSelfSigned })
    }
}
