package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.InscriptionTracer
import com.sparrowwallet.mobile.crypto.track.Piece
import com.sparrowwallet.mobile.crypto.track.TxCache
import com.sparrowwallet.mobile.crypto.track.UnitRange
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.inscriptions.TrackFakes.block
import com.sparrowwallet.mobile.electrum.inscriptions.TrackFakes.coinbase
import com.sparrowwallet.mobile.electrum.inscriptions.TrackFakes.hash
import com.sparrowwallet.mobile.electrum.inscriptions.TrackFakes.reveal
import com.sparrowwallet.mobile.electrum.inscriptions.TrackFakes.spend
import com.sparrowwallet.mobile.electrum.inscriptions.TrackFakes.tx
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Port of the desktop's TrackerRunTest, plus the run budget, the stop on a server error and the changed block cases. */
class TrackerRunTest {
    private val chain = TrackFakes.Chain()
    private val progress = ArrayList<Int>()
    private val budgetUsed = "Lookup budget used up; use Check deeper"
    private val serverError = "Server error, will retry next sync: "

    private fun coin(tx: RawTx.Parsed, vout: Int = 0, height: Int = 2_500_000) =
        TrackerRun.Coin(tx.txid, vout, if(height == 0) null else block(height))

    /** A coin at 2,500,000 whose parent is below Litecoin's first inscription: clean after two lookups. */
    private fun cleanCoin(n: Int): RawTx.Parsed {
        val parent = chain.add(spend(hash(n), 10_000), 2_000_000)
        return chain.add(spend(parent.txid, 9_000), 2_500_000)
    }

    /** A coin whose history is 250 transactions deep above the first inscription: needs more than the default budget. */
    private fun longCoin(n: Int, depth: Int = 250): RawTx.Parsed {
        var previous = chain.add(spend(hash(n), 10_000), 2_000_000)
        for(i in 0 until depth) {
            previous = chain.add(spend(previous.txid, 10_000), 2_500_000 + i)
        }
        return previous
    }

    private val longHeight = 2_500_249

    private fun run(
        utxos: List<TrackerRun.Coin>,
        stored: Map<String, InscriptionCheck> = emptyMap(),
        deeper: Set<String> = emptySet(),
        answers: Map<String, IndexerAnswer> = emptyMap(),
        indexerSource: InscriptionCheck.Source? = null,
        runBudget: Int = TrackerRun.RUN_BUDGET,
        timeUp: () -> Boolean = { false },
        onResult: suspend (String, InscriptionCheck) -> Unit = { _, _ -> },
        chainCoin: Coin = Coin.LITECOIN
    ): Map<String, InscriptionCheck> = runBlocking {
        TrackerRun.run(chainCoin, Network.MAINNET, TxCache(), chain, utxos, stored, answers, indexerSource, deeper, runBudget,
            timeUp, onResult) { done, _ -> progress.add(done) }
    }

    /** Review I2: each coin's result is handed on as soon as it is decided, in run order, so a caller can save as it goes. */
    @Test fun eachDecidedCoinIsHandedOn() {
        val coins = (1..3).map { cleanCoin(it) }
        val stored = clean(coins[1], block(2_500_000))
        val told = ArrayList<Pair<String, InscriptionCheck>>()
        val result = run(coins.map { coin(it) }, mapOf(stored.outpoint to stored), onResult = { op, check -> told.add(op to check) })
        assertEquals(coins.map { it.txid + ":0" }, told.map { it.first })
        assertEquals(result.toList(), told)
    }

    /** A deadline stops the run between coins: no server call after it, and the coins not reached say why. */
    @Test fun aDeadlineStopsTheRunBetweenCoins() {
        val coins = (1..4).map { cleanCoin(it) }
        val standing = clean(coins[3], block(2_500_000))
        var decided = 0
        val result = run(coins.map { coin(it) }, mapOf(standing.outpoint to standing), timeUp = { decided >= 1 },
            onResult = { _, _ -> decided++ })
        assertEquals(Verdict.CLEAN, result.getValue(coins[0].txid + ":0").verdict)
        for(c in coins.subList(1, 3)) {
            val check = result.getValue(c.txid + ":0")
            assertEquals(Verdict.UNCHECKED, check.verdict)
            assertEquals(TrackerRun.TIME_UP, check.reason)
        }
        //A row that stands costs nothing and is kept
        assertSame(standing, result.getValue(standing.outpoint))
        assertEquals(2, chain.calls, "only the first coin's two lookups")
        assertEquals(listOf(1, 2, 3, 4), progress)
    }

    private fun clean(tx: RawTx.Parsed, block: String?) =
        InscriptionCheck(tx.txid, 0, Verdict.CLEAN, InscriptionCheck.Source.BUILT_IN, false, emptyList(), null, block, emptyList())

    private fun found(id: String) = Found(id, 0, null, hash(9), 1, 1)

    @Test fun storedFinalRowIsKeptWithoutLookups() {
        val tx = cleanCoin(1)
        //a row stored since the MWEB peg-out flag exists: nothing to fill (a row without it is filled once, see below)
        val stored = clean(tx, block(2_500_000)).copy(mwebPegOut = false)
        val result = run(listOf(coin(tx)), mapOf(stored.outpoint to stored))
        assertSame(stored, result[stored.outpoint])
        assertEquals(0, chain.calls)
    }

    @Test fun reorgedRowIsTracedAgain() {
        val tx = cleanCoin(1)
        val stored = clean(tx, block(1))
        val result = run(listOf(coin(tx)), mapOf(stored.outpoint to stored))
        assertTrue(chain.calls > 0)
        assertEquals(Verdict.CLEAN, result[stored.outpoint]!!.verdict)
        assertEquals(block(2_500_000), result[stored.outpoint]!!.blockHash)
    }

    @Test fun deeperUsesTheDeeperBudget() {
        val tx = longCoin(1)
        val outpoint = tx.txid + ":0"
        assertEquals(Verdict.UNCHECKED, run(listOf(coin(tx, height = longHeight))).getValue(outpoint).verdict)
        assertEquals(Verdict.CLEAN, run(listOf(coin(tx, height = longHeight)), deeper = setOf(outpoint)).getValue(outpoint).verdict)
    }

    @Test fun unconfirmedCoinHasNoBlockHash() {
        val parent = chain.add(spend(hash(1), 10_000), 2_000_000)
        val tx = chain.add(spend(parent.txid, 9_000), 0)
        val check = run(listOf(coin(tx, height = 0))).getValue(tx.txid + ":0")
        assertEquals(Verdict.CLEAN, check.verdict)
        assertNull(check.blockHash)
        assertFalse(check.proven, "a Clean without a block is provisional")
    }

