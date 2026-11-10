package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.PLAIN
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.body
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.doginalsSteps
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelope
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelopeWithPointer
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.hash
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.reveal
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.script
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.spend
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.tx
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of drongo's InscriptionTracerTest, case for case, plus the phone's own cases at the end. */
class InscriptionTracerTest {
    private val chain = FakeChainSource()
    private val known = HashMap<String, KnownOutput>()
    private val knownResults = KnownResults { txid, vout -> known["$txid:$vout"] }

    private fun litecoin() = InscriptionTracer(Coin.LITECOIN, Network.MAINNET, chain, knownResults, TxCache())

    /** Dogecoin on drongo's regtest network: no inscription cutoff, no exact reward. */
    private fun dogecoinRegtest() =
        InscriptionTracer(chain, knownResults, TxCache(), firstInscriptionHeight = 0, subsidy = { null })

    /** A plain transaction at a height below Litecoin's first inscription, paying one output of the value. */
    private fun old(n: Int, value: Long): RawTx.Parsed = chain.add(spend(listOf(hash(n) to 0), value), 2_000_000)

    /**
     * A HogEx: input 0 spends output 0 of the previous one (then any peg-ins), output 0 (the MWEB pool) pays OP_8 and a 32
     * byte push, the other outputs are peg-outs.
     */
    private fun hogEx(pegIns: List<Pair<String, Int>> = emptyList(), pegOut: Long = 10_000L): RawTx.Parsed {
        val hogAddr = ByteArray(34)
        hogAddr[0] = 0x58
        hogAddr[1] = 0x20
        return tx(listOf(hash(1) to 0) + pegIns, listOf(1_000_000L, pegOut), outputScripts = listOf(hogAddr, PLAIN))
    }

    private fun trace(tracer: InscriptionTracer, txid: String, vout: Int, resume: List<Piece> = emptyList(), budget: Int = InscriptionTracer.DEFAULT_BUDGET, walkBlocks: Boolean = false) =
        runBlocking { tracer.trace(txid, vout, resume, budget, walkBlocks) }

