package com.sparrowwallet.mobile

import kotlin.test.assertNull
import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.TxBuilder
import com.sparrowwallet.mobile.crypto.toHex
import com.sparrowwallet.mobile.electrum.Utxo
import com.sparrowwallet.mobile.electrum.WalletScanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletParamsTest {
    @Test
    fun dogeEstimateIsNotClampedToLitecoinsCeiling() {
        assertEquals(50_600, clampFeeRate(Coin.DOGECOIN, 50_600.0))
    }

    @Test
    fun missingEstimateFallsBackToTheCoinsFloor() {
        assertEquals(100_000, clampFeeRate(Coin.DINGOCOIN, null))
        assertEquals(1000, clampFeeRate(Coin.DOGECOIN, 0.0))
        assertEquals(10, clampFeeRate(Coin.LITECOIN, null))
    }

    @Test
    fun estimateBelowTheFloorIsRaised() {
        assertEquals(1000, clampFeeRate(Coin.PEPECOIN, 1.2))
        assertEquals(2, clampFeeRate(Coin.LITECOIN, 1.2))
    }

    @Test
    fun litecoinCeilingIsUnchanged() {
        assertEquals(10_000, clampFeeRate(Coin.LITECOIN, 50_000.0))
        assertEquals(10_000_000, clampFeeRate(Coin.DINGOCOIN, 1e9))
    }

    @Test
    fun feeUnitNamesTheSubunit() {
        assertEquals("lit/vB", feeUnit(Coin.LITECOIN))
        assertEquals("sat/vB", feeUnit(Coin.JUNKCOIN))
    }

    @Test
    fun scriptTypeNamesAreRead() {
        assertEquals(ScriptType.P2PKH, walletScriptType("P2PKH"))
        assertFailsWith<IllegalArgumentException> { walletScriptType("MWEB") }
    }

    @Test
    fun externalSigningCoversEveryPublicWallet() {
        assertTrue(externalSigningSupported(Coin.LITECOIN, "P2WPKH"))
        assertTrue(externalSigningSupported(Coin.BELLSCOIN, "P2WPKH"))
        assertTrue(externalSigningSupported(Coin.LITECOIN, "P2SH_P2WPKH"))
        assertTrue(externalSigningSupported(Coin.DOGECOIN, "P2PKH"))
        assertFalse(externalSigningSupported(Coin.LITECOIN, "MWEB"))
        assertFalse(externalSigningSupported(Coin.DOGECOIN, "P2WPKH")) // not a script type the coin has
    }

    @Test
    fun dogeSendAssemblesLegacyChangeAtTheCoinRate() {
        val master = HDKey.fromSeed(Bip39.seed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
        val account = master.derivePath("m/44'/3'/0'")
        val doge = ChainParams.of(Coin.DOGECOIN, Network.MAINNET)
        val scan = WalletScanner.UtxoScan(listOf(
            WalletScanner.ChainUtxo(Utxo("11".repeat(32), 0, 100, 300_000_000), 0, 0),
            WalletScanner.ChainUtxo(Utxo("22".repeat(32), 1, 100, 300_000_000), 0, 3),
            WalletScanner.ChainUtxo(Utxo("33".repeat(32), 0, 200, 900_000_000), 0, 4) // 1 confirmation: not spendable
        ), nextChangeIndex = 2)
        val (inputs, change) = spendables(scan, tip = 200, account = account, scriptType = ScriptType.P2PKH, minConfirmations = 6)
        assertEquals(2, inputs.size)
        assertTrue(inputs.all { it.scriptType == ScriptType.P2PKH })
        val expectedChange = Scripts.outputScript(account.deriveChild(1).deriveChild(2).pubKey, ScriptType.P2PKH)
        assertEquals(expectedChange.toHex(), change.toHex())
        assertTrue(change.toHex().startsWith("76a914"))

        val rate = clampFeeRate(Coin.DOGECOIN, 50_600.0)
        val dest = Scripts.outputScript(master.derivePath("m/44'/3'/1'/0/0").pubKey, ScriptType.P2PKH)
        val built = TxBuilder.build(inputs, dest, 400_000_000, rate, change, false, doge)
        assertTrue(built.fee >= TxBuilder.estimateVsize(List(2) { ScriptType.P2PKH }, listOf(25, 25)) * 50_600)
        assertTrue(built.tx.outputs.any { it.script.contentEquals(change) })
        assertTrue(built.tx.serialize()[4] != 0.toByte(), "no segwit marker")
    }

    @Test
    fun freshScanIsReused() {
        assertTrue(reusableScan(scannedAtMs = 1_000, nowMs = 61_000))
    }

    @Test
    fun staleScanIsNotReused() {
        assertFalse(reusableScan(scannedAtMs = 1_000, nowMs = 200_000))
        assertFalse(reusableScan(scannedAtMs = null, nowMs = 5))
        assertFalse(reusableScan(scannedAtMs = 10_000, nowMs = 5_000)) // a clock that went back
    }

    @Test
    fun scanCacheKeepsAFreshScanUntilCleared() {
        val cache = ScanCache<String, String>()
        assertNull(cache.fresh("w", 0))
        cache.put("w", "scan", tip = 7, nowMs = 1_000)
        assertEquals("scan" to 7L, cache.fresh("w", 60_000))
        assertNull(cache.fresh("w", 200_000))
        cache.put("w", "scan2", tip = 8, nowMs = 1_000)
        cache.clear("w")
        assertNull(cache.fresh("w", 2_000))
    }

    @Test
    fun finalTxidIsKnownBeforeSigningOnlyForNativeSegwit() {
        assertTrue(txidKnownBeforeSigning("P2WPKH"))
        assertFalse(txidKnownBeforeSigning("P2SH_P2WPKH"))
        assertFalse(txidKnownBeforeSigning("P2PKH"))
    }
}