    /** A Clean without a block (an unconfirmed coin's, whose witness could still change) is not passed on to the coins that spend it. */
    @Test fun provisionalCleanIsNotAKnownResult() {
        val grandparent = chain.add(spend(hash(1), 10_000), 2_000_000)
        val parent = chain.add(reveal(grandparent.txid, "hi", 9_000), 2_500_000)
        val child = chain.add(spend(parent.txid, 8_000), 2_500_001)
        val childCoin = coin(child, height = 2_500_001)

        //With its block, the stored row for the parent stands for its units: the child reads it and looks no further
        val proven = clean(parent, block(2_500_000))
        assertEquals(Verdict.CLEAN, run(listOf(childCoin), mapOf(proven.outpoint to proven)).getValue(child.txid + ":0").verdict)

        //Without one, the trace goes through the parent and finds what it carries
        val provisional = clean(parent, null)
        val check = run(listOf(childCoin), mapOf(provisional.outpoint to provisional)).getValue(child.txid + ":0")
        assertEquals(Verdict.INSCRIBED, check.verdict)
        assertTrue(check.proven)
    }

    @Test fun openPiecesResume() {
        //The phone's per-coin budget is 100 (the desktop's is 200), so 150 steps take two runs
        val tx = longCoin(1, depth = 150)
        val outpoint = tx.txid + ":0"
        val first = run(listOf(coin(tx, height = 2_500_149))).getValue(outpoint)
        assertFalse(first.open.isEmpty())
        val second = run(listOf(coin(tx, height = 2_500_149)), mapOf(outpoint to first)).getValue(outpoint)
        assertEquals(Verdict.CLEAN, second.verdict)
    }

    @Test fun progressPerCoin() {
        run(listOf(coin(cleanCoin(1)), coin(cleanCoin(2))))
        assertEquals(listOf(1, 2), progress)
    }

    @Test fun ownCoinsNeedNoServer() {
        val tx = cleanCoin(1)
        val result = run(listOf(coin(tx)))
        assertEquals(InscriptionCheck.Source.BUILT_IN, result.getValue(tx.txid + ":0").source)
    }

    /** Review I1: a row dropped for a reorg is not read back as the coin's own known result. */
    @Test fun reorgDropsAWrongRow() {
        val tx = cleanCoin(1)
        val wrong = InscriptionCheck(tx.txid, 0, Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, false, listOf(found(hash(9) + "i0")), null, block(1), emptyList())
        val result = run(listOf(coin(tx)), mapOf(wrong.outpoint to wrong))
        assertEquals(Verdict.CLEAN, result.getValue(wrong.outpoint).verdict)
    }

    /** The shared setup of review C2: A holds X at 0 (a stored guess); an old coin carries Y at offset 6,000 through a reveal. */
    private class Setup(val aRow: InscriptionCheck, val send: RawTx.Parsed, val yId: String)

    private fun incompleteSetup(): Setup {
        val parent = chain.add(spend(hash(1), 10_000), 2_000_000)
        val revealY = chain.add(reveal(parent.txid, "Y", 4_000), 2_500_000)
        val older = chain.add(spend(hash(2), 6_000), 2_000_000)
        val a = chain.add(tx(listOf(older.txid to 0, revealY.txid to 0), listOf(10_000)), 2_500_001)
        val aRow = InscriptionCheck(
            a.txid, 0, Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, false, listOf(found(hash(9) + "i0")), budgetUsed,
            block(2_500_001), listOf(Piece(revealY.txid, 0, UnitRange(0, 4_000), 6_000))
        )
        //The user sends X (the first 1,000 units) and keeps the change
        val send = chain.add(spend(a.txid, 1_000, 9_000), 2_500_002)
        return Setup(aRow, send, revealY.txid + "i0")
    }

    /** Review C2: a stored Inscribed row whose trace stopped short is not trusted for the units it did not trace. */
    @Test fun incompleteInscribedRowIsNotTrusted() {
        val s = incompleteSetup()
        val change = TrackerRun.Coin(s.send.txid, 1, block(2_500_002))
        val result = run(listOf(change), mapOf(s.aRow.outpoint to s.aRow))
        assertEquals(Verdict.INSCRIBED, result.getValue(s.send.txid + ":1").verdict, result.toString())
    }

    @Test fun incompleteInscribedIsNotAKnownResult() {
        val s = incompleteSetup()
        val change = TrackerRun.Coin(s.send.txid, 1, block(2_500_002))
        val check = run(listOf(change), mapOf(s.aRow.outpoint to s.aRow)).getValue(s.send.txid + ":1")
        //Trusting A's stored row would find only its guessed X (outside the change's units) and miss Y
        assertEquals(listOf(s.yId), check.found.map { it.inscriptionId })
        assertTrue(chain.calls > 0)
    }

    @Test fun changedBlockDropsTheStoredResult() {
        val tx = cleanCoin(1)
        val stored = clean(tx, block(2_499_999))
        val result = run(listOf(TrackerRun.Coin(tx.txid, 0, block(2_500_001))), mapOf(stored.outpoint to stored))
        assertTrue(chain.calls > 0)
        assertEquals(Verdict.CLEAN, result.getValue(stored.outpoint).verdict)
        assertEquals(block(2_500_001), result.getValue(stored.outpoint).blockHash)
    }

    @Test fun aStoredResultWithoutABlockHashIsTracedAgain() {
        val tx = cleanCoin(1)
        val stored = clean(tx, null)
        run(listOf(coin(tx)), mapOf(stored.outpoint to stored))
        assertTrue(chain.calls > 0)
    }

    /** A stored Unchecked row whose trace met a dead end, on the coin's current block. */
    private fun deadEnd(tx: RawTx.Parsed, reason: String, blockHash: String? = block(2_500_000)) =
        InscriptionCheck(tx.txid, 0, Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, false, emptyList(), reason, blockHash, emptyList())

    /** Review I4: a coin whose trace ends at an unresolvable mined coin meets it again every run; its row stands. */
    @Test fun aStoredDeadEndCostsNoLookups() {
        for(reason in listOf(InscriptionTracer.FEES_NEED_DEEPER, InscriptionTracer.DOES_NOT_ADD_UP, CheckPolicy.MAY_CARRY_RUNES)) {
            val tx = cleanCoin(1)
            val stored = deadEnd(tx, reason)
            chain.calls = 0
            val result = run(listOf(coin(tx)), mapOf(stored.outpoint to stored))
            assertSame(stored, result.getValue(stored.outpoint), reason)
            assertEquals(0, chain.calls, reason)
        }
    }

