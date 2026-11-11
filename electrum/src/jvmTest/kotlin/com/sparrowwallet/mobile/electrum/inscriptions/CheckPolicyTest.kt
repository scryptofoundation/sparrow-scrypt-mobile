package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.TxOutput
import com.sparrowwallet.mobile.crypto.track.ChainTx
import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.Piece
import com.sparrowwallet.mobile.crypto.track.TraceResult
import com.sparrowwallet.mobile.crypto.track.UnitRange
import com.sparrowwallet.mobile.crypto.track.Verdict
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckPolicyTest {
    private fun hash(n: Int) = n.toString(16).padStart(64, '0')
    private fun block(height: Int) = (0xb10c0000L + height).toString(16).padStart(64, '0')

    private val txid = hash(1)
    private val id = hash(7) + "i0"
    private val blockHash = block(2_500_000)

    private fun tx(id: String, vararg scripts: ByteArray) = RawTx.Parsed(id, scripts.map { TxOutput(1_000, it) })
    private fun plain(id: String) = tx(id, byteArrayOf(0x51))
    private val reveal = ChainTx(plain(hash(2)), 2_400_000, block(2_400_000), 1_600_000_000L, true)

    private fun trace(verdict: Verdict, found: List<Found> = emptyList(), usedChain: Boolean) =
        TraceResult(verdict, found, if(verdict == Verdict.UNCHECKED) "Lookup budget used up; use Check deeper" else null, emptyList(), 3, usedChain)

    private fun answer(vararg inscriptions: String, runes: Boolean = false, unlisted: Boolean = false, indexed: Boolean = true) =
        IndexerAnswer(indexed, inscriptions.toList(), runes, unlisted)

    private fun decide(answer: IndexerAnswer?, trace: TraceResult, reveals: Map<String, ChainTx?> = emptyMap(), creatingTx: RawTx.Parsed? = null) =
        CheckPolicy.decide(txid, 0, answer, InscriptionCheck.Source.PUBLIC, trace, reveals, creatingTx, blockHash)

    @Test fun rule1RunesKeepTheVerdictAndMarkRunes() {
        val check = decide(answer(runes = true), trace(Verdict.CLEAN, usedChain = true))
        assertEquals(Verdict.CLEAN, check.verdict)
        assertTrue(check.runes)
    }

    @Test fun unlistedMarksRunesToo() {
        val check = decide(answer(unlisted = true), trace(Verdict.CLEAN, usedChain = true))
        assertEquals(Verdict.CLEAN, check.verdict)
        assertTrue(check.runes)
    }

    @Test fun rule2IndexerInscriptionConfirmed() {
        val traced = Found(id, 0, null, hash(7), 2_400_000, 1_600_000_000L)
        val check = decide(answer(id), trace(Verdict.INSCRIBED, listOf(traced), true), mapOf(id to reveal))
        assertEquals(Verdict.INSCRIBED, check.verdict)
        assertEquals(InscriptionCheck.Source.PUBLIC, check.source)
        assertEquals(0, check.found.first().offset)
        assertEquals(blockHash, check.blockHash)
        assertNull(check.reason)
        assertTrue(check.open.isEmpty())

        val untraced = decide(answer(id), trace(Verdict.UNCHECKED, usedChain = true), mapOf(id to reveal))
        assertEquals(Verdict.INSCRIBED, untraced.verdict)
        assertEquals(-1, untraced.found.first().offset)
        assertEquals(2_400_000, untraced.found.first().mintHeight)
        assertEquals(hash(2), untraced.found.first().mintTxid)
        assertEquals(1_600_000_000L, untraced.found.first().mintTime)
    }

    @Test fun rule3IndexerInscriptionNotOnChain() {
        val check = decide(answer(id), trace(Verdict.CLEAN, usedChain = true), mapOf(id to null))
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals("Indexer and chain disagree: $id not found on chain", check.reason)
        assertTrue(check.open.isEmpty())
    }

    @Test fun aMissingRevealKeyIsTreatedAsNotOnChain() {
        val check = decide(answer(id), trace(Verdict.CLEAN, usedChain = true), emptyMap())
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals("Indexer and chain disagree: $id not found on chain", check.reason)
    }

    @Test fun rule4IndexerCleanButChainShowsOne() {
        val traced = Found(id, 0, null, hash(7), 2_400_000, 1L)
        val check = decide(answer(), trace(Verdict.INSCRIBED, listOf(traced), true))
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals("Indexer and chain disagree: chain shows $id", check.reason)
    }

    @Test fun rule5IndexerCleanStands() {
        assertEquals(Verdict.CLEAN, decide(answer(), trace(Verdict.CLEAN, usedChain = true)).verdict)
        val budget = decide(answer(), trace(Verdict.UNCHECKED, usedChain = true))
        assertEquals(Verdict.CLEAN, budget.verdict)
        assertEquals(InscriptionCheck.Source.PUBLIC, budget.source)
        assertNull(budget.reason)
    }

    /**
     * The phone is stricter than the desktop: an indexer's "nothing here" never turns a trace that failed on a server
     * error into Clean. The row stays Unchecked with the trace's reason, so it is retried, and keeps its open pieces.
     */
    @Test fun indexedNothingDoesNotClearAFailedTrace() {
        val open = Piece(hash(5), 0, UnitRange(0, 100), 200)
        val reason = "${com.sparrowwallet.mobile.crypto.track.InscriptionTracer.SERVER_ERROR_PREFIX}: connection reset"
        val failed = TraceResult(Verdict.UNCHECKED, emptyList(), reason, listOf(open), 2, true)
        val check = decide(answer(), failed)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals(reason, check.reason)
        assertEquals(InscriptionCheck.Source.PUBLIC, check.source)
        assertEquals(listOf(open), check.open)
        assertFalse(CheckPolicy.complete(check))
        //runes the indexer reported are kept on the row
        assertTrue(decide(answer(runes = true), failed).runes)
        assertEquals(Verdict.UNCHECKED, decide(answer(runes = true), failed).verdict)
    }

    /** A trace cut short by the budget or a dead end lets the indexer's "nothing here" stand, as on the desktop. */
    @Test fun indexedNothingStandsAfterABudgetCut() {
        val budget = decide(answer(), trace(Verdict.UNCHECKED, usedChain = true))
        assertEquals(Verdict.CLEAN, budget.verdict)
        assertNull(budget.reason)
        val deadEnd = TraceResult(Verdict.UNCHECKED, emptyList(), com.sparrowwallet.mobile.crypto.track.InscriptionTracer.FEES_NEED_DEEPER, emptyList(), 4, true)
        assertEquals(Verdict.CLEAN, decide(answer(), deadEnd).verdict)
        assertEquals(Verdict.CLEAN, decide(answer(), trace(Verdict.CLEAN, usedChain = true)).verdict)
    }

    /** Whichever side names an inscription the other does not, the coin is held (never Clean). */
    @Test fun indexerAndChainDisagreeHolds() {
        val traced = Found(id, 0, null, hash(7), 2_400_000, 1L)
        //the indexer says nothing, the chain shows one
        assertEquals(Verdict.UNCHECKED, decide(answer(), trace(Verdict.INSCRIBED, listOf(traced), true)).verdict)
        //the indexer names one the chain does not have
        assertEquals(Verdict.UNCHECKED, decide(answer(id), trace(Verdict.CLEAN, usedChain = true), mapOf(id to null)).verdict)
        //the chain shows one the indexer did not list
        val other = hash(8) + "i0"
        val both = listOf(traced, Found(other, 500, null, hash(8), 1, 1))
        assertEquals(Verdict.UNCHECKED, decide(answer(id), trace(Verdict.INSCRIBED, both, true), mapOf(id to reveal)).verdict)
        //a failed trace and an indexer naming nothing: still not Clean
        val failed = TraceResult(Verdict.UNCHECKED, emptyList(), "${com.sparrowwallet.mobile.crypto.track.InscriptionTracer.SERVER_ERROR_PREFIX}: x", emptyList(), 1, true)
        assertEquals(Verdict.UNCHECKED, decide(answer(), failed).verdict)
    }

    @Test fun rule6NoAnswerTakesTheTrace() {
        val own = decide(null, trace(Verdict.CLEAN, usedChain = false))
        assertEquals(Verdict.CLEAN, own.verdict)
        assertEquals(InscriptionCheck.Source.OWN, own.source)
        val builtIn = decide(null, trace(Verdict.UNCHECKED, usedChain = true))
        assertEquals(Verdict.UNCHECKED, builtIn.verdict)
        assertEquals(InscriptionCheck.Source.BUILT_IN, builtIn.source)
        assertEquals("Lookup budget used up; use Check deeper", builtIn.reason)
    }

    @Test fun rule7NoAnswerAndARunestoneMayCarryRunes() {
        val runestone = tx(hash(3), byteArrayOf(0x6a, 0x5d, 0x00))
        val check = decide(null, trace(Verdict.CLEAN, usedChain = true), creatingTx = runestone)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals("May carry runes", check.reason)
    }

    @Test fun cleanTraceWithRunestoneIsUnchecked() {
        val runestone = tx(hash(3), byteArrayOf(0x51), byteArrayOf(0x6a, 0x5d, 0x00))
        val check = decide(null, trace(Verdict.CLEAN, usedChain = true), creatingTx = runestone)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals("May carry runes", check.reason)
        assertTrue(check.open.isEmpty())
        assertEquals(InscriptionCheck.Source.BUILT_IN, check.source)
        assertFalse(check.runes)
    }

    @Test fun cleanTraceWithDunestoneIsUnchecked() {
        val dunestone = tx(hash(3), byteArrayOf(0x6a, 0x01, 0x44))
        val check = decide(null, trace(Verdict.CLEAN, usedChain = true), creatingTx = dunestone)
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals("May carry runes", check.reason)
    }

    @Test fun runestoneDoesNotTouchANonCleanTraceOrACleanTraceWithoutOne() {
        val runestone = tx(hash(3), byteArrayOf(0x6a, 0x5d, 0x00))
        val unchecked = decide(null, trace(Verdict.UNCHECKED, usedChain = true), creatingTx = runestone)
        assertEquals(Verdict.UNCHECKED, unchecked.verdict)
        assertEquals("Lookup budget used up; use Check deeper", unchecked.reason)
        assertEquals(Verdict.CLEAN, decide(null, trace(Verdict.CLEAN, usedChain = true), creatingTx = plain(hash(3))).verdict)
    }

    @Test fun unindexedAnswerCountsAsNone() {
        val check = decide(answer(indexed = false), trace(Verdict.CLEAN, usedChain = true))
        assertEquals(InscriptionCheck.Source.BUILT_IN, check.source)
    }

    @Test fun indexerSourceIsRecorded() {
        val check = CheckPolicy.decide(txid, 0, answer(), InscriptionCheck.Source.YOUR_OWN, trace(Verdict.CLEAN, usedChain = true), emptyMap(), null, blockHash)
        assertEquals(InscriptionCheck.Source.YOUR_OWN, check.source)
        assertEquals("$txid:0", check.outpoint)
    }

    /** Review C2: a trace that found an inscription but stopped short keeps its reason and open pieces, and is never complete. */
    @Test fun incompleteInscribedKeepsReasonAndOpen() {
        val open = Piece(hash(5), 0, UnitRange(0, 100), 200)
        val found = Found(id, 0, null, hash(7), 2_400_000, 1L)
        val cut = TraceResult(Verdict.INSCRIBED, listOf(found), "Lookup budget used up; use Check deeper", listOf(open), 200, true)
        val check = decide(null, cut)
        assertEquals(Verdict.INSCRIBED, check.verdict)
        assertEquals("Lookup budget used up; use Check deeper", check.reason)
        assertEquals(listOf(open), check.open)
        assertFalse(CheckPolicy.complete(check))
    }

    /** Review I3: an inscription the trace finds that the indexer did not list holds the coin. */
    @Test fun indexerMissedAnInscription() {
        val other = hash(8) + "i0"
        val traced = listOf(Found(id, 0, null, hash(7), 1, 1), Found(other, 500, null, hash(8), 1, 1))
        val check = decide(answer(id), trace(Verdict.INSCRIBED, traced, true), mapOf(id to reveal))
        assertEquals(Verdict.UNCHECKED, check.verdict)
        assertEquals("Indexer and chain disagree: chain shows $other", check.reason)
    }

    @Test fun complete() {
        fun check(verdict: Verdict, runes: Boolean = false, reason: String? = null, open: List<Piece> = emptyList()) =
            InscriptionCheck(txid, 0, verdict, InscriptionCheck.Source.BUILT_IN, runes, emptyList(), reason, blockHash, open)
        assertFalse(CheckPolicy.complete(null))
        assertTrue(CheckPolicy.complete(check(Verdict.CLEAN)))
        assertTrue(CheckPolicy.complete(check(Verdict.INSCRIBED)))
        assertFalse(CheckPolicy.complete(check(Verdict.UNCHECKED)))
        assertFalse(CheckPolicy.complete(check(Verdict.CLEAN, runes = true)))
        assertFalse(CheckPolicy.complete(check(Verdict.CLEAN, reason = "x")))
        assertFalse(CheckPolicy.complete(check(Verdict.INSCRIBED, reason = "x")))
        assertFalse(CheckPolicy.complete(check(Verdict.INSCRIBED, open = listOf(Piece(hash(5), 0, UnitRange(0, 1), 0)))))
    }

    @Test fun checkRoundTripsThroughJson() {
        val original = InscriptionCheck(
            txid, 1, Verdict.INSCRIBED, InscriptionCheck.Source.YOUR_OWN, true,
            listOf(Found(id, 0, 42L, hash(7), 2_400_000, 1_600_000_000L), Found(hash(8) + "i0", -1, null, hash(8), 5, 6)),
            "Lookup budget used up; use Check deeper", blockHash,
            listOf(Piece(hash(5), 0, UnitRange(10, 20), 3), Piece(hash(6), Piece.FEE, UnitRange(0, 7), 99))
        )
        val json = Json.encodeToString(InscriptionCheck.serializer(), original)
        assertEquals(original, Json.decodeFromString(InscriptionCheck.serializer(), json))
    }

    @Test fun unitRangeStillValidatesWhenDecoded() {
        assertEquals(UnitRange(5, 6), Json.decodeFromString(UnitRange.serializer(), """{"start":5,"end":6}"""))
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(UnitRange.serializer(), """{"start":5,"end":5}""") }
    }
}
