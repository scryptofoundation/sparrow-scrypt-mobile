package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainRulesTest {
    @Test
    fun firstInscriptionHeights() {
        assertEquals(2_424_429, ChainRules.firstInscriptionHeight(Coin.LITECOIN, Network.MAINNET))
        assertEquals(2_669_127, ChainRules.firstInscriptionHeight(Coin.LITECOIN, Network.TESTNET))
        assertEquals(4_600_000, ChainRules.firstInscriptionHeight(Coin.DOGECOIN, Network.MAINNET))
        assertEquals(186_920, ChainRules.firstInscriptionHeight(Coin.PEPECOIN, Network.MAINNET))
        assertEquals(22_470, ChainRules.firstInscriptionHeight(Coin.BELLSCOIN, Network.MAINNET))
        assertEquals(273_000, ChainRules.firstInscriptionHeight(Coin.JUNKCOIN, Network.MAINNET))
        for(coin in listOf(Coin.DOGECOIN, Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN)) {
            assertEquals(0, ChainRules.firstInscriptionHeight(coin, Network.TESTNET), "$coin TESTNET")
        }
    }

    @Test
    fun litecoinSubsidy() {
        assertEquals(5_000_000_000L, ChainRules.subsidy(Coin.LITECOIN, Network.MAINNET, 0))
        assertEquals(2_500_000_000L, ChainRules.subsidy(Coin.LITECOIN, Network.MAINNET, 840_000))
        assertEquals(1_250_000_000L, ChainRules.subsidy(Coin.LITECOIN, Network.MAINNET, 2_500_000))
        assertEquals(2_500_000_000L, ChainRules.subsidy(Coin.LITECOIN, Network.TESTNET, 840_000))
        assertEquals(0L, ChainRules.subsidy(Coin.LITECOIN, Network.MAINNET, 840_000 * 64))
    }

    @Test
    fun dogecoinSubsidy() {
        assertEquals(1_000_000_000_000L, ChainRules.subsidy(Coin.DOGECOIN, Network.MAINNET, 4_600_000))
        assertNull(ChainRules.subsidy(Coin.DOGECOIN, Network.MAINNET, 500_000))
        assertNull(ChainRules.subsidy(Coin.DOGECOIN, Network.TESTNET, 700_000))
    }

    @Test
    fun otherCoinsHaveNoCodedSubsidy() {
        for(coin in listOf(Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN)) {
            for(network in Network.entries) {
                assertNull(ChainRules.subsidy(coin, network, 300_000), "$coin $network")
            }
        }
    }

    /** Runes on Litecoin, Dunes on Dogecoin and Pepecoin, junes on Junkcoin; Bellscoin has none. */
    @Test
    fun runeLikeTokens() {
        for(coin in listOf(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.JUNKCOIN)) {
            assertTrue(ChainRules.hasRuneLikeTokens(coin), coin.toString())
        }
        assertFalse(ChainRules.hasRuneLikeTokens(Coin.BELLSCOIN))
    }

    @Test
    fun covers() {
        for(coin in listOf(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN)) {
            assertTrue(ChainRules.covers(coin), coin.toString())
        }
        assertFalse(ChainRules.covers(Coin.LUCKYCOIN))
        assertFalse(ChainRules.covers(Coin.DINGOCOIN))
    }
}