    @Test fun checkDeeperRetracesADeadEnd() {
        val tx = cleanCoin(1)
        val stored = deadEnd(tx, InscriptionTracer.FEES_NEED_DEEPER)
        val result = run(listOf(coin(tx)), mapOf(stored.outpoint to stored), deeper = setOf(stored.outpoint))
        assertTrue(chain.calls > 0)
        assertEquals(Verdict.CLEAN, result.getValue(stored.outpoint).verdict)
    }

    @Test fun aChangedBlockRetracesADeadEnd() {
        val tx = cleanCoin(1)
        val stored = deadEnd(tx, InscriptionTracer.FEES_NEED_DEEPER, block(2_499_999))
        run(listOf(coin(tx)), mapOf(stored.outpoint to stored))
        assertTrue(chain.calls > 0)
    }

    /**
     * A coin out of MWEB is clean since 2026-11-10 (the owner's rule), so a row stored as "Came through MWEB" before then
     * does not stand on its unchanged block: it is traced again and comes out clean.
     */
    @Test fun aStoredMwebRowIsTracedAgainAndComesOutClean() {
        val hogAddr = ByteArray(34)
        hogAddr[0] = 0x58
        hogAddr[1] = 0x20
        val hogEx = chain.add(tx(listOf(hash(1) to 0), listOf(1_000_000L, 10_000L), outputScripts = listOf(hogAddr, TrackFakes.PLAIN),
            mwebExtension = byteArrayOf(0)), 2_499_999)
        assertTrue(hogEx.mwebNullExtension, "fixture is a flagged HogEx")
        val pegOut = chain.add(tx(listOf(hogEx.txid to 1), listOf(9_000L)), 2_500_000)
        val stored = deadEnd(pegOut, InscriptionTracer.THROUGH_MWEB)
        chain.calls = 0
        val result = run(listOf(coin(pegOut)), mapOf(stored.outpoint to stored))
        assertEquals(Verdict.CLEAN, result.getValue(stored.outpoint).verdict, result.toString())
        assertEquals(2, chain.calls, "the coin and the HogEx")
    }

    /** The HogEx's committed output 0: OP_8 and a direct 32 byte push, 34 bytes exactly. */
    private val hogAddr = ByteArray(34) { 7 }.also { it[0] = 0x58; it[1] = 0x20 }

    /**
     * A HogEx paying the MWEB pool (output 0, [output0]) and a peg-out (output 1), its input 0 spending output [spendVout]
     * of the previous HogEx; [flagged] sets the MWEB flag with the null extension, [extension] any other MWEB extension.
     */
    private fun hogEx(n: Int, height: Int, flagged: Boolean = true, spendVout: Int = 0, output0: ByteArray = hogAddr,
                      extension: ByteArray? = if(flagged) byteArrayOf(0) else null): RawTx.Parsed =
        chain.add(tx(listOf(hash(n) to spendVout), listOf(1_000_000L, 10_000L), outputScripts = listOf(output0, TrackFakes.PLAIN),
            mwebExtension = extension), height)

    private fun pegOutCoin(tx: RawTx.Parsed, height: Int = 2_500_000) = TrackerRun.Coin(tx.txid, 1, block(height))

    private fun storedClean(tx: RawTx.Parsed, vout: Int, source: InscriptionCheck.Source = InscriptionCheck.Source.PUBLIC) =
        InscriptionCheck(tx.txid, vout, Verdict.CLEAN, source, false, emptyList(), null, block(2_500_000), emptyList())

    /** A coin made by a confirmed, flagged HogEx (a peg-out straight out of MWEB) is recorded as one. */
    @Test fun aCoinMadeByAConfirmedFlaggedHogExIsAnMwebPegOut() {
        val hog = hogEx(1, 2_500_000)
        assertTrue(hog.mwebNullExtension, "fixture is a flagged HogEx")
        val check = run(listOf(pegOutCoin(hog))).getValue(hog.txid + ":1")
        assertEquals(Verdict.CLEAN, check.verdict, check.toString())
        assertEquals(true, check.mwebPegOut)
        assertEquals(1, chain.calls, "the HogEx, read once")
    }

    /** An ordinary Clean coin is recorded as not one. */
    @Test fun anOrdinaryCleanCoinIsNotAnMwebPegOut() {
        val tx = cleanCoin(1)
        val check = run(listOf(coin(tx))).getValue(tx.txid + ":0")
        assertEquals(Verdict.CLEAN, check.verdict)
        assertEquals(false, check.mwebPegOut)
    }

    /**
     * Only the tracer's own test counts: a look-alike (the HogEx output shape without the MWEB flag) or an unconfirmed
     * flagged HogEx is never a peg-out, whether traced fresh or filled into a stored Clean row.
     */
    @Test fun aLookAlikeOrAnUnconfirmedHogExIsNeverAnMwebPegOut() {
        val lookAlike = hogEx(1, 2_500_000, flagged = false)
        assertFalse(lookAlike.mwebNullExtension)
        val fresh = run(listOf(pegOutCoin(lookAlike))).getValue(lookAlike.txid + ":1")
        assertTrue(fresh.mwebPegOut != true, fresh.toString())
        val stored = storedClean(lookAlike, 1)
        val filled = run(listOf(pegOutCoin(lookAlike)), mapOf(stored.outpoint to stored)).getValue(stored.outpoint)
        assertEquals(stored.copy(mwebPegOut = false), filled)

        //a flagged HogEx the server says is unconfirmed: never true; its row keeps the flag unknown
        val unconfirmed = hogEx(2, 0)
        val freshUnconfirmed = run(listOf(TrackerRun.Coin(unconfirmed.txid, 1, null))).getValue(unconfirmed.txid + ":1")
        assertTrue(freshUnconfirmed.mwebPegOut != true, freshUnconfirmed.toString())
        val storedUnconfirmed = storedClean(unconfirmed, 1)
        val filledUnconfirmed = run(listOf(pegOutCoin(unconfirmed)), mapOf(storedUnconfirmed.outpoint to storedUnconfirmed))
            .getValue(storedUnconfirmed.outpoint)
        assertEquals(storedUnconfirmed, filledUnconfirmed)
        assertNull(filledUnconfirmed.mwebPegOut)
    }

