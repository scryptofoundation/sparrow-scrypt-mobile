package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelope
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.hash
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.reveal
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.script
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.spend
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.tx
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of drongo's MinedCoinTest, case for case. drongo runs most cases on Litecoin regtest (no inscription cutoff, a
 * halving every 150 blocks); the phone has no regtest network, so those cases build the tracer with the same rules.
 */
class MinedCoinTest {
    private val chain = FakeChainSource()

    private fun tracer(coin: Coin, network: Network) = InscriptionTracer(coin, network, chain, KnownResults.NONE, TxCache())

    /** Litecoin on drongo's regtest network: no inscription cutoff, halving every 150 blocks. */
    private fun litecoinRegtest() =
        InscriptionTracer(chain, KnownResults.NONE, TxCache(), firstInscriptionHeight = 0, subsidy = { height -> regtestSubsidy(height) })

    private fun regtestSubsidy(height: Int): Long {
        val halvings = height / 150
        return if(halvings >= 64) 0L else REWARD shr halvings
    }

    private fun trace(tracer: InscriptionTracer, txid: String, vout: Int, budget: Int = InscriptionTracer.DEFAULT_BUDGET, walkBlocks: Boolean = false) =
        runBlocking { tracer.trace(txid, vout, emptyList(), budget, walkBlocks) }

    /** A coinbase with the given script byte, paying the outputs. */
    private fun coinbaseTx(n: Int, vararg outputs: Long): RawTx.Parsed {
        val tx = tx(listOf(hash(0) to -1), outputs.toList(), scriptSigs = listOf(byteArrayOf(0x01, n.toByte())))
        assertTrue(tx.inputs.size == 1 && tx.inputs.first().isCoinbase)
        return tx
    }

    private fun coinbase(vararg outputs: Long): RawTx.Parsed = chain.add(coinbaseTx(0x64, *outputs), HEIGHT)

    /** Transaction A: two 5,000 inputs (the second revealing an inscription) and one 5,000 output, so the inscription goes to its fee. */
    private fun feeBurn(): RawTx.Parsed {
        val p1 = chain.add(coinbaseLike(1), 50)
        val p2 = chain.add(coinbaseLike(2), 50)
        return chain.add(reveal(listOf(p1.txid to 0, p2.txid to 0), listOf(null, script(envelope("burn"))), 5_000), HEIGHT)
    }

    /** An earlier coinbase paying exactly the reward, its output 0 (5,000) wholly in the reward part, so it traces clean. */
    private fun coinbaseLike(n: Int): RawTx.Parsed = coinbaseTx(n, 5_000, REWARD - 5_000)

    private fun esploraFees(a: RawTx.Parsed) {
        chain.fees[HEIGHT] = listOf(BlockFee(a.txid, 5_000))
    }

    @Test
    fun subsidyPartClean() {
        val cb = coinbase(10_000, REWARD - 10_000 + 1_000)
        val result = trace(litecoinRegtest(), cb.txid, 0)
        assertEquals(Verdict.CLEAN, result.verdict)
        assertEquals(1, result.lookups)
        assertEquals(0, chain.blockFeeCalls)
    }

    @Test
    fun feeBurnFound() {
        val a = feeBurn()
        esploraFees(a)
        val cb = coinbase(REWARD + 5_000)
        val result = trace(litecoinRegtest(), cb.txid, 0)
        assertEquals(Verdict.INSCRIBED, result.verdict, result.reason)
        assertEquals(a.txid + "i0", result.found.first().inscriptionId)
        assertEquals(REWARD, result.found.first().offset)
    }

    @Test
    fun electrumFeePartUnchecked() {
        feeBurn()
        val cb = coinbase(REWARD, 5_000)
        val result = trace(litecoinRegtest(), cb.txid, 1)
        assertEquals(Verdict.UNCHECKED, result.verdict)
        assertEquals("Mined coin fees need Check deeper", result.reason)
        assertEquals(Verdict.CLEAN, trace(litecoinRegtest(), cb.txid, 0).verdict)
    }

