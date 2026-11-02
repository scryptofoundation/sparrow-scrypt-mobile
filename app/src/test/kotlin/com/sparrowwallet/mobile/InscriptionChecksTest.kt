package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InscriptionChecksTest {
    @Test
    fun settingRoundTrips() {
        assertEquals(InscriptionSetting.Off, InscriptionSetting.parse(null))
        assertEquals(InscriptionSetting.Off, InscriptionSetting.parse("off"))
        assertEquals(InscriptionSetting.Public, InscriptionSetting.parse(InscriptionSetting.Public.stored()))
        val custom = InscriptionSetting.Custom("http://192.168.1.5:8085")
        assertEquals(custom, InscriptionSetting.parse(custom.stored()))
        assertEquals(InscriptionSetting.Off, InscriptionSetting.parse("custom:ftp://x"), "a stored URL that is not http(s) leaves checks off")
    }

    @Test
    fun urlPerCoin() {
        assertEquals("https://ordinalslite.com", InscriptionSetting.Public.url(Coin.LITECOIN, Network.MAINNET))
        assertEquals("https://pepe-mainnet-api.nintondo.io", InscriptionSetting.Public.url(Coin.PEPECOIN, Network.MAINNET))
        assertNull(InscriptionSetting.Public.url(Coin.JUNKCOIN, Network.MAINNET))
        assertNull(InscriptionSetting.Off.url(Coin.LITECOIN, Network.MAINNET))
        assertEquals("http://10.0.0.2:8085", InscriptionSetting.Custom("http://10.0.0.2:8085/").url(Coin.JUNKCOIN, Network.MAINNET))
    }

    /** The indexer's answer decides; without one (off, unknown or unreachable) the local rule holds the coin. */
    @Test
    fun indexerAnswerDecides() {
        assertTrue(heldFrom(serverAnswer = true) { false })
        assertFalse(heldFrom(serverAnswer = false) { true })
        assertTrue(heldFrom(serverAnswer = null) { true })
        assertFalse(heldFrom(serverAnswer = null) { false })
    }

    /** Nintondo indexes inscriptions only: its "clean" cannot clear a coin made by a runestone or dunestone transaction. */
    @Test
    fun inscriptionOnlyIndexerCannotClearTokenCoins() {
        assertTrue(heldFrom(serverAnswer = false, tokensIndexed = false, writesTokens = { true }) { false })
        assertFalse(heldFrom(serverAnswer = false, tokensIndexed = false, writesTokens = { false }) { true })
        assertFalse(heldFrom(serverAnswer = false, tokensIndexed = true, writesTokens = { true }) { true })
    }
}