    /** A Clean row stored before the flag existed gets it filled once: one read of its creating transaction, no trace. */
    @Test fun aStoredCleanRowWithoutTheFlagIsFilledWithOneLookup() {
        val hog = hogEx(1, 2_500_000)
        val stored = storedClean(hog, 1)
        val told = ArrayList<InscriptionCheck>()
        val check = run(listOf(pegOutCoin(hog)), mapOf(stored.outpoint to stored), onResult = { _, c -> told.add(c) }).getValue(stored.outpoint)
        assertEquals(stored.copy(mwebPegOut = true), check, "the stored row with the flag, not a new trace (a trace says BUILT_IN)")
        assertEquals(listOf(check), told, "handed on, so the filled flag is saved")
        assertEquals(1, chain.calls)

        //an ordinary coin: one lookup (its trace would take two), and false
        chain.calls = 0
        val tx = cleanCoin(2)
        val plain = storedClean(tx, 0)
        assertEquals(plain.copy(mwebPegOut = false), run(listOf(coin(tx)), mapOf(plain.outpoint to plain)).getValue(plain.outpoint))
        assertEquals(1, chain.calls)
    }

    /** With the run budget gone, or after a failed read, the stored row is returned unchanged with the flag unknown. */
    @Test fun aStoredCleanRowWithoutTheFlagStaysAsItIsWhenItCannotBeFilled() {
        val hog = hogEx(1, 2_500_000)
        val stored = storedClean(hog, 1)
        assertSame(stored, run(listOf(pegOutCoin(hog)), mapOf(stored.outpoint to stored), runBudget = 0).getValue(stored.outpoint))
        assertEquals(0, chain.calls)
    }

    /**
     * A failed fill read does not stop the run: that row and any later row needing a fill stay as they were (no more
     * fill reads this run), and other coins are still traced.
     */
    @Test fun aFailedFillLeavesTheRowUnknownAndTheRunGoesOn() {
        val hog = hogEx(1, 2_500_000)
        val stored = storedClean(hog, 1)
        val laterHog = hogEx(2, 2_500_000)
        val later = storedClean(laterHog, 1)
        chain.failing.add(hog.txid)
        val other = cleanCoin(3)
        val result = run(listOf(pegOutCoin(hog), coin(other), pegOutCoin(laterHog)), mapOf(stored.outpoint to stored, later.outpoint to later))
        assertSame(stored, result.getValue(stored.outpoint))
        assertNull(result.getValue(stored.outpoint).mwebPegOut)
        val traced = result.getValue(other.txid + ":0")
        assertEquals(Verdict.CLEAN, traced.verdict, traced.toString())
        assertSame(later, result.getValue(later.outpoint), "no further fill reads after a failed one")
        assertEquals(3, chain.calls, "the failed read, then the other coin's two")
    }

    /**
     * Only the committed shape of a HogEx counts: the MWEB flag and extension sit outside the txid, so a server could add
     * them to any transaction. Output 0 must be exactly OP_8 and a direct 32 byte push, input 0 must spend an output 0,
     * and the coin must not be output 0 itself (the MWEB pool). Each forgery or near miss is false, fresh or filled.
     */
    @Test fun onlyTheCommittedHogExShapeMakesAnMwebPegOut() {
        val pushData1 = byteArrayOf(0x58, 0x4c, 0x20) + ByteArray(32) { 7 }
        val cases = listOf(
            "peg-in (MWEB transaction in the extension)" to hogEx(1, 2_500_000, extension = byteArrayOf(1, 2, 3)),
            "PUSHDATA1 output 0 with the null extension added" to hogEx(2, 2_500_000, output0 = pushData1),
            "input 0 spends output 3" to hogEx(3, 2_500_000, spendVout = 3),
            "output 0 one byte long" to hogEx(4, 2_500_000, output0 = hogAddr + byteArrayOf(0))
        )
        for((name, hog) in cases) {
            val fresh = run(listOf(pegOutCoin(hog))).getValue(hog.txid + ":1")
            assertTrue(fresh.mwebPegOut != true, "$name fresh: $fresh")
            val stored = storedClean(hog, 1)
            assertEquals(stored.copy(mwebPegOut = false), run(listOf(pegOutCoin(hog)), mapOf(stored.outpoint to stored)).getValue(stored.outpoint), name)
        }
        //output 0 of a real HogEx is the MWEB pool, not a peg-out
        val real = hogEx(5, 2_500_000)
        val pool = TrackerRun.Coin(real.txid, 0, block(2_500_000))
        assertTrue(run(listOf(pool)).getValue(real.txid + ":0").mwebPegOut != true)
        val storedPool = storedClean(real, 0)
        assertEquals(storedPool.copy(mwebPegOut = false), run(listOf(pool), mapOf(storedPool.outpoint to storedPool)).getValue(storedPool.outpoint))
    }

    /** The flag is bound to the row's block: a creating transaction the server places in another block leaves it unknown. */
    @Test fun aBlockHashMismatchLeavesTheFlagUnknown() {
        val hog = hogEx(1, 2_500_000)
        chain.txs[hog.txid] = chain.txs.getValue(hog.txid).copy(blockHash = block(7))
        val fresh = run(listOf(pegOutCoin(hog))).getValue(hog.txid + ":1")
        assertEquals(Verdict.CLEAN, fresh.verdict, fresh.toString())
        assertNull(fresh.mwebPegOut)
        val stored = storedClean(hog, 1)
        assertSame(stored, run(listOf(pegOutCoin(hog)), mapOf(stored.outpoint to stored)).getValue(stored.outpoint))
    }

    /** Off a chain with MWEB a stored row is not read for the flag: it stays unknown. */
    @Test fun aChainWithoutMwebMakesNoFillRead() {
        val tx = cleanCoin(1)
        val stored = storedClean(tx, 0)
        assertSame(stored, run(listOf(coin(tx)), mapOf(stored.outpoint to stored), chainCoin = Coin.DOGECOIN).getValue(stored.outpoint))
        assertEquals(0, chain.calls)
    }

    /** A creating transaction without the MWEB null extension is not a peg-out at any height, so a missing height still fills false. */
    @Test fun aStoredRowWhoseTransactionLacksTheNullExtensionFillsFalseWithoutAHeight() {
        val tx = cleanCoin(1)
        chain.add(tx, 0)
        val stored = storedClean(tx, 0)
        assertEquals(stored.copy(mwebPegOut = false), run(listOf(coin(tx)), mapOf(stored.outpoint to stored)).getValue(stored.outpoint))
        assertEquals(1, chain.calls)
    }

