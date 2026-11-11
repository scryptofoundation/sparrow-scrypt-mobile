package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.electrum.indexer.Indexers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InscriptionSettingTest {
    private fun parse(stored: String?, hasPublic: Boolean = true, defaultChoice: String? = null) =
        InscriptionSetting.parse(stored, hasPublic, defaultChoice)

    @Test
    fun parseTable() {
        assertEquals(InscriptionSetting.Public, parse("public"))
        assertEquals(InscriptionSetting.BuiltIn, parse("public", hasPublic = false), "a saved Public on a coin with no public indexer")
        assertEquals(InscriptionSetting.YourOwn("http://192.168.1.5:8085"), parse("custom:http://192.168.1.5:8085"))
        assertEquals(InscriptionSetting.YourOwn("https://ord.example"), parse("custom:https://ord.example", hasPublic = false))
        assertEquals(InscriptionSetting.BuiltIn, parse("custom:ftp://x"), "a stored URL that is not http(s)")
        assertEquals(InscriptionSetting.BuiltIn, parse("builtin"))
        assertEquals(InscriptionSetting.BuiltIn, parse("off"), "a saved Off reads as Built-in")
        assertEquals(InscriptionSetting.BuiltIn, parse("off", defaultChoice = "public"), "a saved Off stays Built-in after the notice")
        assertEquals(InscriptionSetting.BuiltIn, parse("garbage", defaultChoice = "public"))
        //nothing saved: the notice's answer decides
        assertEquals(InscriptionSetting.BuiltIn, parse(null))
        assertEquals(InscriptionSetting.Public, parse(null, defaultChoice = "public"))
        assertEquals(InscriptionSetting.BuiltIn, parse(null, hasPublic = false, defaultChoice = "public"))
        assertEquals(InscriptionSetting.BuiltIn, parse(null, defaultChoice = "builtin"))
        assertEquals(InscriptionSetting.BuiltIn, parse(null, defaultChoice = "something else"))
    }

    @Test
    fun storedRoundTrips() {
        assertEquals("public", InscriptionSetting.Public.stored())
        assertEquals("builtin", InscriptionSetting.BuiltIn.stored())
        assertEquals("custom:http://10.0.0.2:8085", InscriptionSetting.YourOwn("http://10.0.0.2:8085").stored())
        for(s in listOf(InscriptionSetting.Public, InscriptionSetting.BuiltIn, InscriptionSetting.YourOwn("https://ord.example:8080/"))) {
            assertEquals(s, parse(s.stored()))
        }
    }

    @Test
    fun urlPerCoin() {
        assertEquals("https://ordinalslite.com", InscriptionSetting.Public.url(Coin.LITECOIN, Network.MAINNET))
        assertEquals("https://pepe-mainnet-api.nintondo.io", InscriptionSetting.Public.url(Coin.PEPECOIN, Network.MAINNET))
        assertNull(InscriptionSetting.Public.url(Coin.JUNKCOIN, Network.MAINNET))
        assertNull(InscriptionSetting.BuiltIn.url(Coin.LITECOIN, Network.MAINNET))
        assertEquals("http://10.0.0.2:8085", InscriptionSetting.YourOwn("http://10.0.0.2:8085/").url(Coin.JUNKCOIN, Network.MAINNET))
    }

    @Test
    fun noIndexerBeforeTheNoticeIsAnswered() {
        val prefs = InscriptionPrefs(FakePrefs())
        assertNull(prefs.defaultChoice())
        for(coin in Coin.entries) for(network in Network.entries) {
            assertEquals(InscriptionSetting.BuiltIn, prefs.setting(coin, network), "$coin $network")
            assertNull(prefs.setting(coin, network).url(coin, network), "$coin $network")
        }
        //answered Built-in: still no indexer anywhere
        prefs.saveDefaultChoice("builtin")
        for(coin in Coin.entries) for(network in Network.entries) {
            assertNull(prefs.setting(coin, network).url(coin, network), "$coin $network")
        }
    }

    @Test
    fun publicAnswerAppliesOnlyWhereNothingIsSaved() {
        val prefs = InscriptionPrefs(FakePrefs())
        prefs.save(Coin.DOGECOIN, Network.MAINNET, InscriptionSetting.parse("off", true, null))
        prefs.save(Coin.PEPECOIN, Network.MAINNET, InscriptionSetting.YourOwn("http://10.0.0.2:8085"))
        prefs.saveDefaultChoice("public")
        assertEquals(InscriptionSetting.Public, prefs.setting(Coin.LITECOIN, Network.MAINNET))
        assertEquals("https://ordinalslite.com", prefs.setting(Coin.LITECOIN, Network.MAINNET).url(Coin.LITECOIN, Network.MAINNET))
        assertEquals(InscriptionSetting.BuiltIn, prefs.setting(Coin.DOGECOIN, Network.MAINNET), "a saved Off stays off any indexer")
        assertEquals(InscriptionSetting.YourOwn("http://10.0.0.2:8085"), prefs.setting(Coin.PEPECOIN, Network.MAINNET))
        assertEquals(InscriptionSetting.BuiltIn, prefs.setting(Coin.JUNKCOIN, Network.MAINNET), "no public indexer")
        assertEquals(InscriptionSetting.BuiltIn, prefs.setting(Coin.LITECOIN, Network.TESTNET), "no public indexer")
    }

    @Test
    fun explicitSettingKeepsItsMeaning() {
        val prefs = InscriptionPrefs(FakePrefs())
        prefs.save(Coin.LITECOIN, Network.MAINNET, InscriptionSetting.Public)
        assertEquals(InscriptionSetting.Public, prefs.setting(Coin.LITECOIN, Network.MAINNET), "a saved Public needs no notice")
        assertFalse(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET, airGapped = false))
    }

    @Test
    fun noticeIsDueOnlyOnce() {
        for(answer in listOf("public", "builtin")) {
            val prefs = InscriptionPrefs(FakePrefs())
            assertTrue(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET, airGapped = false))
            assertTrue(prefs.noticeDue(Coin.DOGECOIN, Network.MAINNET, airGapped = false))
            prefs.saveDefaultChoice(answer)
            assertEquals(answer, prefs.defaultChoice())
            for(coin in Coin.entries) for(network in Network.entries) {
                assertFalse(prefs.noticeDue(coin, network, airGapped = false), "$answer: $coin $network")
            }
        }
    }

    @Test
    fun noticeIsNotDueWhereNoPublicIndexerExists() {
        val prefs = InscriptionPrefs(FakePrefs())
        assertFalse(prefs.noticeDue(Coin.JUNKCOIN, Network.MAINNET, airGapped = false))
        assertFalse(prefs.noticeDue(Coin.LITECOIN, Network.TESTNET, airGapped = false))
        assertTrue(prefs.noticeDue(Coin.PEPECOIN, Network.TESTNET, airGapped = false))
    }

    @Test
    fun noticeIsNotDueWhenAirGappedOrSomethingIsSaved() {
        val prefs = InscriptionPrefs(FakePrefs())
        assertFalse(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET, airGapped = true))
        prefs.save(Coin.LITECOIN, Network.MAINNET, InscriptionSetting.BuiltIn)
        assertFalse(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET, airGapped = false))
        assertTrue(prefs.noticeDue(Coin.BELLSCOIN, Network.MAINNET, airGapped = false))
    }

    @Test
    fun noticeCopyIsExact() {
        assertEquals("Inscription checks", InscriptionNotice.TITLE)
        assertEquals("Use the public indexer", InscriptionNotice.USE_PUBLIC)
        assertEquals("Use Built-in instead", InscriptionNotice.USE_BUILT_IN)
        assertEquals("ordinalslite.com", InscriptionNotice.host(Coin.LITECOIN, Network.MAINNET))
        assertNull(InscriptionNotice.host(Coin.JUNKCOIN, Network.MAINNET))
        assertEquals("Sparrow can ask a public indexer (ordinalslite.com) which of this wallet's coins carry inscriptions " +
            "or runes. That tells the indexer which coins are yours. It goes through your Tor proxy when one is set. You " +
            "can check on-chain only instead: that is slower and cannot see runes.", InscriptionNotice.body("ordinalslite.com"))
        assertEquals("public", InscriptionNotice.choice(usePublic = true))
        assertEquals("builtin", InscriptionNotice.choice(usePublic = false))
        assertEquals(Indexers.publicUrl(Coin.DOGECOIN, Network.MAINNET), "https://" + InscriptionNotice.host(Coin.DOGECOIN, Network.MAINNET))
    }
}
