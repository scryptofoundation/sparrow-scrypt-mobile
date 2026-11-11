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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        setting: InscriptionSetting = InscriptionSetting.BuiltIn, coin: Coin = Coin.LITECOIN, queued: Set<String> = emptySet(),
        airGapped: Boolean = false
    ) = CollectiblesModel.build(listOf(WalletChecks(Layer.PUBLIC, checks, status, queued, "P2WPKH")), setting, coin, airGapped)

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

    //Held coins, reasons, duplicates, unreadable balances

    private fun marked(n: Int, runeList: Map<String, RuneBalance>? = null, verdict: Verdict = Verdict.CLEAN) =
        row(n, verdict, runes = true, runeList = runeList)

    private fun heldOf(v: CollectiblesView) = v.unchecked.filter { it.reason == CollectiblesModel.HELD }

    @Test fun heldCoinWithNoRuneListShowsOnLitecoinPublicAndYourOwn() {
        for(setting in listOf(InscriptionSetting.Public, InscriptionSetting.YourOwn(own))) {
            for(list in listOf<Map<String, RuneBalance>?>(null, emptyMap())) {
                val v = view(map(marked(0, list)), setting = setting)
                assertEquals(listOf("$txid:0"), heldOf(v).map { it.outpoint })
                assertFalse(heldOf(v).single().canCheckDeeper)
                assertFalse(v.empty)
            }
        }
    }

    @Test fun heldCoinWithAListShowsOnBuiltInAndOffLitecoin() {
        val list = mapOf("A" to RuneBalance("100", 2, null))
        assertEquals(1, heldOf(view(map(marked(0, list)), setting = InscriptionSetting.BuiltIn)).size)
        for(coin in listOf(Coin.DOGECOIN, Coin.BELLSCOIN)) {
            for(setting in listOf(InscriptionSetting.Public, InscriptionSetting.BuiltIn)) {
                val v = view(map(marked(0, list), marked(1)), setting = setting, coin = coin)
                assertEquals(2, heldOf(v).size)
                assertFalse(v.empty)
            }
        }
    }

    @Test fun heldCoinWhoseEntriesWereAllSkippedShows() {
        val v = view(map(marked(0, mapOf("BAD" to RuneBalance("1x", 0, null)))), setting = InscriptionSetting.Public)
        assertEquals(1, heldOf(v).size)
        assertFalse(v.empty)
    }

    @Test fun heldCoinWithABalanceRowIsNotListedTwice() {
        val v = view(map(marked(0, mapOf("A" to RuneBalance("100", 2, null)))), setting = InscriptionSetting.Public)
        assertTrue(v.unchecked.isEmpty())
        assertEquals(1, v.runes!!.size)
        assertTrue(v.empty)
    }

    @Test fun inscribedAndMarkedShowsAsATileOnly() {
        val f = found("${"1".repeat(64)}i0", number = 3)
        val v = view(
            map(row(0, Verdict.INSCRIBED, listOf(f), runes = true)), setting = InscriptionSetting.Public
        )
        assertEquals(1, v.tiles.size)
        assertTrue(v.unchecked.isEmpty())
        assertFalse(v.empty)
    }

    /** A coin Send holds by an indexer's mark: a deeper trace cannot free it, so it says it is held and offers no Check deeper. */
    @Test fun uncheckedAndHeldShowsHeldWithNoCheckDeeper() {
        for(reason in listOf("Server error: x", InscriptionTracer.BUDGET_USED, InscriptionTracer.FEES_NEED_DEEPER)) {
            for(setting in listOf(InscriptionSetting.Public, InscriptionSetting.BuiltIn)) {
                val v = view(map(marked(0, null, Verdict.UNCHECKED).copy(reason = reason)), setting = setting)
                val row = v.unchecked.single()
                assertEquals(CollectiblesModel.HELD, row.reason, reason)
                assertFalse(row.canCheckDeeper, reason)
                assertEquals(DeeperButton.NONE, CollectiblesModel.deeperButton(row, airGapped = false))
            }
        }
    }

    @Test fun airGappedSaysChecksAreOff() {
        for(status in listOf(null, CheckStatus(3, 40, false, true), CheckStatus(40, 40, true, false))) {
            assertEquals("Checks are off while air-gapped", view(map(row(0)), status, InscriptionSetting.Public, airGapped = true).header)
            assertEquals("Checks are off while air-gapped", view(null, status, airGapped = true).header)
        }
        assertEquals(CollectiblesModel.CHECKS_OFF, view(map(row(0)), airGapped = true).header)
    }

    @Test fun emptyIsFalseWheneverAnythingIsListed() {
        assertFalse(view(map(row(0, Verdict.UNCHECKED, reason = "x", block = null))).empty)
        assertFalse(view(map(row(0, block = null))).empty)
        assertFalse(view(map(row(0, Verdict.INSCRIBED, emptyList()))).empty)
        assertTrue(view(map(row(0), row(1))).empty)
    }

    @Test fun reasonsAreSingleLineSafeAndShortButDeeperStillWorks() {
        val hostile = "Server error\nline two\u202eevil\u200b " + "x".repeat(500)
        val v = view(map(row(0, Verdict.UNCHECKED, reason = hostile, block = null)))
        val shown = v.unchecked.single().reason
        assertFalse(shown.any { it == '\n' || it == '\u202e' || it == '\u200b' })
        assertTrue(shown.startsWith("Server error line twoevil"))
        assertTrue(shown.length <= 120)
        assertFalse(v.unchecked.single().canCheckDeeper)
        val budget = view(map(row(0, Verdict.UNCHECKED, reason = InscriptionTracer.BUDGET_USED, block = null)))
        assertTrue(budget.unchecked.single().canCheckDeeper)
        assertEquals(InscriptionTracer.BUDGET_USED, budget.unchecked.single().reason)
    }

    @Test fun overlongRuneAmountsAreSkipped() {
        val v = view(
            map(row(0, runeList = mapOf("OK" to RuneBalance("1", 0, null), "LONG" to RuneBalance("1".repeat(41), 0, null)))),
            setting = InscriptionSetting.Public
        )
        assertEquals(listOf("OK"), v.runes!!.map { it.name })
    }

    @Test fun twoUnnamedInscriptionsOnOneCoinAreBothListedAndCounted() {
        val a = found("?i0", height = 5)
        val v = view(map(row(0, Verdict.INSCRIBED, listOf(a, a)), row(1, Verdict.INSCRIBED, listOf(a))))
        assertEquals(3, v.tiles.size)
        assertEquals(listOf(2, 2, 1), v.tiles.map { it.onCoin })
        assertEquals(3, v.tiles.map { it.key }.toSet().size)
    }

    @Test fun skippedRuneEntriesAreSaid() {
        val bad = row(0, runeList = mapOf("BAD" to RuneBalance("1x", 2, null)))
        assertEquals(CollectiblesModel.RUNES_UNREADABLE, view(map(bad), setting = InscriptionSetting.Public).runesNote)
        val mixed = map(row(0, runeList = mapOf("D" to RuneBalance("1", 2, null))), row(1, runeList = mapOf("D" to RuneBalance("1", 0, null))))
        assertEquals(CollectiblesModel.RUNES_UNREADABLE, view(mixed, setting = InscriptionSetting.Public).runesNote)
        assertEquals(CollectiblesModel.RUNES_NOTE, view(map(bad), setting = InscriptionSetting.BuiltIn).runesNote)
        assertNull(view(map(row(0, runeList = mapOf("A" to RuneBalance("1", 0, null)))), setting = InscriptionSetting.Public).runesNote)
    }

    // ---- the screen's decisions ----

    @Test fun collectiblesButtonOnlyForPublicWalletsOnCoveredCoins() {
        for(coin in listOf(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN)) {
            assertTrue(CollectiblesModel.available(coin, "P2WPKH"), coin.name)
            assertTrue(CollectiblesModel.available(coin, "P2PKH"), coin.name)
        }
        assertFalse(CollectiblesModel.available(Coin.LITECOIN, "MWEB"))
        assertFalse(CollectiblesModel.available(Coin.LUCKYCOIN, "P2PKH"))
        assertFalse(CollectiblesModel.available(Coin.DINGOCOIN, "P2PKH"))
    }

    @Test fun queuedRowsSayQueuedAndOthersDoNot() {
        val rows = map(
            row(0, Verdict.UNCHECKED, reason = InscriptionTracer.BUDGET_USED, block = null),
            row(1, Verdict.UNCHECKED, reason = InscriptionTracer.FEES_NEED_DEEPER, block = null),
            row(2, Verdict.CLEAN, runes = true, block = null)
        )
        val v = view(rows, setting = InscriptionSetting.Public, queued = setOf("$txid:0", "$txid:2"))
        val by = v.unchecked.associateBy { it.outpoint }
        assertTrue(by.getValue("$txid:0").queued)
        assertFalse(by.getValue("$txid:0").canCheckDeeper)
        assertFalse(by.getValue("$txid:1").queued)
        assertTrue(by.getValue("$txid:1").canCheckDeeper)
        //A held row never offers Check deeper, queued or not
        assertEquals(CollectiblesModel.HELD, by.getValue("$txid:2").reason)
        assertFalse(by.getValue("$txid:2").queued)
        assertFalse(by.getValue("$txid:2").canCheckDeeper)
    }

    @Test fun deeperButtonStates() {
        val row = UncheckedRow("o:0", 1, InscriptionTracer.BUDGET_USED, canCheckDeeper = true)
        assertEquals(DeeperButton.CHECK, CollectiblesModel.deeperButton(row, airGapped = false))
        assertEquals(DeeperButton.OFF, CollectiblesModel.deeperButton(row, airGapped = true))
        assertEquals(DeeperButton.QUEUED, CollectiblesModel.deeperButton(row.copy(canCheckDeeper = false, queued = true), airGapped = false))
        assertEquals(DeeperButton.NONE, CollectiblesModel.deeperButton(row.copy(canCheckDeeper = false), airGapped = false))
        assertEquals(DeeperButton.NONE, CollectiblesModel.deeperButton(UncheckedRow("o:1", 1, CollectiblesModel.HELD, false), airGapped = false))
        assertEquals("Check deeper", CollectiblesModel.CHECK_DEEPER)
        assertEquals("Queued", CollectiblesModel.QUEUED)
    }

    private fun tile(id: String = "${"1".repeat(64)}i0", label: String = "#1,234", onCoin: Int = 1, value: Long? = 150_000_000L,
                     height: Int = 512_340, time: Long = 1_700_000_000L, chain: Int? = 0, index: Int? = 4) =
        CollectibleTile(id, "$txid:1", label, "b".repeat(64), height, time, onCoin, value, InscriptionCheck.Source.PUBLIC,
            chain = chain, index = index)

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 0, 0, 0, 64, 0, 0, 0, 32)

    @Test fun previewDecisions() {
        val t = tile()
        assertEquals(InscriptionPreview.Shown.Unavailable(InscriptionPreview.UNAVAILABLE),
            InscriptionPreview.shown(tile(id = "?unnamed"), airGapped = false, failed = false, content = null))
        assertEquals(InscriptionPreview.Shown.Unavailable(InscriptionPreview.AIR_GAPPED),
            InscriptionPreview.shown(t, airGapped = true, failed = false, content = InscriptionContent("text/plain", null, "hi".encodeToByteArray())))
        assertEquals(InscriptionPreview.Shown.Unavailable("Preview unavailable"), InscriptionPreview.shown(t, false, failed = true, content = null))
        assertEquals(InscriptionPreview.Shown.Loading, InscriptionPreview.shown(t, false, false, null))
        val image = assertIs<InscriptionPreview.Shown.Image>(InscriptionPreview.shown(t, false, false, InscriptionContent("image/png", null, png)))
        assertEquals(64 to 32, image.width to image.height)
        assertEquals(InscriptionPreview.Shown.Text("hi\tthere"),
            InscriptionPreview.shown(t, false, false, InscriptionContent("text/plain;charset=utf-8", null, "hi\tthere\u202e".encodeToByteArray())))
        assertEquals(InscriptionPreview.Shown.Placeholder("text/html", "1,500 bytes"),
            InscriptionPreview.shown(t, false, false, InscriptionContent("text/html", null, ByteArray(1_500))))
        assertEquals(InscriptionPreview.Shown.Placeholder("image/png", "24 bytes"),
            InscriptionPreview.shown(t, false, false, InscriptionContent("image/png", "br", png)), "encoded: never decoded")
        assertEquals(InscriptionPreview.Shown.Placeholder("Unknown type", "0 bytes"),
            InscriptionPreview.shown(t, false, false, InscriptionContent(null, null, ByteArray(0))))
    }

    /** Content over the limit (its body never assembled) shows its type and size with "Preview unavailable". */
    @Test fun oversizedContentShowsItsTypeAndSize() {
        val t = tile()
        val big = InscriptionContent("image/png", null, ByteArray(0), size = 512_000, complete = false)
        assertEquals(InscriptionPreview.Shown.Placeholder("image/png", "512,000 bytes", "Preview unavailable"),
            InscriptionPreview.shown(t, false, false, big))
        assertEquals("image/png, 512,000 bytes", InscriptionPreview.contentLine(big))
        val unknown = InscriptionContent(null, null, ByteArray(0), size = 400_000, complete = false, sizeOver = true)
        assertEquals(InscriptionPreview.Shown.Placeholder("Unknown type", "Over 400,000 bytes", "Preview unavailable"),
            InscriptionPreview.shown(t, false, false, unknown))
        //A complete placeholder has no note
        assertNull((InscriptionPreview.shown(t, false, false, InscriptionContent("text/html", null, ByteArray(3)))
            as InscriptionPreview.Shown.Placeholder).note)
    }

    @Test fun previewsDecodeOneAtATime(): Unit = runBlocking {
        val decodes = OneAtATime()
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        (1..6).map {
            async(kotlinx.coroutines.Dispatchers.Default) {
                decodes.run {
                    most.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                    Thread.sleep(20)
                    inside.decrementAndGet()
                }
            }
        }.forEach { it.await() }
        assertEquals(1, most.get())
    }

    /** A tile that leaves while its decode waits for its turn never decodes. */
    @Test fun aDecodeWhoseTileLeftNeverStarts(): Unit = runBlocking {
        val decodes = OneAtATime()
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = async(kotlinx.coroutines.Dispatchers.Default) { decodes.run { holding.countDown(); release.await() } }
        holding.await()
        val ran = java.util.concurrent.atomic.AtomicBoolean()
        val second = launch(kotlinx.coroutines.Dispatchers.Default) { decodes.run { ran.set(true) } }
        kotlinx.coroutines.delay(50)
        second.cancel()
        second.join()
        release.countDown()
        first.await()
        assertFalse(ran.get())
        //and a caller already cancelled when its turn comes does not decode either
        val gone = launch(kotlinx.coroutines.Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) { decodes.run { ran.set(true) } }
        gone.cancel()
        gone.join()
        assertFalse(ran.get())
    }

    @Test fun aCancelledLoadReleasesItsPermit(): Unit = runBlocking {
        val permits = kotlinx.coroutines.sync.Semaphore(1)
        val cache = ContentCache(64)
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stuck = launch(kotlinx.coroutines.Dispatchers.Default) {
            ContentLoading.load(cache, "a", airGapped = false, permits = permits) { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        }
        started.await()
        assertEquals(0, permits.availablePermits)
        stuck.cancel()
        stuck.join()
        assertEquals(1, permits.availablePermits)
        val next = kotlinx.coroutines.withTimeout(5_000) {
            ContentLoading.load(cache, "b", airGapped = false, permits = permits) { InscriptionContent("text/plain", null, "x".encodeToByteArray()) }
        }
        assertNotNull(next)
    }

    @Test fun aHostileContentTypeIsShownSafelyAndShort() {
        val shown = InscriptionPreview.typeText("text/x\n\u202eevil\u200b" + "y".repeat(500))
        assertFalse(shown.any { it == '\n' || it == '\u202e' || it == '\u200b' })
        assertTrue(shown.startsWith("text/x evil"))
        assertTrue(shown.length <= InscriptionPreview.MAX_TYPE_CHARS)
    }

    @Test fun loadsOnlyWhatMayBeShownAndNeverRetriesAFailureInAVisit() {
        val failures = PreviewFailures()
        assertTrue(InscriptionPreview.shouldLoad(tile(), airGapped = false, failures))
        assertFalse(InscriptionPreview.shouldLoad(tile(id = "?x"), airGapped = false, failures))
        assertFalse(InscriptionPreview.shouldLoad(tile(), airGapped = true, failures))
        failures.add(tile().key)
        assertTrue(tile().key in failures)
        assertFalse(InscriptionPreview.shouldLoad(tile(), airGapped = false, failures))
    }

    @Test fun decodedBoundsMustMatchTheHeaderAndTheLimits() {
        assertTrue(InscriptionPreview.decodedBoundsOk(64, 32, 64, 32))
        assertFalse(InscriptionPreview.decodedBoundsOk(64, 32, 65, 32), "larger than the header said")
        assertFalse(InscriptionPreview.decodedBoundsOk(64, 32, 64, 33))
        assertFalse(InscriptionPreview.decodedBoundsOk(64, 32, -1, -1), "the decoder could not read it")
        assertFalse(InscriptionPreview.decodedBoundsOk(64, 32, 0, 32))
        assertFalse(InscriptionPreview.decodedBoundsOk(5_000, 10, 5_000, 10), "over the side limit")
        assertFalse(InscriptionPreview.decodedBoundsOk(3_000, 3_000, 3_000, 3_000), "over the pixel limit")
        assertTrue(InscriptionPreview.decodedBoundsOk(64, 32, 32, 16), "smaller is accepted")
        assertEquals(4, PreviewRules.sampleSize(1_024, 512, InscriptionPreview.THUMB_TARGET))
    }

    @Test fun detailLines() {
        val lines = InscriptionDetail.lines(tile(), Coin.LITECOIN, content = "image/png, 24 bytes", address = "ltc1qaddress")
        val by = lines.associate { it.label to it }
        assertEquals("${"1".repeat(64)}i0", by.getValue("Inscription id").value)
        assertTrue(by.getValue("Inscription id").copy)
        assertEquals("#1,234", by.getValue("Number").value)
        assertTrue(by.getValue("Minted").value.startsWith("Block 512,340, "))
        assertEquals("image/png, 24 bytes", by.getValue("Content").value)
        assertEquals("$txid:1", by.getValue("Coin").value)
        assertTrue(by.getValue("Coin").copy)
        assertEquals("1.50000000 LTC", by.getValue("Value").value)
        assertEquals("ltc1qaddress", by.getValue("Address").value)
        assertEquals("Public indexer", by.getValue("Source").value)
        assertEquals(listOf("Inscription id", "Coin"), lines.filter { it.copy }.map { it.label }, "only the id and the outpoint are copied")
        //Unknown parts are left out, not guessed
        val bare = InscriptionDetail.lines(tile(label = "Block 512,340", value = null, chain = null, index = null), Coin.LITECOIN, null, null)
            .map { it.label }
        assertFalse("Number" in bare || "Value" in bare || "Address" in bare || "Content" in bare)
        assertEquals("Unconfirmed", InscriptionDetail.lines(tile(label = "Unconfirmed", height = 0, time = 0), Coin.LITECOIN, null, null)
            .single { it.label == "Minted" }.value)
        //Named as the setting names them; the wallet's own trace is the built-in one
        assertEquals(listOf("Built-in", "Built-in", "Your own indexer", "Public indexer"),
            listOf(InscriptionCheck.Source.BUILT_IN, InscriptionCheck.Source.OWN, InscriptionCheck.Source.YOUR_OWN, InscriptionCheck.Source.PUBLIC)
                .map { InscriptionDetail.source(it) })
    }

    @Test fun onlyAReceiveOrChangePositionGivesAnAddress() {
        assertEquals(0 to 4, InscriptionDetail.position(tile()))
        assertEquals(1 to 0, InscriptionDetail.position(tile(chain = 1, index = 0)))
        assertNull(InscriptionDetail.position(tile(chain = null)))
        assertNull(InscriptionDetail.position(tile(index = null)))
        assertNull(InscriptionDetail.position(tile(chain = 2)))
        assertNull(InscriptionDetail.position(tile(index = -1)))
    }

    @Test fun aCoinWithSeveralInscriptionsSaysSo() {
        assertNull(InscriptionDetail.carries(tile(onCoin = 1)))
        assertEquals("This coin carries 3 inscriptions.", InscriptionDetail.carries(tile(onCoin = 3)))
    }

    @Test fun tilesCarryTheCoinsAddressPosition() {
        val r = row(0, Verdict.INSCRIBED, listOf(found("${"1".repeat(64)}i0"))).copy(chain = 1, index = 9)
        val t = view(map(r)).tiles.single()
        assertEquals(1 to 9, t.chain to t.index)
    }

    @Test fun concurrentCallersShareOneLoad() = runBlocking {
        val cache = ContentCache(64)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val load: suspend () -> InscriptionContent? = { calls++; gate.await(); content }
        val a = async { ContentLoading.load(cache, "k", false, load) }
        val b = async { ContentLoading.load(cache, "k", false, load) }
        kotlinx.coroutines.yield()
        gate.complete(Unit)
        assertSame(content, a.await())
        assertSame(content, b.await())
        assertEquals(1, calls)
    }

    @Test fun aCancelledSharedLoadDoesNotPoisonTheWaiter() = runBlocking {
        val cache = ContentCache(64)
        var calls = 0
        val never = kotlinx.coroutines.CompletableDeferred<Unit>()
        val load: suspend () -> InscriptionContent? = {
            if(calls++ == 0) never.await()
            content
        }
        val a = async { ContentLoading.load(cache, "k", false, load) }
        kotlinx.coroutines.yield()
        val b = async { ContentLoading.load(cache, "k", false, load) }
        kotlinx.coroutines.yield()
        a.cancel()
        assertSame(content, b.await())
        assertEquals(2, calls)
        assertSame(content, ContentLoading.load(cache, "k", false) { null })
    }

    @Test fun aFailedSharedLoadDoesNotPoisonLaterCallers() = runBlocking {
        val cache = ContentCache(64)
        assertNull(ContentLoading.load(cache, "k", false) { throw IllegalStateException("x") })
        assertSame(content, ContentLoading.load(cache, "k", false) { content })
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
        assertEquals(64L * content.body.size, cache.bytes())
    }

    /** The cache also holds at most 8 MiB of bodies, oldest out first; a body over the bound is not cached. */
    @Test fun cacheIsBoundedByBytes() {
        assertEquals(8L * 1024 * 1024, ContentCache.MAX_BYTES)
        val big = InscriptionContent("image/png", null, ByteArray(400_000))
        val cache = ContentCache(64)
        for(i in 0 until 64) cache.put("k$i", big)
        assertEquals(20, cache.size(), "20 bodies of 400,000 bytes fit in 8 MiB")
        assertTrue(cache.bytes() <= ContentCache.MAX_BYTES)
        assertNull(cache.get("k43"))
        assertNotNull(cache.get("k44"))
        assertNotNull(cache.get("k63"))

        val small = ContentCache(64, maxBytes = 1_000)
        val part = InscriptionContent("text/plain", null, ByteArray(400))
        small.put("a", part)
        small.put("b", part)
        small.get("a")
        small.put("c", part)
        assertNotNull(small.get("a"), "recently used stays")
        assertNull(small.get("b"), "oldest out first")
        assertEquals(800L, small.bytes())
        small.put("huge", InscriptionContent("text/plain", null, ByteArray(1_001)))
        assertNull(small.get("huge"), "over the bound: not cached")
        assertEquals(2, small.size())
        //replacing an entry counts its new size only
        small.put("a", InscriptionContent("text/plain", null, ByteArray(100)))
        assertEquals(500L, small.bytes())
    }

    /**
     * A coin whose earlier results were lost says so. Check deeper is offered only where a trace can resolve it (Bellscoin,
     * no rune-like tokens); on Litecoin, Dogecoin, Pepecoin and Junkcoin only an indexer answer can.
     */
    @Test fun aCoinWhoseResultsWereLostCanBeCheckedDeeperOnlyWhereATraceIsEnough() {
        val rows = map(row(0, Verdict.UNCHECKED, reason = InscriptionChecker.LOST_REASON, block = null))
        assertTrue(view(rows, coin = Coin.BELLSCOIN).unchecked.single().canCheckDeeper)
        for(coin in listOf(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.JUNKCOIN)) {
            assertFalse(view(rows, coin = coin).unchecked.single().canCheckDeeper, coin.toString())
        }
    }

    // ---- a wallet and its Taproot wallet on one screen ----

    private fun checks(layer: Layer, checks: Map<String, InscriptionCheck>? = emptyMap(), status: CheckStatus? = null,
                       queued: Set<String> = emptySet()) =
        WalletChecks(layer, checks, status, queued, if(layer == Layer.TAPROOT) "P2TR" else "P2WPKH")

    private fun both(
        public: Map<String, InscriptionCheck>? = emptyMap(), taproot: Map<String, InscriptionCheck>? = emptyMap(),
        publicStatus: CheckStatus? = null, taprootStatus: CheckStatus? = null, setting: InscriptionSetting = InscriptionSetting.BuiltIn,
        coin: Coin = Coin.LITECOIN, publicQueued: Set<String> = emptySet(), taprootQueued: Set<String> = emptySet(),
        airGapped: Boolean = false
    ) = CollectiblesModel.build(
        listOf(checks(Layer.PUBLIC, public, publicStatus, publicQueued), checks(Layer.TAPROOT, taproot, taprootStatus, taprootQueued)),
        setting, coin, airGapped
    )

    /** One wallet gives exactly the view it gave before Taproot wallets: nothing tagged, every item on its own layer. */
    @Test fun oneWalletGivesTodaysView() {
        val rows = map(
            row(0, Verdict.INSCRIBED, listOf(found("${"1".repeat(64)}i0", number = 5), found("${"2".repeat(64)}i0"))),
            row(1, Verdict.UNCHECKED, reason = InscriptionTracer.BUDGET_USED, block = null),
            row(2, runes = true, runeList = mapOf("A" to RuneBalance("100", 2, null))),
            row(3, block = null)
        )
        val v = view(rows, CheckStatus(4, 4, false, false), InscriptionSetting.Public, queued = setOf("$txid:1"))
        assertEquals(2, v.tiles.size)
        assertTrue(v.tiles.all { it.tag == null && it.layer == Layer.PUBLIC })
        assertEquals(2, v.unchecked.size)
        assertTrue(v.unchecked.all { it.tag == null && it.layer == Layer.PUBLIC })
        assertTrue(v.unchecked.single { it.outpoint == "$txid:1" }.queued)
        assertEquals(listOf(RuneRow("A", null, "1.00")), v.runes)
        assertEquals("Up to date", v.header)
        assertFalse(v.empty)
        //A Taproot wallet shown alone is one wallet too: its items keep their layer for routing, untagged
        val alone = CollectiblesModel.build(listOf(checks(Layer.TAPROOT, rows, CheckStatus(4, 4, false, false), setOf("$txid:1"))),
            InscriptionSetting.Public, Coin.LITECOIN)
        assertTrue(alone.tiles.all { it.tag == null && it.layer == Layer.TAPROOT })
        assertTrue(alone.unchecked.all { it.tag == null && it.layer == Layer.TAPROOT })
        assertEquals(v.tiles.map { it.inscriptionId }, alone.tiles.map { it.inscriptionId })
        assertEquals(v.unchecked.map { it.copy(layer = Layer.TAPROOT) }, alone.unchecked)
        assertEquals(v.runes, alone.runes)
        assertEquals(v.header, alone.header)
    }

    @Test fun tilesFromTheCompanionAreTagged() {
        val mine = found("${"1".repeat(64)}i0", number = 1, height = 300)
        val its = found("${"2".repeat(64)}i0", number = 2, height = 200)
        val v = both(map(row(0, Verdict.INSCRIBED, listOf(mine))), map(row(1, Verdict.INSCRIBED, listOf(its))))
        assertEquals(listOf(mine.inscriptionId, its.inscriptionId), v.tiles.map { it.inscriptionId })
        val (public, taproot) = v.tiles
        assertEquals(Layer.PUBLIC, public.layer)
        assertNull(public.tag)
        assertEquals(Layer.TAPROOT, taproot.layer)
        assertEquals("Taproot", taproot.tag)
        assertEquals(CollectiblesModel.TAPROOT_TAG, Layer.TAPROOT.label)
    }

    @Test fun sameInscriptionIdInBothWalletsKeepsTwoTiles() {
        val f = found("${"1".repeat(64)}i0", number = 1)
        val v = both(map(row(0, Verdict.INSCRIBED, listOf(f))), map(row(0, Verdict.INSCRIBED, listOf(f))))
        assertEquals(2, v.tiles.size)
        assertEquals(2, v.tiles.map { it.key }.toSet().size)
        assertEquals(listOf(Layer.PUBLIC, Layer.TAPROOT), v.tiles.map { it.layer })
        assertTrue(v.tiles.all { Layer.PUBLIC.name in it.key || Layer.TAPROOT.name in it.key })
    }

    @Test fun runesAreSummedAcrossBothWallets() {
        val p = map(row(0, runeList = mapOf("UNCOMMON•GOODS" to RuneBalance("1000", 2, "*"))))
        val t = map(row(1, runeList = mapOf("UNCOMMON•GOODS" to RuneBalance("250", 2, "*"), "ZED" to RuneBalance("7", 0, null))))
        val v = both(p, t, setting = InscriptionSetting.Public)
        assertEquals(listOf(RuneRow("UNCOMMON•GOODS", "*", "12.50"), RuneRow("ZED", null, "7")), v.runes)
        assertNull(v.runesNote)
        //The Built-in note and the unreadable note behave as for one wallet
        assertNull(both(p, t).runes)
        assertEquals(CollectiblesModel.RUNES_NOTE, both(p, t).runesNote)
        val bad = map(row(2, runeList = mapOf("BAD" to RuneBalance("1x", 0, null))))
        assertEquals(CollectiblesModel.RUNES_UNREADABLE, both(p, bad, setting = InscriptionSetting.Public).runesNote)
        //Litecoin only
        assertNull(both(p, t, setting = InscriptionSetting.Public, coin = Coin.BELLSCOIN).runes)
        //First seen across wallets is by outpoint, then the public wallet before the Taproot one
        val dupP = map(row(5, runeList = mapOf("DUP" to RuneBalance("100", 2, "P"))))
        val dupT = map(row(5, runeList = mapOf("DUP" to RuneBalance("100", 0, "T"))))
        assertEquals(listOf(RuneRow("DUP", "P", "1.00")), both(dupP, dupT, setting = InscriptionSetting.Public).runes)
        //A balance in one wallet never stands for a held coin of the other
        val held = map(row(0, runes = true))
        val heldRows = both(p, held, setting = InscriptionSetting.Public).unchecked
        assertEquals(listOf(Layer.TAPROOT), heldRows.filter { it.reason == CollectiblesModel.HELD }.map { it.layer })
    }

    @Test fun headerCombinesBothWallets() {
        val one = map(row(0))
        assertEquals("Not checked yet", both(null, null).header)
        assertEquals("Up to date", both(one, null).header)
        assertEquals("Up to date", both(null, one).header)
        assertEquals("Up to date", both(one, one).header)
        //Running if either runs, both wallets' counts summed
        assertEquals("Checked 3 of 50 coins", both(one, one, CheckStatus(3, 40, false, true), CheckStatus(0, 10, false, true)).header)
        assertEquals("Checked 13 of 50 coins", both(one, one, CheckStatus(3, 40, false, true), CheckStatus(10, 10, false, false)).header)
        assertEquals("Checked 0 of 10 coins", both(null, null, null, CheckStatus(0, 10, false, true)).header)
        //Unreachable if either wallet's last run was
        val unreachable = CheckStatus(10, 10, true, false)
        val fine = CheckStatus(40, 40, false, false)
        assertEquals("Public indexer unreachable, checking on-chain",
            both(one, one, fine, unreachable, InscriptionSetting.Public).header)
        assertEquals("Your indexer unreachable, checking on-chain",
            both(one, one, unreachable, fine, InscriptionSetting.YourOwn(own)).header)
        assertEquals("Up to date", both(one, one, unreachable, unreachable, InscriptionSetting.BuiltIn).header)
        assertEquals("Up to date", both(one, one, fine, fine, InscriptionSetting.Public).header)
        //Air-gapped as for one wallet
        assertEquals(CollectiblesModel.CHECKS_OFF, both(one, one, CheckStatus(3, 40, false, true), unreachable, airGapped = true).header)
    }

    @Test fun emptyNeedsBothEmpty() {
        assertFalse(both(null, null).empty)
        assertTrue(both(emptyMap(), emptyMap()).empty)
        assertTrue(both(map(row(0)), null).empty)
        assertTrue(both(map(row(0)), map(row(1))).empty)
        val tile = map(row(0, Verdict.INSCRIBED, listOf(found("${"1".repeat(64)}i0"))))
        val unchecked = map(row(0, Verdict.UNCHECKED, reason = "x", block = null))
        assertFalse(both(map(row(0)), tile).empty)
        assertFalse(both(tile, map(row(0))).empty)
        assertFalse(both(emptyMap(), unchecked).empty)
        assertFalse(both(unchecked, null).empty)
    }

    @Test fun uncheckedRowsNameTheirWallet() {
        val p = map(row(0, Verdict.UNCHECKED, reason = InscriptionTracer.BUDGET_USED, block = null))
        val t = map(row(0, Verdict.UNCHECKED, reason = InscriptionTracer.BUDGET_USED, block = null), row(1, block = null))
        //Queued on the public wallet: the Taproot wallet's row for the same outpoint is not
        val v = both(p, t, publicQueued = setOf("$txid:0"))
        assertEquals(3, v.unchecked.size)
        val public = v.unchecked.single { it.layer == Layer.PUBLIC }
        assertNull(public.tag)
        assertTrue(public.queued)
        val taproot = v.unchecked.filter { it.layer == Layer.TAPROOT }
        assertEquals(listOf("Taproot", "Taproot"), taproot.map { it.tag })
        assertFalse(taproot.first { it.outpoint == "$txid:0" }.queued)
        assertTrue(taproot.first { it.outpoint == "$txid:0" }.canCheckDeeper)
        //and the other way round
        val w = both(p, t, taprootQueued = setOf("$txid:0"))
        assertFalse(w.unchecked.single { it.layer == Layer.PUBLIC }.queued)
        assertTrue(w.unchecked.first { it.layer == Layer.TAPROOT && it.outpoint == "$txid:0" }.queued)
    }

    /** An MWEB wallet never adds anything to the screen. */
    @Test fun anMwebWalletIsNeverShown() {
        val inscribed = map(row(0, Verdict.INSCRIBED, listOf(found("${"1".repeat(64)}i0"))))
        val v = CollectiblesModel.build(listOf(checks(Layer.PUBLIC, map(row(1))), WalletChecks(Layer.PRIVATE, inscribed, null, emptySet(), "MWEB")),
            InscriptionSetting.BuiltIn, Coin.LITECOIN)
        assertTrue(v.tiles.isEmpty())
        assertTrue(v.empty)
    }

    // ---- which wallets the screen shows, and where each item's actions go ----

    private val publicMember = Member("w", com.sparrowwallet.mobile.storage.VaultData(mnemonic = "abandon"))
    private val privateMember = Member("w Private", com.sparrowwallet.mobile.storage.VaultData(mnemonic = "abandon", scriptType = "MWEB"))
    private val taprootMember = Member("w Taproot", com.sparrowwallet.mobile.storage.VaultData(mnemonic = "abandon", scriptType = "P2TR"))
    private val fullFamily = WalletFamily(publicMember, privateMember, taprootMember)

    @Test fun thePublicLayerShowsThePublicAndTaprootWalletsAndNeverThePrivateOne() {
        assertEquals(listOf(Layer.PUBLIC, Layer.TAPROOT), CollectiblesModel.shownLayers(fullFamily, Layer.PUBLIC))
        assertEquals(listOf(Layer.TAPROOT), CollectiblesModel.shownLayers(fullFamily, Layer.TAPROOT))
        assertEquals(listOf(Layer.PUBLIC), CollectiblesModel.shownLayers(WalletFamily(publicMember, privateMember), Layer.PUBLIC))
        assertEquals(listOf(Layer.PUBLIC), CollectiblesModel.shownLayers(WalletFamily(publicMember), Layer.PUBLIC))
        assertEquals(listOf(Layer.PUBLIC, Layer.TAPROOT),
            CollectiblesModel.shownLayers(WalletFamily(publicMember, taproot = taprootMember), Layer.PUBLIC))
        for(layer in fullFamily.layers) assertFalse(Layer.PRIVATE in CollectiblesModel.shownLayers(fullFamily, layer) && layer != Layer.PRIVATE)
    }

    @Test fun eachItemGoesToTheWalletThatHoldsIt() {
        assertSame(publicMember, CollectiblesModel.holder(fullFamily, Layer.PUBLIC, Layer.PUBLIC))
        assertSame(taprootMember, CollectiblesModel.holder(fullFamily, Layer.PUBLIC, Layer.TAPROOT))
        assertSame(taprootMember, CollectiblesModel.holder(fullFamily, Layer.TAPROOT, Layer.TAPROOT))
        //Never another wallet in its place: no wallet, no address and no request
        assertNull(CollectiblesModel.holder(fullFamily, Layer.PUBLIC, Layer.PRIVATE))
        assertNull(CollectiblesModel.holder(fullFamily, Layer.TAPROOT, Layer.PUBLIC))
        assertNull(CollectiblesModel.holder(WalletFamily(publicMember), Layer.PUBLIC, Layer.TAPROOT))
    }

    @Test fun theDetailNamesTheWalletOfATaggedTile() {
        val tagged = tile().copy(layer = Layer.TAPROOT, tag = "Taproot")
        val lines = InscriptionDetail.lines(tagged, Coin.LITECOIN, null, "ltc1paddress", wallet = "w Taproot")
        assertEquals("w Taproot", lines.single { it.label == "Wallet" }.value)
        assertFalse(lines.single { it.label == "Wallet" }.copy)
        //An untagged tile (one wallet shown, or the public wallet's) has no wallet line
        assertFalse(InscriptionDetail.lines(tile(), Coin.LITECOIN, null, null, wallet = "w").any { it.label == "Wallet" })
        assertFalse(InscriptionDetail.lines(tile().copy(layer = Layer.TAPROOT), Coin.LITECOIN, null, null, wallet = "w Taproot")
            .any { it.label == "Wallet" })
    }

    private fun info(name: String = "w", coin: Coin = Coin.LITECOIN, network: Network = Network.MAINNET,
                     scriptType: String = "P2WPKH", links: WalletLinks = WalletLinks()) =
        WalletRepository.WalletInfo(name, network, scriptType, links, coin)

    @Test fun hintShowsOnlyWhereACompanionIsOffered() {
        val w = info()
        assertTrue(CollectiblesModel.companionHint(w, true, "BIP39", listOf(w), Layer.PUBLIC))
        //Only from the Public layer
        assertFalse(CollectiblesModel.companionHint(w, true, "BIP39", listOf(w), Layer.TAPROOT))
        assertFalse(CollectiblesModel.companionHint(w, true, "BIP39", listOf(w), Layer.PRIVATE))
        //Only where a Taproot wallet can be added
        assertFalse(CollectiblesModel.companionHint(null, true, "BIP39", listOf(w), Layer.PUBLIC))
        assertFalse(CollectiblesModel.companionHint(w, false, null, listOf(w), Layer.PUBLIC))
        assertFalse(CollectiblesModel.companionHint(info(coin = Coin.DOGECOIN, scriptType = "P2PKH"), true, "BIP39", listOf(w), Layer.PUBLIC))
        assertFalse(CollectiblesModel.companionHint(info(coin = Coin.BELLSCOIN, network = Network.TESTNET), true, "BIP39", listOf(w), Layer.PUBLIC))
        assertTrue(CollectiblesModel.companionHint(info(coin = Coin.BELLSCOIN), true, "BIP39", listOf(w), Layer.PUBLIC))
        assertFalse(CollectiblesModel.companionHint(info(scriptType = "P2TR"), true, "BIP39", listOf(w), Layer.PUBLIC))
        //Linked both ways: gone. A half-finished link counts as none, so the button shows and finishes it
        val linked = info(links = WalletLinks(taproot = "w Taproot"))
        val tap = info("w Taproot", scriptType = "P2TR", links = WalletLinks(parent = "w"))
        assertFalse(CollectiblesModel.companionHint(linked, true, "BIP39", listOf(linked, tap), Layer.PUBLIC))
        assertTrue(CollectiblesModel.companionHint(linked, true, "BIP39", listOf(linked), Layer.PUBLIC))
        assertTrue(CollectiblesModel.companionHint(linked, true, "BIP39", listOf(linked, tap.copy(links = WalletLinks())), Layer.PUBLIC))
        assertEquals("Inscriptions sent to this seed's Taproot addresses are kept in its Taproot wallet.", CollectiblesModel.TAPROOT_HINT)
        assertEquals("Add Taproot wallet", CollectiblesModel.ADD_TAPROOT)
    }
}
