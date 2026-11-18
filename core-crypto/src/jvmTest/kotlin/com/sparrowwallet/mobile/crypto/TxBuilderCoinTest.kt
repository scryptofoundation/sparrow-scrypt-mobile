package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Per-coin fee floor, fee size and dust rules in TxBuilder. */
class TxBuilderCoinTest {
    private val master = HDKey.fromSeed(Bip39.seed(CoinVectors.file.mnemonic, ""))
    private val doge = ChainParams.of(Coin.DOGECOIN, Network.MAINNET)
    private val dingo = ChainParams.of(Coin.DINGOCOIN, Network.MAINNET)

    private fun input(value: Long, path: String = "m/44'/3'/0'/0/0") =
        SpendableInput("11".repeat(32), 0, value, master.derivePath(path), ScriptType.P2PKH)

    private val p2pkh = Scripts.p2pkhOutput(master.derivePath("m/44'/3'/0'/0/1").pubKey)

    @Test fun dogeChangeBelowDustGoesToFee() {
        val vsize = TxBuilder.estimateVsize(listOf(ScriptType.P2PKH), listOf(25, 25))
        val amount = 100_000_000L
        val built = TxBuilder.build(listOf(input(amount + vsize * 1000 + 999_999)), p2pkh, amount, 1000, p2pkh, params = doge, chainTip = null)
        assertEquals(1, built.tx.outputs.size)
        assertEquals(0, built.change)
    }

    @Test fun feeRateBelowCoinFloorIsRefused() {
        val e = assertFailsWith<IllegalArgumentException> {
            TxBuilder.build(listOf(input(500_000_000)), p2pkh, 100_000_000, 999, p2pkh, params = doge, chainTip = null)
        }
        assertEquals("Fee rate must be at least 1000 sat/vB for Dogecoin", e.message)
    }

    @Test fun dingoFeeRoundsSizeUpToWholeKilobytes() {
        val built = TxBuilder.build(listOf(input(5_000_000_000)), p2pkh, 100_000_000, 100_000, p2pkh, params = dingo, chainTip = null)
        assertEquals(1000L * 100_000, built.fee) // one input, two outputs: under 1000 vbytes, charged as 1000
    }

    @Test fun paidFeeRateIsTheFeeOverTheSize() {
        // Dingocoin charges whole kilobytes, so what is paid per vbyte is above the rate asked for
        val dingoBuild = TxBuilder.buildUnsigned(listOf(input(5_000_000_000)), p2pkh, 100_000_000, 100_000, p2pkh, params = dingo)
        val dingoSize = TxBuilder.estimateVsize(listOf(ScriptType.P2PKH), listOf(25, 25))
        assertEquals(dingoBuild.fee / dingoSize, dingoBuild.paidFeeRate)
        assertTrue(dingoBuild.paidFeeRate > 100_000)
        // elsewhere it is the rate asked for
        val dogeBuild = TxBuilder.buildUnsigned(listOf(input(500_000_000)), p2pkh, 100_000_000, 1000, p2pkh, params = doge)
        assertEquals(1000, dogeBuild.paidFeeRate)
    }

    @Test fun legacyInputCountsItsFullSize() {
        assertEquals(10L + 148 + 2 * 34, TxBuilder.estimateVsize(listOf(ScriptType.P2PKH), listOf(25, 25)))
    }

    @Test fun builtDogeTxSignsWithAScriptSig() {
        val built = TxBuilder.build(listOf(input(500_000_000)), p2pkh, 100_000_000, 1000, p2pkh, params = doge, chainTip = null)
        assertTrue(built.tx.inputs[0].witness.isEmpty())
        assertTrue(built.tx.inputs[0].scriptSig.isNotEmpty())
    }

    @Test fun segwitInputOnALegacyOnlyCoinIsRefused() {
        val segwitInput = SpendableInput("11".repeat(32), 0, 500_000_000, master.derivePath("m/44'/3'/0'/0/0"), ScriptType.P2WPKH)
        val e = assertFailsWith<IllegalArgumentException> {
            TxBuilder.build(listOf(segwitInput), p2pkh, 100_000_000, 1000, p2pkh, params = doge, chainTip = null)
        }
        assertEquals("Dogecoin has no P2WPKH inputs", e.message)
    }

    @Test fun dogeInsufficientFundsNamesSats() {
        val e = assertFailsWith<IllegalArgumentException> {
            TxBuilder.build(listOf(input(100_100_000)), p2pkh, 100_000_000, 1000, p2pkh, params = doge, chainTip = null)
        }
        assertTrue(e.message!!.endsWith(" sats, have 100100000 spendable"), e.message)
    }

    @Test fun dingoFeeCrossingAKilobyteIsChargedForTwo() {
        val inputs = (0 until 7).map { input(1_000_000_000, "m/44'/3'/0'/0/$it") }
        assertTrue(TxBuilder.estimateVsize(List(7) { ScriptType.P2PKH }, listOf(25, 25)) in 1001..1999)
        val built = TxBuilder.build(inputs, p2pkh, 6_700_000_000, 100_000, p2pkh, params = dingo, chainTip = null)
        assertEquals(2000L * 100_000, built.fee)
    }

    @Test fun dogeAmountBelowDustIsRefused() {
        val e = assertFailsWith<IllegalArgumentException> {
            TxBuilder.build(listOf(input(500_000_000)), p2pkh, 999_999, 1000, p2pkh, params = doge, chainTip = null)
        }
        assertEquals("Amount is below the 1000000 sats dust limit", e.message)
    }

    @Test fun dogeSendUsesLegacyChangeAndCoinFloor() {
        val change = Scripts.outputScript(master.derivePath("m/44'/3'/0'/1/0").pubKey, ScriptType.P2PKH)
        assertEquals(25, change.size)
        assertTrue(change.toHex().startsWith("76a914"))
        val inputs = listOf(input(300_000_000, "m/44'/3'/0'/0/0"), input(300_000_000, "m/44'/3'/0'/0/1"))
        val built = TxBuilder.build(inputs, p2pkh, 400_000_000, 1000, change, params = doge, chainTip = null)
        val vsize = TxBuilder.estimateVsize(List(2) { ScriptType.P2PKH }, listOf(25, 25))
        assertTrue(built.fee >= vsize * 1000)
        assertTrue(built.tx.outputs.any { it.script.contentEquals(change) })
        val raw = built.tx.serialize()
        assertTrue(raw[4] != 0.toByte(), "no segwit marker")
    }
}