    @Test
    fun cleanBelowCutoff() {
        val parent = old(1, 10_000)
        val coin = chain.add(spend(listOf(parent.txid to 0), 9_000), 2_500_000)
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.CLEAN, result.verdict)
        assertEquals(2, result.lookups)
        assertTrue(result.usedChain)
    }

    @Test
    fun foundInscription() {
        val parent = old(1, 10_000)
        val reveal = chain.add(reveal(listOf(parent.txid to 0), listOf(script(envelope("hi"))), 10_000), 2_500_000)
        val coin = chain.add(spend(listOf(reveal.txid to 0), 10_000), 2_500_001)
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.INSCRIBED, result.verdict)
        assertEquals(1, result.found.size)
        val found = result.found.first()
        assertEquals(reveal.txid + "i0", found.inscriptionId)
        assertEquals(0, found.offset)
        assertNull(found.number)
        assertEquals(reveal.txid, found.mintTxid)
        assertEquals(2_500_000, found.mintHeight)
    }

    @Test
    fun inscriptionElsewhere() {
        val parent = old(1, 15_000)
        val reveal = chain.add(reveal(listOf(parent.txid to 0), listOf(script(envelopeWithPointer("hi", 12_000))), 10_000, 5_000), 2_500_000)
        val coin = chain.add(spend(listOf(reveal.txid to 0), 10_000), 2_500_001)
        assertEquals(Verdict.CLEAN, trace(litecoin(), coin.txid, 0).verdict)
        assertEquals(Verdict.INSCRIBED, trace(litecoin(), reveal.txid, 1).verdict)
    }

    @Test
    fun foundOffsetIsInTheCoin() {
        //Two 5,000 inputs, the second a reveal's output carrying an inscription at its offset 0: merged, it sits at 5,000
        val p1 = old(1, 5_000)
        val p2 = old(2, 5_000)
        val reveal = chain.add(reveal(listOf(p2.txid to 0), listOf(script(envelope("x"))), 5_000), 2_500_000)
        val coin = chain.add(spend(listOf(p1.txid to 0, reveal.txid to 0), 10_000), 2_500_001)
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(5_000, result.found.first().offset)
    }

    @Test
    fun budgetThenResume() {
        var previous = old(1, 10_000)
        for(i in 0 until 5) {
            previous = chain.add(spend(listOf(previous.txid to 0), 10_000), 2_500_000 + i)
        }
        val cut = trace(litecoin(), previous.txid, 0, budget = 2)
        assertEquals(Verdict.UNCHECKED, cut.verdict)
        assertEquals("Lookup budget used up; use Check deeper", cut.reason)
        assertFalse(cut.open.isEmpty())

        val resumed = trace(litecoin(), previous.txid, 0, cut.open, 10)
        assertEquals(Verdict.CLEAN, resumed.verdict)
        assertTrue(resumed.open.isEmpty())
    }

    @Test
    fun knownResultsUsed() {
        val walletParent = chain.add(spend(listOf(hash(1) to 0), 10_000), 2_500_000, false)
        known[walletParent.txid + ":0"] = KnownOutput(Verdict.CLEAN, emptyList())
        val coin = chain.add(spend(listOf(walletParent.txid to 0), 9_000), 2_500_001)
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.CLEAN, result.verdict)
        assertEquals(1, result.lookups)
    }

    @Test
    fun knownInscribedCarriesOver() {
        val walletParent = chain.add(spend(listOf(hash(1) to 0), 10_000), 2_500_000, false)
        val inscription = Found(hash(7) + "i0", 3_000, null, hash(7), 2_400_000, 1L)
        known[walletParent.txid + ":0"] = KnownOutput(Verdict.INSCRIBED, listOf(inscription))
        val coin = chain.add(spend(listOf(walletParent.txid to 0), 4_000, 6_000), 2_500_001)
        val first = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.INSCRIBED, first.verdict)
        assertEquals(3_000, first.found.first().offset)
        assertEquals(Verdict.CLEAN, trace(litecoin(), coin.txid, 1).verdict)
    }

    /** A coin that came out of MWEB has no history to follow: the piece ends clean at the HogEx (the owner's rule, 2026-11-10). */
    @Test
    fun hogExIsClean() {
        val hogEx = hogEx()
        assertTrue(InscriptionTracer.isHogEx(hogEx), "fixture is a HogEx")
        chain.add(hogEx, 2_500_000)
        val coin = chain.add(spend(listOf(hogEx.txid to 1), 9_000), 2_500_001)
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.CLEAN, result.verdict)
        assertNull(result.reason)
        assertTrue(result.open.isEmpty())
        assertEquals(2, result.lookups)
    }

    /**
     * An inscription moved into MWEB by a peg-in and a peg-out paying the wallet coin: the trace stops at the HogEx and never
     * reaches the inscription, so the coin is clean (drongo's inscriptionThroughMwebIsClean).
     */
    @Test
    fun inscriptionThroughMwebIsClean() {
        val parent = old(1, 10_000)
        val reveal = chain.add(reveal(listOf(parent.txid to 0), listOf(script(envelope("in"))), 10_000), 2_500_000)
        val pegInScript = ByteArray(34)
        pegInScript[0] = 0x59
        pegInScript[1] = 0x20
        val pegIn = chain.add(tx(listOf(reveal.txid to 0), listOf(9_000L), outputScripts = listOf(pegInScript)), 2_500_001)
        val hogEx = chain.add(hogEx(listOf(pegIn.txid to 0), 9_000), 2_500_001)
        assertTrue(InscriptionTracer.isHogEx(hogEx), "fixture is a HogEx")
        val coin = chain.add(spend(listOf(hogEx.txid to 1), 9_000), 2_500_002)
        assertEquals(Verdict.INSCRIBED, trace(litecoin(), pegIn.txid, 0).verdict, "the inscription went into MWEB")
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.CLEAN, result.verdict, result.reason)
        assertTrue(result.found.isEmpty())
        assertEquals(2, result.lookups, "the trace stops at the HogEx")
    }

    /** Review C1: a trace that hit a dead end leaves nothing to resume, so a later resume cannot skip the dead end. */
    @Test
    fun deadEndIsNotResumable() {
        val coin = deadEndChain()
        val cut = trace(litecoin(), coin.txid, 0, budget = 4)
        assertEquals(Verdict.UNCHECKED, cut.verdict)
        assertTrue(cut.open.isEmpty(), "a dead end leaves nothing to resume: ${cut.open}")
    }

    /** A coin spending a coinbase the server gave no height for (a dead end) and the end of a five transaction chain above the cutoff. */
    private fun deadEndChain(): RawTx.Parsed {
        val unknownHeight = chain.add(tx(listOf(hash(0) to -1), listOf(10_000L), scriptSigs = listOf(byteArrayOf(0x01, 0x65))), 0)
        assertTrue(unknownHeight.inputs.first().isCoinbase)
        var previous = old(2, 10_000)
        for(i in 0 until 5) {
            previous = chain.add(spend(listOf(previous.txid to 0), 10_000), 2_500_001 + i)
        }
        return chain.add(spend(listOf(unknownHeight.txid to 0, previous.txid to 0), 20_000), 2_500_010)
    }

    /** Review I4: the inscription of a last Doginals step sits on the first unit of the flow, output 1 when output 0 holds nothing. */
    @Test
    fun doginalsFirstUnitAfterAnEmptyOutput() {
        val funding = chain.add(spend(listOf(hash(1) to 0), 100_000_000_000L), 100)
        val step = doginalsSteps(body(600), funding.txid).first()
        val landed = tx(
            listOf(step.inputs.first().prevTxid to step.inputs.first().prevVout.toInt()), listOf(0L, 100_000L),
            scriptSigs = listOf(step.scriptSigs.first()), version = 1, outputScripts = listOf(byteArrayOf(0x6a), PLAIN)
        )
        chain.add(landed, 101)
        val result = trace(dogecoinRegtest(), landed.txid, 1)
        assertEquals(Verdict.INSCRIBED, result.verdict, result.reason)
        assertEquals(landed.txid + "i0", result.found.first().inscriptionId)
    }

    @Test
    fun unconfirmedNotCutoff() {
        val parent = old(1, 10_000)
        val reveal = chain.add(reveal(listOf(parent.txid to 0), listOf(script(envelope("u"))), 10_000), 0)
        val coin = chain.add(spend(listOf(reveal.txid to 0), 10_000), 0)
        assertEquals(Verdict.INSCRIBED, trace(litecoin(), coin.txid, 0).verdict)
    }

    @Test
    fun serverError() {
        val parent = old(1, 10_000)
        val coin = chain.add(spend(listOf(parent.txid to 0), 9_000), 2_500_000)
        chain.failing.add(parent.txid)
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.UNCHECKED, result.verdict)
        assertEquals("Server error, will retry next sync: connection reset", result.reason)
        assertFalse(result.open.isEmpty())
    }

    @Test
    fun cacheSavesLookups() {
        val parent = old(1, 10_000)
        val coin = chain.add(spend(listOf(parent.txid to 0), 9_000), 2_500_000)
        val tracer = litecoin()
        assertEquals(2, trace(tracer, coin.txid, 0).lookups)
        val again = trace(tracer, coin.txid, 0)
        assertEquals(Verdict.CLEAN, again.verdict)
        assertEquals(0, again.lookups)
    }

    @Test
    fun doginalsFound() {
        val funding = chain.add(spend(listOf(hash(1) to 0), 100_000_000_000L), 100)
        val steps = doginalsSteps(body(4_000), funding.txid)
        assertTrue(steps.size > 1, "a chain of several steps")
        steps.forEachIndexed { i, step -> chain.add(step, 101 + i) }
        val last = steps.last()
        val result = trace(dogecoinRegtest(), last.txid, 0)
        assertEquals(Verdict.INSCRIBED, result.verdict)
        val found = result.found.first()
        assertEquals(steps.first().txid + "i0", found.inscriptionId)
        assertEquals(0, found.offset)
        assertEquals(last.txid, found.mintTxid)
    }

    // ---- Cases the phone adds ----

    /** A trace cut off by budget after meeting a dead end, resumed with what it left open, meets the dead end again and is not clean. */
    @Test
    fun resumeKeepsDeadEnds() {
        val coin = deadEndChain()
        val cut = trace(litecoin(), coin.txid, 0, budget = 4)
        assertEquals(Verdict.UNCHECKED, cut.verdict)
        assertEquals("Lookup budget used up; use Check deeper", cut.reason)
        assertTrue(cut.open.isEmpty())

        val resumed = trace(litecoin(), coin.txid, 0, cut.open)
        assertEquals(Verdict.UNCHECKED, resumed.verdict)
        assertEquals("Mined coin height unknown", resumed.reason)
    }

    /** The piece on an output KnownResults calls clean costs no lookup of its own: its parents are never fetched. */
    @Test
    fun knownOutputEndsTheTraceInOneHop() {
        //The parent is on the server and its own input (hash 1) is not: tracing past it would fail
        val parent = chain.add(spend(listOf(hash(1) to 0), 10_000), 2_500_000)
        known[parent.txid + ":0"] = KnownOutput(Verdict.CLEAN, emptyList())
        val coin = chain.add(spend(listOf(parent.txid to 0), 9_000), 2_500_001)
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.CLEAN, result.verdict, result.reason)
        //The coin, and the parent for its value (as drongo does); nothing for the parent's own piece
        assertEquals(2, result.lookups)
        assertEquals(2, chain.transactionCalls)
    }

    /**
     * A transaction with the MWEB flag is a HogEx when its extension is the single byte 0, as drongo reads it; one that
     * carries an MWEB transaction (a peg-in) is traced through like any other. A HogEx ends its piece clean.
     */
    @Test
    fun mwebFlaggedHogExIsCleanAndPegInIsTracedThrough() {
        //Output 0 pays an ordinary script: only the flag and the extension can make these a HogEx
        val hogParent = old(1, 10_000)
        val hogEx = tx(listOf(hogParent.txid to 0), listOf(10_000L), mwebExtension = byteArrayOf(0))
        assertTrue(hogEx.mweb && hogEx.mwebNullExtension, "fixture has the null MWEB extension")
        assertTrue(InscriptionTracer.isHogEx(hogEx))
        chain.add(hogEx, 2_500_000)
        val hogCoin = chain.add(spend(listOf(hogEx.txid to 0), 9_000), 2_500_001)
        val stopped = trace(litecoin(), hogCoin.txid, 0)
        assertEquals(Verdict.CLEAN, stopped.verdict, stopped.reason)
        //The coin and the HogEx: the HogEx's own input is never fetched
        assertEquals(2, stopped.lookups)

        val pegParent = old(2, 10_000)
        val pegIn = tx(listOf(pegParent.txid to 0), listOf(10_000L), mwebExtension = byteArrayOf(1, 2, 3))
        assertTrue(pegIn.mweb && !pegIn.mwebNullExtension, "fixture carries an MWEB transaction")
        assertFalse(InscriptionTracer.isHogEx(pegIn))
        chain.add(pegIn, 2_500_000)
        val pegCoin = chain.add(spend(listOf(pegIn.txid to 0), 9_000), 2_500_001)
        val traced = trace(litecoin(), pegCoin.txid, 0)
        assertEquals(Verdict.CLEAN, traced.verdict, traced.reason)
        assertEquals(3, traced.lookups)
    }

    /** A cancelled trace throws the cancellation, on the coin's own fetch and on a parent's, and returns no result. */
    @Test
    fun cancellationIsThrown() {
        val parent = old(1, 10_000)
        val coin = chain.add(spend(listOf(parent.txid to 0), 9_000), 2_500_000)
        for(cancelAt in listOf(coin.txid, parent.txid)) {
            val source = object : ChainSource by chain {
                override suspend fun transaction(txid: String): ChainTx {
                    if(txid == cancelAt) {
                        throw CancellationException("cancelled")
                    }
                    return chain.transaction(txid)
                }
            }
            val tracer = InscriptionTracer(Coin.LITECOIN, Network.MAINNET, source, knownResults, TxCache())
            assertFailsWith<CancellationException>("cancelled at $cancelAt") { trace(tracer, coin.txid, 0) }
        }
    }

    /** An input with no ordinary outpoint (an MWEB id on the 0xFFFFFFFF index) stops its piece as MWEB. */
    @Test
    fun mwebInputIsUnchecked() {
        val parent = old(1, 10_000)
        val mixed = chain.add(spend(listOf(parent.txid to 0, hash(5) to -1), 15_000), 2_500_000)
        val coin = chain.add(spend(listOf(mixed.txid to 0), 14_000), 2_500_001)
        val result = trace(litecoin(), coin.txid, 0)
        assertEquals(Verdict.UNCHECKED, result.verdict)
        assertEquals("Came through MWEB", result.reason)
    }
}
