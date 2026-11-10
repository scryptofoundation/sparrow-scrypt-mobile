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
        runBudget: Int = TrackerRun.RUN_BUDGET
    ): Map<String, InscriptionCheck> = runBlocking {
        TrackerRun.run(Coin.LITECOIN, Network.MAINNET, TxCache(), chain, utxos, stored, answers, indexerSource, deeper, runBudget) { done, _ -> progress.add(done) }
    }

    private fun clean(tx: RawTx.Parsed, block: String?) =
        InscriptionCheck(tx.txid, 0, Verdict.CLEAN, InscriptionCheck.Source.BUILT_IN, false, emptyList(), null, block, emptyList())

    private fun found(id: String) = Found(id, 0, null, hash(9), 1, 1)

    @Test fun storedFinalRowIsKeptWithoutLookups() {
        val tx = cleanCoin(1)
        val stored = clean(tx, block(2_500_000))
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

    /** Review I4: a coin whose trace ends at MWEB or an unresolvable mined coin meets it again every run; its row stands. */
    @Test fun aStoredDeadEndCostsNoLookups() {
        for(reason in listOf(InscriptionTracer.THROUGH_MWEB, InscriptionTracer.FEES_NEED_DEEPER, InscriptionTracer.DOES_NOT_ADD_UP,
                             CheckPolicy.MAY_CARRY_RUNES)) {
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
        val stored = deadEnd(tx, InscriptionTracer.THROUGH_MWEB, block(2_499_999))
        run(listOf(coin(tx)), mapOf(stored.outpoint to stored))
        assertTrue(chain.calls > 0)
    }

    @Test fun aDeadEndWithoutABlockOrWithAnIndexedAnswerIsRetraced() {
        val tx = cleanCoin(1)
        val unproven = deadEnd(tx, InscriptionTracer.THROUGH_MWEB, null)
        run(listOf(coin(tx)), mapOf(unproven.outpoint to unproven))
        assertTrue(chain.calls > 0, "no block")

        chain.calls = 0
        val stored = deadEnd(tx, InscriptionTracer.THROUGH_MWEB)
        val answer = IndexerAnswer(true, emptyList(), runes = false, unlisted = false)
        run(listOf(coin(tx)), mapOf(stored.outpoint to stored), answers = mapOf(stored.outpoint to answer), indexerSource = InscriptionCheck.Source.PUBLIC)
        assertTrue(chain.calls > 0, "an indexed answer")
    }

    /** Budget, server-error and unknown-height rows are not dead ends: they are retried. */
    @Test fun otherUncheckedRowsAreRetried() {
        for(reason in listOf(budgetUsed, serverError + "connection reset", InscriptionTracer.HEIGHT_UNKNOWN)) {
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
        val answer = IndexerAnswer(true, listOf(r.yId), runes = false, unlisted = false)
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
