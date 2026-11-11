package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.electrum.indexer.Indexers
import kotlinx.coroutines.runBlocking
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
        assertEquals(InscriptionSetting.BuiltIn, parse("off"), "a saved Off reads as Built-in before the notice")
        assertEquals(InscriptionSetting.Public, parse("off", defaultChoice = "public"), "a saved Off counts as nothing saved")
        assertEquals(InscriptionSetting.BuiltIn, parse("off", hasPublic = false, defaultChoice = "public"))
        assertEquals(InscriptionSetting.BuiltIn, parse("garbage", defaultChoice = "public"))
        assertEquals(InscriptionSetting.BuiltIn, parse("custom:http://a b"), "a malformed stored URL")
        assertEquals(InscriptionSetting.BuiltIn, parse("custom:http://user:pw@host"), "a stored URL with a user name")
        assertEquals(InscriptionSetting.BuiltIn, parse("custom:http://"), "a stored URL with no host")
        assertEquals(InscriptionSetting.YourOwn("http://Host:8085"), parse("custom:HTTP://Host:8085"))
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
        prefs.save(Coin.DOGECOIN, Network.MAINNET, InscriptionSetting.Public)
        assertFalse(prefs.noticeDue(Coin.DOGECOIN, Network.MAINNET, airGapped = false))
        assertTrue(prefs.noticeDue(Coin.BELLSCOIN, Network.MAINNET, airGapped = false))
    }

    /** The repository asks without a flag: the app-wide air-gapped mode decides. */
    @Test
    fun noticeFollowsAirGappedMode() {
        val prefs = InscriptionPrefs(FakePrefs())
        try {
            NetworkPolicy.blocked = true
            assertFalse(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET))
            NetworkPolicy.blocked = false
            assertTrue(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET))
        } finally {
            NetworkPolicy.blocked = false
        }
    }

    /** The old app's Save wrote "off" whether or not the user chose it, so it counts as nothing saved. */
    @Test
    fun aStoredOffCountsAsNothingSaved() {
        val raw = FakePrefs()
        raw.edit().putString(InscriptionPrefs.key(Coin.LITECOIN, Network.MAINNET), "off").apply()
        val prefs = InscriptionPrefs(raw)
        assertEquals(InscriptionSetting.BuiltIn, prefs.setting(Coin.LITECOIN, Network.MAINNET))
        assertNull(prefs.setting(Coin.LITECOIN, Network.MAINNET).url(Coin.LITECOIN, Network.MAINNET))
        assertTrue(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET, airGapped = false))
        prefs.saveDefaultChoice("public")
        assertEquals(InscriptionSetting.Public, prefs.setting(Coin.LITECOIN, Network.MAINNET))
        assertFalse(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET, airGapped = false))
    }

    /** Saving the Server screen with the inscription choice as shown writes nothing for it: the notice still decides. */
    @Test
    fun savingOtherServerFieldsLeavesTheInscriptionChoiceAlone() = runBlocking {
        val raw = FakePrefs()
        val prefs = InscriptionPrefs(raw)
        val shown = prefs.setting(Coin.LITECOIN, Network.MAINNET)
        var opened = 0
        var saved: InscriptionSetting? = InscriptionSetting.Public
        var saves = 0
        val refused = saveInscriptionChoice(shown, null, shown, null, airGapped = false, open = { opened++; error("not asked") }) {
            saves++; saved = it; it?.let { s -> prefs.save(Coin.LITECOIN, Network.MAINNET, s) }
        }
        assertNull(refused)
        assertEquals(1, saves, "the server fields are still saved")
        assertNull(saved, "no inscription setting to write")
        assertEquals(0, opened)
        assertFalse(raw.contains(InscriptionPrefs.key(Coin.LITECOIN, Network.MAINNET)))
        assertTrue(prefs.noticeDue(Coin.LITECOIN, Network.MAINNET, airGapped = false))
        prefs.saveDefaultChoice("public")
        assertEquals(InscriptionSetting.Public, prefs.setting(Coin.LITECOIN, Network.MAINNET))
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
            "can check on-chain only instead: that is slower and cannot see runes. Your answer applies to every coin. You " +
            "can change it for each coin under Server.", InscriptionNotice.body("ordinalslite.com"))
        assertEquals("public", InscriptionNotice.choice(usePublic = true))
        assertEquals("builtin", InscriptionNotice.choice(usePublic = false))
        assertEquals(Indexers.publicUrl(Coin.DOGECOIN, Network.MAINNET), "https://" + InscriptionNotice.host(Coin.DOGECOIN, Network.MAINNET))
    }
}
