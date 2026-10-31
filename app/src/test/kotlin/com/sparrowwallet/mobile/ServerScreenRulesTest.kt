package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerScreenRulesTest {
    @Test
    fun coinWithoutPublicServerHasNoDefault() {
        assertTrue(PublicServers.forCoin(Coin.DOGECOIN, Network.TESTNET).isEmpty())
        assertEquals(ServerScreenState.NeedsOwnServer, serverScreenState(null))
        val s = PublicServers.forCoin(Coin.DOGECOIN, Network.MAINNET).first()
        assertEquals(ServerScreenState.Configured(s), serverScreenState(s))
    }

    @Test
    fun esploraSummaryNamesTheTip() {
        assertEquals("Esplora, tip 1234", connectionSummary(listOf("Esplora", "1234")))
    }

    @Test
    fun electrumSummaryIsUnchanged() {
        assertEquals("Electrum ElectrumX 1.16.0, protocol 1.4", connectionSummary(listOf("ElectrumX 1.16.0", "1.4")))
    }

    @Test
    fun esploraUrlBecomesSettings() {
        val s = esploraSettings("https://peppool.space/api/", useProxy = false, proxyHost = "127.0.0.1", proxyPort = 9050)
        assertEquals("peppool.space", s?.host)
        assertEquals("https://peppool.space/api", s?.esploraUrl)
        assertEquals(null, esploraSettings("ftp://x", false, "127.0.0.1", 9050))
    }
}