    /** Only a proven, complete Clean row is filled: other rows are not read for it. */
    @Test fun onlyAStoredProvenCleanRowIsFilled() {
        val hog = hogEx(1, 2_500_000)
        val deadEnd = InscriptionCheck(hog.txid, 1, Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, false, emptyList(),
            InscriptionTracer.FEES_NEED_DEEPER, block(2_500_000), emptyList())
        assertSame(deadEnd, run(listOf(pegOutCoin(hog)), mapOf(deadEnd.outpoint to deadEnd)).getValue(deadEnd.outpoint))
        assertEquals(0, chain.calls)
    }

    @Test fun aDeadEndWithoutABlockOrWithAnIndexedAnswerIsRetraced() {
        val tx = cleanCoin(1)
        val unproven = deadEnd(tx, InscriptionTracer.FEES_NEED_DEEPER, null)
        run(listOf(coin(tx)), mapOf(unproven.outpoint to unproven))
        assertTrue(chain.calls > 0, "no block")

        chain.calls = 0
        val stored = deadEnd(tx, InscriptionTracer.FEES_NEED_DEEPER)
        val answer = IndexerAnswer(true, emptyList(), runes = false, unlisted = false)
        run(listOf(coin(tx)), mapOf(stored.outpoint to stored), answers = mapOf(stored.outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC)
        assertTrue(chain.calls > 0, "an indexed answer")
    }

    /**
     * Budget, server-error, unknown-height and MWEB rows are not dead ends: they are retried. (The phone's stop at an MWEB
     * input, beyond drongo, still says "Came through MWEB", so such a coin is traced again each run.)
     */
    @Test fun otherUncheckedRowsAreRetried() {
        for(reason in listOf(budgetUsed, serverError + "connection reset", InscriptionTracer.HEIGHT_UNKNOWN, InscriptionTracer.THROUGH_MWEB)) {
            val tx = cleanCoin(1)
            val stored = deadEnd(tx, reason)
            chain.calls = 0
            run(listOf(coin(tx)), mapOf(stored.outpoint to stored))
            assertTrue(chain.calls > 0, reason)
        }
    }

    @Test fun serverErrorLeavesTheRestUnchecked() {
        val coins = (1..5).map { cleanCoin(it) }
        chain.failing.add(coins[2].txid)
        val storedFourth = InscriptionCheck(
            coins[3].txid, 0, Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, false, emptyList(), "Mined coin fees need Check deeper",
            block(2_500_000), listOf(Piece(hash(77), 0, UnitRange(0, 100), 0))
        )
        val result = run(coins.map { coin(it) }, mapOf(storedFourth.outpoint to storedFourth))
        assertEquals(coins.map { it.txid + ":0" }, result.keys.toList())
        assertEquals(Verdict.CLEAN, result.getValue(coins[0].txid + ":0").verdict)
        assertEquals(Verdict.CLEAN, result.getValue(coins[1].txid + ":0").verdict)
        val third = result.getValue(coins[2].txid + ":0")
        assertEquals(Verdict.UNCHECKED, third.verdict)
        assertEquals(serverError + "connection reset", third.reason)
        //The fourth keeps its stored result as it is; the fifth has none and says why
        assertSame(storedFourth, result.getValue(storedFourth.outpoint))
        val fifth = result.getValue(coins[4].txid + ":0")
        assertEquals(Verdict.UNCHECKED, fifth.verdict)
        assertEquals(serverError + "connection reset", fifth.reason)
        assertTrue(fifth.open.isEmpty())
        //Two clean coins (two lookups each) and the failing call: nothing after it
        assertEquals(5, chain.calls)
    }

    @Test fun runBudgetStopsTheRun() {
        val coins = (1..4).map { longCoin(it * 10) }
        val storedThird = InscriptionCheck(
            coins[2].txid, 0, Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, false, emptyList(), budgetUsed,
            block(longHeight), listOf(Piece(hash(77), 0, UnitRange(0, 100), 0))
        )
        val result = run(coins.map { coin(it, height = longHeight) }, mapOf(storedThird.outpoint to storedThird), runBudget = 150)
        //The first coin uses its 100, the second is capped to the 50 left, then nothing is asked of the server
        assertEquals(150, chain.calls)
        assertEquals(Verdict.UNCHECKED, result.getValue(coins[0].txid + ":0").verdict)
        assertEquals(Verdict.UNCHECKED, result.getValue(coins[1].txid + ":0").verdict)
        val third = result.getValue(storedThird.outpoint)
        assertSame(storedThird, third)
        assertEquals(budgetUsed, third.reason)
        assertEquals(1, third.open.size)
        val fourth = result.getValue(coins[3].txid + ":0")
        assertEquals(Verdict.UNCHECKED, fourth.verdict)
        assertEquals(budgetUsed, fourth.reason)
        assertEquals(InscriptionCheck.Source.BUILT_IN, fourth.source)
        assertTrue(fourth.open.isEmpty())
        assertEquals(listOf(1, 2, 3, 4), progress)
    }

    @Test fun coinsWhoseStoredResultStandsCostNothingWhenTheBudgetIsGone() {
        val long = longCoin(10)
        val standing = cleanCoin(20)
        val stored = clean(standing, block(2_500_000))
        val result = run(listOf(coin(long, height = longHeight), coin(standing)), mapOf(stored.outpoint to stored), runBudget = 100)
        assertSame(stored, result.getValue(stored.outpoint))
        assertEquals(100, chain.calls)
    }

    @Test fun aReorgedStoredResultIsNotKeptWhenTheBudgetIsGone() {
        val long = longCoin(10)
        val reorged = cleanCoin(20)
        val stored = clean(reorged, block(1))
        val result = run(listOf(coin(long, height = longHeight), coin(reorged)), mapOf(stored.outpoint to stored), runBudget = 100)
        val check = result.getValue(stored.outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(budgetUsed, check.reason)
        assertEquals(block(2_500_000), check.blockHash)
    }

    @Test fun deeperCoinGetsTheDeeperBudgetAndBlockWalk() {
        //A mined coin's fee part: the block's fees come only from walking its transactions
        val parent = chain.add(spend(hash(5), 10_000), 2_000_000)
        val paying = chain.add(spend(parent.txid, 5_000), 2_500_000)
        val mined = chain.add(coinbase(1_250_000_000L, 5_000), 2_500_000)
        chain.positions[2_500_000] = listOf(mined.txid, paying.txid)
        val minedCoin = TrackerRun.Coin(mined.txid, 1, block(2_500_000))
        val outpoint = mined.txid + ":1"
        val plain = run(listOf(minedCoin)).getValue(outpoint)
        assertEquals(Verdict.UNCHECKED, plain.verdict)
        assertEquals("Mined coin fees need Check deeper", plain.reason)
        assertEquals(0, chain.positionCalls)
        val walked = run(listOf(minedCoin), deeper = setOf(outpoint)).getValue(outpoint)
        assertEquals(Verdict.CLEAN, walked.verdict, walked.toString())
        assertTrue(chain.positionCalls > 0)

        //The deeper budget is not the run budget: a small run budget does not cut it short
        val long = longCoin(30)
        val longOutpoint = long.txid + ":0"
        val free = run(listOf(coin(long, height = longHeight)), deeper = setOf(longOutpoint), runBudget = 150).getValue(longOutpoint)
        assertEquals(Verdict.CLEAN, free.verdict)
    }

    @Test fun deeperCoinsAreCheckedFirstOutsideTheRunBudget() {
        //Seven coins of 250 steps each (100 lookups apiece under the default budget) and a deeper one of 800 steps, listed last
        val others = (1..7).map { longCoin(100 + it) }
        val big = longCoin(200, depth = 800)
        val bigOutpoint = big.txid + ":0"
        val utxos = others.map { coin(it, height = longHeight) } + coin(big, height = 2_500_799)
        val result = run(utxos, deeper = setOf(bigOutpoint))
        assertEquals(utxos.map { it.outpoint }, result.keys.toList())
        assertEquals(Verdict.CLEAN, result.getValue(bigOutpoint).verdict)
        //About 800 for the deeper coin, then the full 600 for the rest: six coins traced, the seventh left alone
        assertTrue(chain.calls in 1_400..1_410, "calls ${chain.calls}")
        for(c in others.take(6)) {
            assertTrue(result.getValue(c.txid + ":0").open.isNotEmpty())
        }
        val seventh = result.getValue(others[6].txid + ":0")
        assertEquals(budgetUsed, seventh.reason)
        assertTrue(seventh.open.isEmpty())
    }

    @Test fun aContradictedStoredCleanIsNotKeptWhenTheBudgetIsGone() {
        val tx = cleanCoin(1)
        val stored = clean(tx, block(2_500_000))
        val answer = IndexerAnswer(true, emptyList(), runes = true, unlisted = false)
        val check = run(listOf(coin(tx)), mapOf(stored.outpoint to stored), answers = mapOf(stored.outpoint to answer),
            indexerSource = InscriptionCheck.Source.PUBLIC, runBudget = 0).getValue(stored.outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(budgetUsed, check.reason)
        assertEquals(0, chain.calls)
    }

    @Test fun aContradictedStoredInscribedIsNotKeptWhenTheBudgetIsGone() {
        val tx = cleanCoin(1)
        val stored = InscriptionCheck(tx.txid, 0, Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, false, listOf(found(hash(9) + "i0")), null, block(2_500_000), emptyList())
        val answer = IndexerAnswer(true, emptyList(), runes = false, unlisted = false)
        val check = run(listOf(coin(tx)), mapOf(stored.outpoint to stored), answers = mapOf(stored.outpoint to answer),
            indexerSource = InscriptionCheck.Source.PUBLIC, runBudget = 0).getValue(stored.outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(budgetUsed, check.reason)
    }

    @Test fun aStoredCleanWithoutABlockHashIsNotKeptWhenTheBudgetIsGone() {
        val tx = cleanCoin(1)
        val stored = clean(tx, null)
        val check = run(listOf(coin(tx)), mapOf(stored.outpoint to stored), runBudget = 0).getValue(stored.outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(budgetUsed, check.reason)
    }

    @Test fun aContradictedStoredCleanIsNotKeptAfterAServerError() {
        val broken = cleanCoin(1)
        chain.failing.add(broken.txid)
        val tx = cleanCoin(2)
        val stored = clean(tx, block(2_500_000))
        val answer = IndexerAnswer(true, emptyList(), runes = true, unlisted = false)
        val result = run(listOf(coin(broken), coin(tx)), mapOf(stored.outpoint to stored), answers = mapOf(stored.outpoint to answer),
            indexerSource = InscriptionCheck.Source.PUBLIC)
        val check = result.getValue(stored.outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(serverError + "connection reset", check.reason)
    }

    /** A coin whose stored UNCHECKED result resumes from its parent: the trace never reads the coin's own transaction. */
    private fun resumedCoin(n: Int): Pair<RawTx.Parsed, InscriptionCheck> {
        val parent = chain.add(spend(hash(n), 10_000), 2_000_000)
        val tx = chain.add(spend(parent.txid, 9_000), 2_500_000)
        val stored = InscriptionCheck(
            tx.txid, 0, Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, false, emptyList(), budgetUsed, block(2_500_000),
            listOf(Piece(parent.txid, 0, UnitRange(0, 9_000), 0))
        )
        return tx to stored
    }

    @Test fun aResumedCleanTraceNeedsTheCreatingTransaction() {
        val (tx, stored) = resumedCoin(1)
        val result = run(listOf(coin(tx)), mapOf(stored.outpoint to stored))
        assertEquals(Verdict.CLEAN, result.getValue(stored.outpoint).verdict)
        //The parent, then the coin's own transaction for the runes rule
        assertEquals(2, chain.calls)
    }

    @Test fun aFailedCreatingTransactionFetchLeavesTheCoinUncheckedAndStopsTheRun() {
        val (tx, stored) = resumedCoin(1)
        chain.failing.add(tx.txid)
        val other = cleanCoin(2)
        val result = run(listOf(coin(tx), coin(other)), mapOf(stored.outpoint to stored))
        val check = result.getValue(stored.outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(serverError + "connection reset", check.reason)
        assertEquals(serverError + "connection reset", result.getValue(other.txid + ":0").reason)
        //The parent and the failing call; nothing after
        assertEquals(2, chain.calls)
    }

    @Test fun aCreatingTransactionWithAnotherTxidLeavesTheCoinUncheckedAndStopsTheRun() {
        val (tx, stored) = resumedCoin(1)
        val wrong = cleanCoin(5)
        chain.txs[tx.txid] = chain.txs.getValue(wrong.txid)
        val other = cleanCoin(2)
        val callsBefore = chain.calls
        val result = run(listOf(coin(tx), coin(other)), mapOf(stored.outpoint to stored))
        val check = result.getValue(stored.outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertTrue(check.reason!!.startsWith(serverError), check.reason)
        assertTrue(result.getValue(other.txid + ":0").reason!!.startsWith(serverError))
        assertEquals(callsBefore + 2, chain.calls)
    }

    @Test fun theCreatingTransactionReadIsBoundByTheRunBudget() {
        val (tx, stored) = resumedCoin(1)
        //One lookup for the parent uses the whole run budget
        val check = run(listOf(coin(tx)), mapOf(stored.outpoint to stored), runBudget = 1).getValue(stored.outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(budgetUsed, check.reason)
        assertEquals(1, chain.calls)
    }

    @Test fun revealChecksCountAgainstTheRunBudget() {
        val tx = cleanCoin(1)
        val outpoint = tx.txid + ":0"
        val answer = IndexerAnswer(true, listOf(hash(999) + "i0"), runes = false, unlisted = false)
        //The trace takes the two lookups of the run budget, so no reveal call is made
        val check = run(listOf(coin(tx)), answers = mapOf(outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC, runBudget = 2).getValue(outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(budgetUsed, check.reason)
        assertEquals(2, chain.calls)
    }

    @Test fun cancellationIsNotAServerError() {
        val tx = cleanCoin(1)
        chain.cancelling.add(tx.txid)
        assertFailsWith<CancellationException> { run(listOf(coin(tx))) }
    }

    @Test fun anIndexedAnswerNamingAnInscriptionIsCheckedAgainstTheChain() {
        val tx = cleanCoin(1)
        val parent = chain.add(spend(hash(8), 10_000), 2_000_000)
        val creator = chain.add(reveal(parent.txid, "Z", 9_000), 2_450_000)
        val id = creator.txid + "i0"
        val outpoint = tx.txid + ":0"
        val answer = IndexerAnswer(true, listOf(id), runes = false, unlisted = false)
        val check = run(listOf(coin(tx)), answers = mapOf(outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC).getValue(outpoint)
        assertEquals(Verdict.INSCRIBED, check.verdict)
        assertEquals(InscriptionCheck.Source.PUBLIC, check.source)
        assertEquals(listOf(id), check.found.map { it.inscriptionId })
        assertEquals(2_450_000, check.found.first().mintHeight)
    }

    @Test fun anIndexedInscriptionThatTheChainDoesNotShowLeavesTheCoinHeld() {
        val tx = cleanCoin(1)
        val parent = chain.add(spend(hash(8), 10_000), 2_000_000)
        //A transaction that exists but creates nothing
        val plain = chain.add(spend(parent.txid, 9_000), 2_450_000)
        val id = plain.txid + "i0"
        val outpoint = tx.txid + ":0"
        val answer = IndexerAnswer(true, listOf(id), runes = false, unlisted = false)
        val check = run(listOf(coin(tx)), answers = mapOf(outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC).getValue(outpoint)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals("Indexer and chain disagree: $id not found on chain", check.reason)
    }

    /** A reveal the run already holds (traced earlier, in the cache) is read from the cache, not fetched again. */
    @Test fun revealIsReadFromTheCache() {
        val tx = cleanCoin(1)
        val parent = chain.add(spend(hash(8), 10_000), 2_000_000)
        val creator = chain.add(reveal(parent.txid, "Z", 9_000), 2_450_000)
        val id = creator.txid + "i0"
        val outpoint = tx.txid + ":0"
        val answer = IndexerAnswer(true, listOf(id), runes = false, unlisted = false)
        val cache = TxCache()
        cache.put(chain.txs.getValue(creator.txid))
        val check = runBlocking {
            TrackerRun.run(Coin.LITECOIN, Network.MAINNET, cache, chain, listOf(coin(tx)), emptyMap(), mapOf(outpoint to answer),
                InscriptionCheck.Source.PUBLIC, emptySet(), TrackerRun.RUN_BUDGET, { false }, { _, _ -> }) { done, _ -> progress.add(done) }
        }.getValue(outpoint)
        assertEquals(Verdict.INSCRIBED, check.verdict)
        assertEquals(2, chain.calls, "only the trace's two lookups; the reveal came from the cache")
        //A reveal fetched once is kept, so the next check does not fetch it again
        val fresh = TxCache()
        chain.calls = 0
        val first = runBlocking { RevealCheck.reveal(id, chain, fresh) }
        assertEquals(1, chain.calls)
        assertSame(first, runBlocking { RevealCheck.reveal(id, chain, fresh) })
        assertEquals(1, chain.calls)
    }

    /** A cached reveal is free: with the run budget used up by the trace, it is still read and never refused for budget. */
    @Test fun aCachedRevealIsNotRefusedForBudget() {
        val tx = cleanCoin(1)
        val parent = chain.add(spend(hash(8), 10_000), 2_000_000)
        val creator = chain.add(reveal(parent.txid, "Z", 9_000), 2_450_000)
        val id = creator.txid + "i0"
        val outpoint = tx.txid + ":0"
        val answer = IndexerAnswer(true, listOf(id), runes = false, unlisted = false)
        val cache = TxCache()
        cache.put(chain.txs.getValue(creator.txid))
        val check = runBlocking {
            TrackerRun.run(Coin.LITECOIN, Network.MAINNET, cache, chain, listOf(coin(tx)), emptyMap(), mapOf(outpoint to answer),
                InscriptionCheck.Source.PUBLIC, emptySet(), 2, { false }, { _, _ -> }) { done, _ -> progress.add(done) }
        }.getValue(outpoint)
        assertEquals(Verdict.INSCRIBED, check.verdict)
        assertEquals(2, chain.calls, "only the trace's two lookups")
    }

    /** A reveal that is not cached counts one lookup: with a budget of 3, the trace's 2 and the reveal's 1 leave nothing for the next coin. */
    @Test fun anUncachedRevealCountsOneLookup() {
        val first = cleanCoin(1)
        val second = cleanCoin(2)
        val parent = chain.add(spend(hash(8), 10_000), 2_000_000)
        val creator = chain.add(reveal(parent.txid, "Z", 9_000), 2_450_000)
        val answer = IndexerAnswer(true, listOf(creator.txid + "i0"), runes = false, unlisted = false)
        val result = run(listOf(coin(first), coin(second)), answers = mapOf(first.txid + ":0" to answer),
            indexerSource = InscriptionCheck.Source.PUBLIC, runBudget = 3)
        assertEquals(Verdict.INSCRIBED, result.getValue(first.txid + ":0").verdict)
        val two = result.getValue(second.txid + ":0")
        assertEquals(Verdict.UNCHECKED, two.verdict)
        assertEquals(budgetUsed, two.reason)
        assertEquals(3, chain.calls)
    }

    /** A source that answers another transaction than the one asked for is an error, never "no inscription found". */
    @Test fun aRevealFromTheWrongTransactionIsAnError() {
        val parent = chain.add(spend(hash(8), 10_000), 2_000_000)
        val other = chain.add(reveal(parent.txid, "Z", 9_000), 2_450_000)
        val asked = hash(777)
        val source = object : com.sparrowwallet.mobile.crypto.track.ChainSource {
            override suspend fun transaction(txid: String) = chain.txs.getValue(other.txid)
            override suspend fun blockFees(height: Int, blockHash: String) = null
            override suspend fun txidAt(height: Int, position: Int): String? = null
        }
        val cache = TxCache()
        assertFailsWith<IllegalStateException> { runBlocking { RevealCheck.reveal(asked + "i0", source, cache) } }
        assertNull(cache.get(asked))
        assertNull(cache.get(other.txid), "the wrong transaction is not kept either")
        //the right one still reveals
        assertEquals(other.txid, runBlocking { RevealCheck.reveal(other.txid + "i0", source, cache) }!!.tx.txid)
    }

    @Test fun aRevealCheckThatFailsStopsTheRun() {
        val first = cleanCoin(1)
        val second = cleanCoin(2)
        val answer = IndexerAnswer(true, listOf(hash(999) + "i0"), runes = false, unlisted = false)
        val result = run(listOf(coin(first), coin(second)), answers = mapOf(first.txid + ":0" to answer), indexerSource = InscriptionCheck.Source.PUBLIC)
        val one = result.getValue(first.txid + ":0")
        assertEquals(Verdict.UNCHECKED, one.verdict)
        assertTrue(one.reason!!.startsWith(serverError))
        val two = result.getValue(second.txid + ":0")
        assertEquals(Verdict.UNCHECKED, two.verdict)
        assertEquals(one.reason, two.reason)
        //The second coin was never asked of the server
        assertEquals(2 + 1, chain.calls)
    }

    @Test fun aStoredResultContradictedByTheIndexerIsTracedAgain() {
        val tx = cleanCoin(1)
        val stored = clean(tx, block(2_500_000))
        val answer = IndexerAnswer(true, emptyList(), runes = true, unlisted = false)
        val check = run(listOf(coin(tx)), mapOf(stored.outpoint to stored), answers = mapOf(stored.outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC)
            .getValue(stored.outpoint)
        assertTrue(chain.calls > 0)
        assertTrue(check.runes)
        assertEquals(InscriptionCheck.Source.PUBLIC, check.source)
    }

    @Test fun aCancelledCreatingTransactionFetchIsRethrown() {
        val (tx, stored) = resumedCoin(1)
        chain.cancelling.add(tx.txid)
        assertFailsWith<CancellationException> { run(listOf(coin(tx)), mapOf(stored.outpoint to stored)) }
    }

    /** A coin holding inscription Y near the front of a 150 step ancestry above the first inscription: a trace of 100 lookups finds Y and stops short. */
    private class Revealed(val tx: RawTx.Parsed, val yId: String, val links: List<RawTx.Parsed>)

    private fun revealedCoin(): Revealed {
        var previous = chain.add(spend(hash(300), 10_000), 2_000_000)
        val links = arrayListOf(previous)
        for(i in 0 until 150) {
            previous = chain.add(spend(previous.txid, 10_000), 2_500_000 + i)
            links.add(previous)
        }
        val revealY = chain.add(reveal(previous.txid, "Y", 4_000), 2_600_000)
        val older = chain.add(spend(hash(301), 6_000), 2_000_000)
        val a = chain.add(tx(listOf(older.txid to 0, revealY.txid to 0), listOf(10_000)), 2_600_001)
        return Revealed(a, revealY.txid + "i0", links)
    }

    private fun assertStartsOver(r: Revealed, first: InscriptionCheck) {
        //The held row forgot the find, so it must not carry the pieces a resume would take for the whole coin
        assertEquals(Verdict.UNCHECKED, first.verdict)
        assertTrue(first.found.isEmpty())
        assertTrue(first.open.isEmpty(), first.toString())
        val again = run(listOf(coin(r.tx, height = 2_600_001)), mapOf(first.outpoint to first)).getValue(first.outpoint)
        assertEquals(Verdict.INSCRIBED, again.verdict, again.toString())
        assertEquals(listOf(r.yId), again.found.map { it.inscriptionId })
    }

    @Test fun aBudgetExitAfterAFindStartsTheTraceOverNextRun() {
        val r = revealedCoin()
        val outpoint = r.tx.txid + ":0"
        //Y was read by the trace, so its reveal check is free; a second id the trace did not read is what the budget refuses
        val elsewhere = chain.add(reveal(hash(302), "W", 4_000), 2_550_000)
        val answer = IndexerAnswer(true, listOf(r.yId, elsewhere.txid + "i0"), runes = false, unlisted = false)
        val first = run(listOf(coin(r.tx, height = 2_600_001)), answers = mapOf(outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC, runBudget = 100)
            .getValue(outpoint)
        assertEquals(budgetUsed, first.reason)
        assertStartsOver(r, first)
    }

    @Test fun aRevealFailureAfterAFindStartsTheTraceOverNextRun() {
        val r = revealedCoin()
        val outpoint = r.tx.txid + ":0"
        val answer = IndexerAnswer(true, listOf(r.yId, hash(999) + "i0"), runes = false, unlisted = false)
        val first = run(listOf(coin(r.tx, height = 2_600_001)), answers = mapOf(outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC)
            .getValue(outpoint)
        assertTrue(first.reason!!.startsWith(serverError), first.reason)
        assertStartsOver(r, first)
    }

    @Test fun aServerErrorAfterAFindStartsTheTraceOverNextRun() {
        val r = revealedCoin()
        val outpoint = r.tx.txid + ":0"
        val answer = IndexerAnswer(true, listOf(r.yId), runes = false, unlisted = false)
        chain.failing.add(r.links[r.links.size - 50].txid)
        val first = run(listOf(coin(r.tx, height = 2_600_001)), answers = mapOf(outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC)
            .getValue(outpoint)
        assertTrue(first.reason!!.startsWith(serverError), first.reason)
        chain.failing.clear()
        assertStartsOver(r, first)
    }
}
