package com.sparrowwallet.mobile

import kotlin.test.assertNull
import com.sparrowwallet.mobile.electrum.EsploraBackend
import com.sparrowwallet.mobile.electrum.ElectrumBackend
import com.sparrowwallet.mobile.crypto.ChainParams
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

    @Test fun anEsploraCoinCanUseAnElectrumServer() {
        // your own Luckycoin node behind ElectrumX, as the desktop allows
        val electrum = ServerFields(BackendKind.ELECTRUM, host = "10.0.2.2", port = "50102", useTls = false).settings()!!
        assertNull(electrum.esploraUrl)
        assertTrue(Backends.open(electrum, ChainParams.of(Coin.LUCKYCOIN, Network.TESTNET)) is ElectrumBackend)
        assertEquals(BackendKind.ELECTRUM, serverKindFor(Coin.LUCKYCOIN, electrum))
        assertEquals(BackendKind.ESPLORA, serverKindFor(Coin.LUCKYCOIN, null))
        // and the other way round
        val esplora = ServerFields(BackendKind.ESPLORA, esploraUrl = "https://example.org/api/").settings()!!
        assertEquals("https://example.org/api", esplora.esploraUrl)
        assertTrue(Backends.open(esplora, ChainParams.of(Coin.DOGECOIN, Network.MAINNET)) is EsploraBackend)
        assertEquals(BackendKind.ESPLORA, serverKindFor(Coin.DOGECOIN, esplora))
        assertNull(ServerFields(BackendKind.ELECTRUM, host = " ", port = "50002").settings())
        assertNull(ServerFields(BackendKind.ESPLORA, esploraUrl = "ftp://x").settings())
    }
}
