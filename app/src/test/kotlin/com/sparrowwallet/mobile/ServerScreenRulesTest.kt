package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.electrum.SocksProxy
import com.sparrowwallet.mobile.electrum.indexer.IndexerException
import com.sparrowwallet.mobile.electrum.indexer.IndexerInscription
import com.sparrowwallet.mobile.electrum.indexer.IndexerOutput
import com.sparrowwallet.mobile.electrum.indexer.IndexerStatus
import com.sparrowwallet.mobile.electrum.indexer.Indexers
import com.sparrowwallet.mobile.electrum.indexer.InscriptionIndexer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun inscriptionChoicesShowPublicOnlyWhereItExists() {
        val ltc = Indexers.publicUrl(Coin.LITECOIN, Network.MAINNET)
        assertEquals(listOf(InscriptionChoice.PUBLIC, InscriptionChoice.YOUR_OWN, InscriptionChoice.BUILT_IN), inscriptionChoices(ltc))
        assertEquals(listOf(InscriptionChoice.YOUR_OWN, InscriptionChoice.BUILT_IN),
            inscriptionChoices(Indexers.publicUrl(Coin.JUNKCOIN, Network.MAINNET)))
        assertEquals("Public (ordinalslite.com)", inscriptionChoiceLabel(InscriptionChoice.PUBLIC, ltc))
        assertEquals("Public (doge-mainnet-api.nintondo.io)",
            inscriptionChoiceLabel(InscriptionChoice.PUBLIC, Indexers.publicUrl(Coin.DOGECOIN, Network.MAINNET)))
        assertEquals("Your own", inscriptionChoiceLabel(InscriptionChoice.YOUR_OWN, ltc))
        assertEquals("Built-in", inscriptionChoiceLabel(InscriptionChoice.BUILT_IN, null))
    }

    @Test
    fun inscriptionChoiceFollowsTheSetting() {
        assertEquals(InscriptionChoice.PUBLIC, InscriptionChoice.of(InscriptionSetting.Public))
        assertEquals(InscriptionChoice.YOUR_OWN, InscriptionChoice.of(InscriptionSetting.YourOwn("http://x")))
        assertEquals(InscriptionChoice.BUILT_IN, InscriptionChoice.of(InscriptionSetting.BuiltIn))
    }

    @Test
    fun yourOwnUrlMustBeHttp() {
        assertEquals(InscriptionSetting.YourOwn("http://10.0.0.2:8085"), yourOwnSetting("  http://10.0.0.2:8085 "))
        assertEquals(InscriptionSetting.YourOwn("https://ord.example"), yourOwnSetting("https://ord.example"))
        assertNull(yourOwnSetting("ftp://x"))
        assertNull(yourOwnSetting(""))
        assertEquals(InscriptionSetting.YourOwn("http://Host:8085"), yourOwnSetting("HTTP://Host:8085"), "the scheme in any case, stored lowercased")
        assertEquals(InscriptionSetting.YourOwn("https://ord.example"), yourOwnSetting("\n Https://ord.example\t"))
        assertEquals(YourOwnUrl.Refused("The indexer URL cannot hold a user name or password"), checkYourOwnUrl("http://user:pass@host:8085"))
        assertEquals(YourOwnUrl.Refused("The indexer URL must start with http:// or https://"), checkYourOwnUrl("ftp://x"))
        assertEquals(YourOwnUrl.Refused("Not a valid indexer URL"), checkYourOwnUrl("http://a b"))
        assertEquals(YourOwnUrl.Refused("Not a valid indexer URL"), checkYourOwnUrl("http://"))
        assertEquals(YourOwnUrl.Refused("Not a valid indexer URL"), checkYourOwnUrl("http:///path"))
        assertEquals(YourOwnUrl.Ok("http://10.0.0.2:8085"), checkYourOwnUrl(" http://10.0.0.2:8085 "))
    }

    /** A malformed URL never reaches the Send path's Nintondo test as a crash. */
    @Test
    fun runesAwareCheckToleratesAMalformedUrl(): Unit = runBlocking {
        assertFalse(runesAwareIndexer(Coin.LITECOIN, null))
        //Send no longer reads the URL for this: a malformed one never opens a reader, so it is never runes-aware
        val answers = IndexerReaders().answers(InscriptionSetting.YourOwn("http://a b"), Coin.LITECOIN, Network.MAINNET, null,
            listOf(com.sparrowwallet.mobile.electrum.Utxo("11".repeat(32), 0, 100, 1_000)), airGapped = false)
        assertFalse(answers.runesAware)
    }

    @Test
    fun yourOwnIsOpenedOnlyWhenTheUrlOrProxyChanges() = runBlocking {
        val saved = InscriptionSetting.YourOwn("http://10.0.0.2:8085")
        val tor = SocksProxy("127.0.0.1", 9050)
        var opened = 0
        val writes = mutableListOf<InscriptionSetting?>()
        val open: suspend (String) -> InscriptionIndexer = { opened++; FakeIndexer() }
        assertNull(saveInscriptionChoice(saved, tor, saved, tor, airGapped = false, open = open) { writes += it })
        assertEquals(0, opened, "the same URL through the same proxy is not asked again")
        assertEquals(listOf<InscriptionSetting?>(null), writes)
        val changed = InscriptionSetting.YourOwn("http://10.0.0.3:8085")
        assertNull(saveInscriptionChoice(saved, tor, changed, tor, airGapped = false, open = open) { writes += it })
        assertEquals(1, opened)
        assertNull(saveInscriptionChoice(saved, tor, saved, null, airGapped = false, open = open) { writes += it })
        assertEquals(2, opened, "a changed proxy checks again")
        assertEquals(listOf<InscriptionSetting?>(null, changed, saved), writes)
        //Public and Built-in contact nothing
        assertNull(saveInscriptionChoice(saved, tor, InscriptionSetting.BuiltIn, tor, airGapped = false, open = open) { writes += it })
        assertEquals(2, opened)
        assertEquals(InscriptionSetting.BuiltIn, writes.last())
    }

    @Test
    fun refusedYourOwnSavesNothing() = runBlocking {
        var saves = 0
        val refused = saveInscriptionChoice(InscriptionSetting.BuiltIn, null, InscriptionSetting.YourOwn("http://x.example"), null,
            airGapped = false, open = { throw IndexerException(Indexers.UNSUPPORTED) }) { saves++ }
        assertEquals(Indexers.UNSUPPORTED, refused)
        assertEquals(0, saves)
        val gapped = saveInscriptionChoice(InscriptionSetting.BuiltIn, null, InscriptionSetting.YourOwn("http://x.example"), null,
            airGapped = true, open = { error("not asked") }) { saves++ }
        assertEquals("Off in air-gapped mode", gapped)
        assertEquals(0, saves)
    }

    private class FakeIndexer : InscriptionIndexer {
        var closed = 0
        override val runesAware = false
        override suspend fun status() = IndexerStatus("mainnet", false, null)
        override suspend fun outputs(outpoints: List<String>) = emptyMap<String, IndexerOutput>()
        override suspend fun inscription(id: String): IndexerInscription? = null
        override fun close() { closed++ }
    }

    @Test
    fun yourOwnIsOpenedAndClosedBeforeSaving() = runBlocking {
        val reader = FakeIndexer()
        val asked = mutableListOf<String>()
        assertNull(checkYourOwnIndexer("http://10.0.0.2:8085", airGapped = false) { asked += it; reader })
        assertEquals(listOf("http://10.0.0.2:8085"), asked)
        assertEquals(1, reader.closed, "the reader is closed after the check")
    }

    @Test
    fun yourOwnRefusalShowsTheReason() = runBlocking {
        assertEquals(Indexers.UNSUPPORTED, checkYourOwnIndexer("http://x", airGapped = false) { throw IndexerException(Indexers.UNSUPPORTED) })
        assertEquals("Could not reach the indexer",
            checkYourOwnIndexer("http://x", airGapped = false) { throw java.io.IOException() })
    }

    @Test
    fun yourOwnIsNotContactedWhenAirGapped() = runBlocking {
        var calls = 0
        val result = checkYourOwnIndexer("http://10.0.0.2:8085", airGapped = true) { calls++; FakeIndexer() }
        assertEquals("Off in air-gapped mode", result)
        assertEquals(0, calls)
    }

    @Test
    fun cancellationIsNotARefusal() {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runBlocking { checkYourOwnIndexer("http://x", airGapped = false) { throw kotlinx.coroutines.CancellationException("left") } }
        }
    }

    /**
     * Final review M7: with Your own set and only the proxy changed, an indexer that cannot be reached (offline, a
     * timeout) does not block saving the proxy; a refusal (another chain, an unsupported kind, plain http through the
     * proxy) still does, and so does an unreachable indexer for a new URL.
     */
    @Test
    fun aProxyOnlyChangeSavesWhenTheIndexerIsOffline() = runBlocking {
        val saved = InscriptionSetting.YourOwn("https://ord.example")
        val tor = SocksProxy("127.0.0.1", 9050)
        val writes = mutableListOf<InscriptionSetting?>()
        for(offline in listOf(java.io.IOException("connect timed out"), java.net.SocketTimeoutException("read timed out"))) {
            assertNull(saveInscriptionChoice(saved, null, saved, tor, airGapped = false, open = { throw offline }) { writes += it })
        }
        assertEquals(listOf<InscriptionSetting?>(saved, saved), writes)
        val refused = saveInscriptionChoice(saved, null, saved, tor, airGapped = false,
            open = { throw IndexerException("Inscriptions server is for another chain") }) { writes += it }
        assertEquals("Inscriptions server is for another chain", refused)
        val newUrl = saveInscriptionChoice(saved, null, InscriptionSetting.YourOwn("https://other.example"), tor, airGapped = false,
            open = { throw java.io.IOException("connect timed out") }) { writes += it }
        assertEquals("connect timed out", newUrl)
        assertEquals(2, writes.size, "neither refusal saved")
    }
}