    @Test
    fun electrumWalk() {
        val a = feeBurn()
        val cb = coinbase(REWARD + 5_000)
        chain.positions[HEIGHT] = listOf(cb.txid, a.txid)
        val result = trace(litecoinRegtest(), cb.txid, 0, InscriptionTracer.DEEPER_BUDGET, true)
        assertEquals(Verdict.INSCRIBED, result.verdict, result.reason)
        assertEquals(REWARD, result.found.first().offset)
    }

    /** Review C3: a coinbase whose height the server did not give must not be read against the height-0 reward. */
    @Test
    fun unknownHeightCoinbaseIsUnchecked() {
        val tx = coinbaseTx(0x65, 1_000_000_000L)
        chain.add(tx, 0)
        val result = trace(tracer(Coin.LITECOIN, Network.MAINNET), tx.txid, 0)
        assertEquals(Verdict.UNCHECKED, result.verdict)
        assertEquals("Mined coin height unknown", result.reason)
    }

    /** Review C3: a height one off at a halving must not double the reward; the smaller of the neighbours' rewards is used. */
    @Test
    fun nearHalvingUsesTheSmallerReward() {
        val tx = coinbaseTx(0x66, 4_000_000_000L, 1_000_000_000L)
        chain.add(tx, 149)
        //Reward at 149 is 50 LTC, at 150 25 LTC: units past 25 LTC need the block's fees, which this chain does not have
        val result = trace(litecoinRegtest(), tx.txid, 0)
        assertEquals(Verdict.UNCHECKED, result.verdict)
    }

    /** Review I2: a coinbase returned without its block hash still gets its block's fees, by the hash at its height. */
    @Test
    fun feesFoundByTheHashAtTheHeight() {
        val a = feeBurn()
        esploraFees(a)
        val tx = coinbaseTx(0x64, REWARD + 5_000)
        chain.addWithoutHash(tx, HEIGHT)
        chain.hashes[HEIGHT] = FakeChainSource.heightHash(HEIGHT)
        val result = trace(litecoinRegtest(), tx.txid, 0)
        assertEquals(Verdict.INSCRIBED, result.verdict, result.reason)
    }

    @Test
    fun walkUnsupportedStaysUnchecked() {
        //A server that cannot list block positions must not read as a block with no fees
        feeBurn()
        val cb = coinbase(REWARD + 5_000)
        val result = trace(litecoinRegtest(), cb.txid, 0, InscriptionTracer.DEEPER_BUDGET, true)
        assertEquals(Verdict.UNCHECKED, result.verdict)
        assertEquals("Mined coin fees need Check deeper", result.reason)

        val noReward = trace(tracer(Coin.PEPECOIN, Network.TESTNET), cb.txid, 0, InscriptionTracer.DEEPER_BUDGET, true)
        assertEquals(Verdict.UNCHECKED, noReward.verdict)
    }

    @Test
    fun doesNotAddUp() {
        val a = feeBurn()
        esploraFees(a)
        val cb = coinbase(REWARD + 5_001)
        val result = trace(litecoinRegtest(), cb.txid, 0)
        assertEquals(Verdict.UNCHECKED, result.verdict)
        assertEquals("Mined coin does not add up", result.reason)
    }

    @Test
    fun rewardFromFees() {
        val a = chain.add(spend(listOf(hash(1) to 0), 1_000), HEIGHT)
        chain.fees[HEIGHT] = listOf(BlockFee(a.txid, 5_000))
        val cb = coinbase(1_000_000, 5_000)
        val result = trace(tracer(Coin.PEPECOIN, Network.TESTNET), cb.txid, 0)
        assertEquals(Verdict.CLEAN, result.verdict, result.reason)
    }

    @Test
    fun noRewardNoFees() {
        val cb = coinbase(1_000_000, 5_000)
        val result = trace(tracer(Coin.PEPECOIN, Network.TESTNET), cb.txid, 0)
        assertEquals(Verdict.UNCHECKED, result.verdict)
        assertEquals("Mined coin fees need Check deeper", result.reason)
    }

    companion object {
        private const val HEIGHT = 100
        private const val REWARD = 5_000_000_000L
    }
}
