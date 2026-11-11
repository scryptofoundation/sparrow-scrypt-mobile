package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
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
}
