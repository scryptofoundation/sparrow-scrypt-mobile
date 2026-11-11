package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.InscriptionContent
import com.sparrowwallet.mobile.crypto.track.InscriptionTracer
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.indexer.RuneBalance
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CollectiblesModelTest {
    private val txid = "a".repeat(64)
    private val own = "https://example.org"

    private fun found(id: String, number: Long? = null, height: Int = 100, time: Long = 1_000) =
        Found(id, 0, number, "b".repeat(64), height, time)

    private fun row(
        n: Int, verdict: Verdict = Verdict.CLEAN, found: List<Found> = emptyList(), reason: String? = null,
        block: String? = "h", runes: Boolean = false, runeList: Map<String, RuneBalance>? = null, value: Long? = 10_000,
        source: InscriptionCheck.Source = InscriptionCheck.Source.BUILT_IN
    ) = InscriptionCheck(txid, n, verdict, source, runes, found, reason, block, emptyList(), runeList = runeList, value = value)

    private fun map(vararg rows: InscriptionCheck) = rows.associateBy { it.outpoint }

    private fun view(
        checks: Map<String, InscriptionCheck>? = emptyMap(), status: CheckStatus? = null,
        setting: InscriptionSetting = InscriptionSetting.BuiltIn, coin: Coin = Coin.LITECOIN, queued: Set<String> = emptySet()
    ) = CollectiblesModel.build(checks, status, setting, coin, queued)

    @Test fun headerStates() {
        assertEquals("Not checked yet", view(null).header)
        assertEquals("Up to date", view(map(row(0))).header)
        assertEquals("Checked 3 of 40 coins", view(map(row(0)), CheckStatus(3, 40, false, true)).header)
        assertEquals("Checked 3 of 40 coins", view(null, CheckStatus(3, 40, true, true)).header)
        val unreachable = CheckStatus(40, 40, true, false)
        assertEquals("Public indexer unreachable, checking on-chain", view(map(row(0)), unreachable, InscriptionSetting.Public).header)
        assertEquals("Your indexer unreachable, checking on-chain", view(map(row(0)), unreachable, InscriptionSetting.YourOwn(own)).header)
        assertEquals("Up to date", view(map(row(0)), unreachable, InscriptionSetting.BuiltIn).header)
        assertEquals("Up to date", view(map(row(0)), CheckStatus(40, 40, false, false), InscriptionSetting.Public).header)
    }

    @Test fun labels() {
        assertEquals("#1,234,567", CollectiblesModel.label(found("x", number = 1_234_567)))
        assertEquals("#0", CollectiblesModel.label(found("x", number = 0)))
        assertEquals("#999", CollectiblesModel.label(found("x", number = 999)))
        assertEquals("Block 512,340", CollectiblesModel.label(found("x", height = 512_340)))
        assertEquals("Unconfirmed", CollectiblesModel.label(found("x", height = 0)))
        assertEquals("Inscription", CollectiblesModel.label(found("?abc", height = 5)))
    }

    @Test fun labelsIgnoreTheDeviceLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("#1,234,567", CollectiblesModel.label(found("x", number = 1_234_567)))
            assertEquals("Block 512,340", CollectiblesModel.label(found("x", height = 512_340)))
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("Block 512,340", CollectiblesModel.label(found("x", height = 512_340)))
            val runes = view(map(row(0, runeList = mapOf("A" to RuneBalance("1250", 2, null)))), setting = InscriptionSetting.Public).runes!!
            assertEquals("12.50", runes[0].amount)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun tilesAreNewestFirstAndCountedPerCoin() {
        val one = found("${"1".repeat(64)}i0", number = 5, height = 100, time = 10)
        val two = found("${"2".repeat(64)}i0", number = 6, height = 300, time = 30)
        val three = found("${"3".repeat(64)}i0", number = 7, height = 200, time = 20)
        val v = view(map(row(0, Verdict.INSCRIBED, listOf(one, two)), row(1, Verdict.INSCRIBED, listOf(three)), row(2)))
        assertEquals(listOf(two.inscriptionId, three.inscriptionId, one.inscriptionId), v.tiles.map { it.inscriptionId })
        assertEquals(listOf(2, 1, 2), v.tiles.map { it.onCoin })
        val t = v.tiles[0]
        assertEquals("$txid:0", t.outpoint)
        assertEquals("#6", t.label)
        assertEquals("b".repeat(64), t.mintTxid)
        assertEquals(300, t.mintHeight)
        assertEquals(30L, t.mintTime)
        assertEquals(10_000L, t.value)
        assertEquals(InscriptionCheck.Source.BUILT_IN, t.source)
        assertFalse(v.empty)
    }

    @Test fun unconfirmedMintsComeFirstAndTiesAreStable() {
        val a = found("${"a".repeat(64)}i0", height = 100, time = 5)
        val b = found("${"b".repeat(64)}i0", height = 100, time = 5)
        val unconfirmed = found("${"c".repeat(64)}i0", height = 0, time = 0)
        val rows = map(row(0, Verdict.INSCRIBED, listOf(b)), row(1, Verdict.INSCRIBED, listOf(a)), row(2, Verdict.INSCRIBED, listOf(unconfirmed)))
        val v = view(rows)
        assertEquals(listOf(unconfirmed, a, b).map { it.inscriptionId }, v.tiles.map { it.inscriptionId })
        assertEquals("Unconfirmed", v.tiles[0].label)
        assertEquals(v, view(rows.entries.reversed().associate { it.key to it.value }))
    }

    @Test fun unnamedIdIsLabelledInscription() {
        val v = view(map(row(0, Verdict.INSCRIBED, listOf(found("?unnamed", height = 7)))))
        assertEquals("Inscription", v.tiles.single().label)
    }

    @Test fun inscribedRowWithNothingNamedIsNotAnEmptyWallet() {
        val v = view(map(row(0, Verdict.INSCRIBED, emptyList(), reason = null)))
        assertTrue(v.tiles.isEmpty())
        assertFalse(v.empty)
        val u = v.unchecked.single()
        assertEquals("$txid:0", u.outpoint)
        assertFalse(u.canCheckDeeper)
    }

    @Test fun runesAreSummedAcrossCoinsWithDivisibility() {
        val r1 = row(0, runeList = mapOf("UNCOMMON•GOODS" to RuneBalance("1000", 2, "*")))
        val r2 = row(1, runeList = mapOf("UNCOMMON•GOODS" to RuneBalance("250", 2, "*"), "ZED" to RuneBalance("7", 0, null)))
        val v = view(map(r1, r2), setting = InscriptionSetting.Public)
        assertEquals(listOf(RuneRow("UNCOMMON•GOODS", "*", "12.50"), RuneRow("ZED", null, "7")), v.runes)
        assertNull(v.runesNote)
        assertEquals(v.runes, view(map(r1, r2), setting = InscriptionSetting.YourOwn(own)).runes)
    }

    @Test fun runeAmountsBeyondLongAreExact() {
        val big = "340282366920938463463374607431768211455"
        val v = view(
            map(row(0, runeList = mapOf("BIG" to RuneBalance(big, 18, null))), row(1, runeList = mapOf("BIG" to RuneBalance(big, 18, null)))),
            setting = InscriptionSetting.Public
        )
        assertEquals("680564733841876926926.749214863536422910", v.runes!!.single().amount)
    }

    @Test fun smallAmountsKeepLeadingZeros() {
        val v = view(map(row(0, runeList = mapOf("A" to RuneBalance("5", 3, null)))), setting = InscriptionSetting.Public)
        assertEquals("0.005", v.runes!!.single().amount)
    }

    @Test fun sameNameWithOtherDivisibilityDoesNotCrashAndFirstSeenWins() {
        //Rows are taken in outpoint order, so vout 0 is first
        val r1 = row(0, runeList = mapOf("DUP" to RuneBalance("100", 2, "A")))
        val r2 = row(1, runeList = mapOf("DUP" to RuneBalance("100", 0, "B")))
        val r3 = row(2, runeList = mapOf("DUP" to RuneBalance("50", 2, "C")))
        val v = view(map(r3, r2, r1), setting = InscriptionSetting.Public)
        assertEquals(listOf(RuneRow("DUP", "A", "1.50")), v.runes)
    }

    @Test fun unreadableBalancesAreSkipped() {
        val v = view(
            map(row(0, runeList = mapOf("BAD" to RuneBalance("12x", 2, null), "NEG" to RuneBalance("5", -1, null), "HUGE" to RuneBalance("5", 100, null)))),
            setting = InscriptionSetting.Public
        )
        assertEquals(emptyList(), v.runes)
    }

    @Test fun builtInGivesTheNoteAndNoBalances() {
        val v = view(map(row(0, runeList = mapOf("A" to RuneBalance("1", 0, null)))), setting = InscriptionSetting.BuiltIn)
        assertNull(v.runes)
        assertEquals("Rune balances need Public or Your own.", v.runesNote)
    }

    @Test fun noRuneSectionOffLitecoin() {
        for(coin in listOf(Coin.DOGECOIN, Coin.PEPECOIN)) {
            for(setting in listOf(InscriptionSetting.Public, InscriptionSetting.BuiltIn, InscriptionSetting.YourOwn(own))) {
                val v = view(map(row(0, runeList = mapOf("A" to RuneBalance("1", 0, null)))), setting = setting, coin = coin)
                assertNull(v.runes)
                assertNull(v.runesNote)
            }
        }
    }

    @Test fun noRuneListsShowAnEmptyListOnPublic() {
        assertEquals(emptyList(), view(map(row(0)), setting = InscriptionSetting.Public).runes)
    }

    @Test fun runesMarkedRowIsNeitherATileNorUnchecked() {
        val marked = row(0, Verdict.CLEAN, runes = true, block = null, runeList = mapOf("A" to RuneBalance("100", 2, null)))
        val v = view(map(marked), setting = InscriptionSetting.Public)
        assertTrue(v.tiles.isEmpty())
        assertTrue(v.unchecked.isEmpty())
        assertEquals("1.00", v.runes!!.single().amount)
        assertTrue(v.empty)
    }

    @Test fun uncheckedRowsAndCanCheckDeeper() {
        val rows = map(
            row(0, Verdict.UNCHECKED, reason = InscriptionTracer.BUDGET_USED, block = null),
            row(1, Verdict.UNCHECKED, reason = InscriptionTracer.FEES_NEED_DEEPER, block = null),
            row(2, Verdict.UNCHECKED, reason = "Server error: x", block = null),
            row(3, Verdict.UNCHECKED, reason = InscriptionTracer.BUDGET_USED, block = null),
            row(4, Verdict.CLEAN, block = null, value = null),
            row(5, Verdict.CLEAN)
        )
        val v = view(rows, queued = setOf("$txid:3"))
        assertEquals(5, v.unchecked.size)
        val by = v.unchecked.associateBy { it.outpoint }
        assertTrue(by.getValue("$txid:0").canCheckDeeper)
        assertTrue(by.getValue("$txid:1").canCheckDeeper)
        assertFalse(by.getValue("$txid:2").canCheckDeeper)
        assertFalse(by.getValue("$txid:3").canCheckDeeper)
        assertEquals("Waiting for a confirmation", by.getValue("$txid:4").reason)
        assertFalse(by.getValue("$txid:4").canCheckDeeper)
        assertNull(by.getValue("$txid:4").value)
        assertEquals(10_000L, by.getValue("$txid:0").value)
        assertEquals(InscriptionTracer.BUDGET_USED, by.getValue("$txid:0").reason)
        assertFalse("$txid:5" in by)
    }

    @Test fun emptyOnlyWithResultsAndNoTiles() {
        assertFalse(view(null).empty)
        assertTrue(view(emptyMap()).empty)
        assertTrue(view(map(row(0))).empty)
        assertFalse(view(map(row(0, Verdict.INSCRIBED, listOf(found("${"1".repeat(64)}i0"))))).empty)
    }

    @Test fun sameInputsGiveTheSameView() {
        val rows = map(row(0, Verdict.INSCRIBED, listOf(found("${"1".repeat(64)}i0"))), row(1, Verdict.UNCHECKED, reason = "x", block = null))
        assertEquals(view(rows), view(rows))
    }

    //Content loading

    private val content = InscriptionContent("text/plain", null, "hi".encodeToByteArray())

    @Test fun keysSeparateCoinsAndNetworks() {
        val base = ContentLoading.key(Coin.LITECOIN, Network.MAINNET, "id")
        assertNotEquals(base, ContentLoading.key(Coin.LITECOIN, Network.TESTNET, "id"))
        assertNotEquals(base, ContentLoading.key(Coin.DOGECOIN, Network.MAINNET, "id"))
        assertNotEquals(base, ContentLoading.key(Coin.LITECOIN, Network.MAINNET, "id2"))
    }

    @Test fun contentIsCached() = runBlocking {
        val cache = ContentCache(64)
        var calls = 0
        val load: suspend () -> InscriptionContent? = { calls++; content }
        assertSame(content, ContentLoading.load(cache, "k", false, load))
        assertSame(content, ContentLoading.load(cache, "k", false, load))
        assertEquals(1, calls)
        ContentLoading.load(cache, "other", false, load)
        assertEquals(2, calls)
    }

    @Test fun failuresAreNullAndNotCached() = runBlocking {
        val cache = ContentCache(64)
        var calls = 0
        assertNull(ContentLoading.load(cache, "k", false) { calls++; throw IllegalStateException("server") })
        assertNull(ContentLoading.load(cache, "k", false) { calls++; null })
        assertEquals(2, calls)
        assertNull(cache.get("k"))
        assertSame(content, ContentLoading.load(cache, "k", false) { calls++; content })
        assertEquals(3, calls)
    }

    @Test fun airGappedMakesNoCallAndReturnsNothingEvenCached() = runBlocking {
        val cache = ContentCache(64)
        cache.put("k", content)
        var calls = 0
        assertNull(ContentLoading.load(cache, "k", true) { calls++; content })
        assertEquals(0, calls)
    }

    @Test fun cancellationPropagates() {
        val cache = ContentCache(64)
        assertFailsWith<CancellationException> {
            runBlocking { ContentLoading.load(cache, "k", false) { throw CancellationException("stop") } }
        }
        assertNull(cache.get("k"))
    }

    @Test fun cacheKeepsTheNewestSixtyFour() {
        val cache = ContentCache(64)
        for(i in 0 until 64) cache.put("k$i", content)
        cache.get("k0")
        cache.put("k64", content)
        assertNotNull(cache.get("k0"))
        assertNull(cache.get("k1"))
        assertNotNull(cache.get("k64"))
        assertEquals(64, cache.size())
    }
}
