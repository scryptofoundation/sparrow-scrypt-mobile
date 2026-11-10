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
        assertTrue(externalSigningSupported(Coin.LITECOIN, Network.MAINNET, "P2WPKH"))
        assertTrue(externalSigningSupported(Coin.BELLSCOIN, Network.MAINNET, "P2WPKH"))
        assertTrue(externalSigningSupported(Coin.LITECOIN, Network.MAINNET, "P2SH_P2WPKH"))
        assertTrue(externalSigningSupported(Coin.DOGECOIN, Network.MAINNET, "P2PKH"))
        assertFalse(externalSigningSupported(Coin.LITECOIN, Network.MAINNET, "MWEB"))
        assertFalse(externalSigningSupported(Coin.DOGECOIN, Network.MAINNET, "P2WPKH")) // not a script type the coin has
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
        val (tiers, change) = spendables(scan, tip = 200, account = account, scriptType = ScriptType.P2PKH, minConfirmations = 6) { CoinState.CLEAN }
        val inputs = tiers.clean
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
    fun finalTxidIsKnownBeforeSigningOnlyWithoutScriptSigs() {
        assertTrue(txidKnownBeforeSigning("P2WPKH"))
        assertTrue(txidKnownBeforeSigning("P2TR"))
        assertFalse(txidKnownBeforeSigning("P2SH_P2WPKH"))
        assertFalse(txidKnownBeforeSigning("P2PKH"))
    }

    @Test
    fun sendScreenSpeaksOfLayersOnlyForMwebCoins() {
        val ltc = sendCopy(Coin.LITECOIN)
        assertEquals("ONE WALLET, TWO LAYERS", ltc.subtitle)
        assertEquals("Address (public or private)", ltc.addressLabel)
        assertTrue(ltc.showLayers)
        val pep = sendCopy(Coin.PEPECOIN)
        assertEquals("PEPECOIN", pep.subtitle)
        assertEquals("Address", pep.addressLabel)
        assertFalse(pep.showLayers)
        val layerWords = Regex("(?i)layer|public|private")
        for(coin in Coin.entries.filter { !it.mweb }) {
            val c = sendCopy(coin)
            for(text in listOf(c.subtitle, c.addressLabel, c.toSelf, c.from, c.available, c.maxHint, c.shortfall, c.cannotCover)) {
                assertFalse(layerWords.containsMatchIn(text), "${coin.name}: $text")
            }
        }
    }

    @Test
    fun coinsUnderTheConfirmationRuleCountAsMaturing() {
        // value to height; 0 and below are unconfirmed
        val coins = SendCoins.split(listOf(100L to 40, 200L to 45, 300L to 0, 400L to -1, 50L to 38).map { (v, h) -> ScannedCoin(v, h) },
            tip = 45, minConfirmations = 6)
        assertEquals(listOf(100L, 50L), coins.spendable)
        assertEquals(900L, coins.maturing)
    }

    /** A coin that may carry an inscription is held: left out of Send unless the user includes held coins. */
    @Test
    fun heldCoinsAreLeftOutUnlessIncluded() {
        val coins = SendCoins.split(listOf(ScannedCoin(100_000, 40, state = CoinState.HELD), ScannedCoin(5_000_000, 40),
            ScannedCoin(100_000, 45, state = CoinState.HELD)), tip = 45, minConfirmations = 6)
        assertEquals(listOf(5_000_000L), coins.spendable)
        assertEquals(listOf(100_000L), coins.held)
        assertEquals(100_000L, coins.maturing)
        assertEquals(listOf(5_000_000L, 100_000L), coins.usable(includeHeld = true))
        assertEquals(listOf(5_000_000L), coins.usable(includeHeld = false))

        val master = HDKey.fromSeed(Bip39.seed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
        val account = master.derivePath("m/44'/3'/0'")
        val scan = WalletScanner.UtxoScan(listOf(
            WalletScanner.ChainUtxo(Utxo("11".repeat(32), 0, 100, 100_000), 0, 0),
            WalletScanner.ChainUtxo(Utxo("22".repeat(32), 1, 100, 300_000_000), 0, 3)
        ), nextChangeIndex = 2)
        val (tiers, _) = spendables(scan, 200, account, ScriptType.P2PKH, 6) {
            if(it.utxo.txHash == "11".repeat(32)) CoinState.HELD else CoinState.CLEAN
        }
        assertEquals(listOf(300_000_000L), tiers.candidates(includeHeld = false, sendMax = false).map { it.value })
        assertEquals(listOf(300_000_000L), tiers.candidates(includeHeld = false, sendMax = true).map { it.value })
        assertEquals(listOf(300_000_000L, 100_000L), tiers.candidates(includeHeld = true, sendMax = false).map { it.value })
        assertEquals(listOf(300_000_000L, 100_000L), tiers.candidates(includeHeld = true, sendMax = true).map { it.value })
    }

    private val ltcAccount = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")).derivePath("m/84'/2'/0'")

    /** Clean 30,000 and 10,000, Unchecked 50,000 and 20,000, Held 80,000, all P2WPKH and deep enough. */
    private fun tieredScan(): Pair<SpendTiers, ByteArray> {
        val coins = listOf(
            "11" to 30_000L to CoinState.CLEAN, "22" to 50_000L to CoinState.UNCHECKED, "33" to 10_000L to CoinState.CLEAN,
            "44" to 20_000L to CoinState.UNCHECKED, "55" to 80_000L to CoinState.HELD
        )
        val scan = WalletScanner.UtxoScan(coins.mapIndexed { i, (c, _) ->
            WalletScanner.ChainUtxo(Utxo(c.first.repeat(32), 0, 100, c.second), 0, i)
        }, nextChangeIndex = 0)
        val states = coins.associate { (c, s) -> c.first.repeat(32) to s }
        return spendables(scan, 200, ltcAccount, ScriptType.P2WPKH, 6) { states.getValue(it.utxo.txHash) }
    }

    private fun pay(amount: Long, includeHeld: Boolean = false, sendMax: Boolean = false, pegIn: Boolean = false): Pair<SpendTiers, List<String>> {
        val (tiers, change) = tieredScan()
        val dest = Scripts.p2wpkhOutput(ltcAccount.deriveChild(0).deriveChild(9).pubKey)
        val built = TxBuilder.buildUnsigned(tiers.candidates(includeHeld, sendMax, pegIn), dest, amount, 2, change, sendMax,
            ChainParams.of(Coin.LITECOIN, Network.MAINNET), keepOrder = true)
        return tiers to built.inputs.map { outpoint(it) }
    }

    @Test
    fun uncheckedCoinsAreUsedLast() {
        val (tiers, small) = pay(20_000)
        assertEquals(listOf("11".repeat(32) + ":0"), small) // the Clean 30,000, not the larger Unchecked 50,000
        assertFalse(tiers.usesUnchecked(small))
        assertEquals(0, tiers.uncheckedIn(small))

        val (_, cleanOnly) = pay(39_000)
        assertEquals(setOf("11", "33").map { it.repeat(32) + ":0" }.toSet(), cleanOnly.toSet())
        assertFalse(tiers.usesUnchecked(cleanOnly))

        val (_, both) = pay(60_000)
        assertEquals(listOf("11", "33", "22").map { it.repeat(32) + ":0" }, both) // every Clean coin, then the largest Unchecked
        assertTrue(tiers.usesUnchecked(both))
        assertEquals(1, tiers.uncheckedIn(both))

        //Held coins stay out of a payment the other coins cannot cover
        assertFailsWith<IllegalArgumentException> { pay(150_000) }
        assertEquals("55".repeat(32) + ":0", pay(150_000, includeHeld = true).second.last())
    }

    /** Send max spends Clean coins only, unless the user ticks the include box. */
    @Test
    fun sendMaxLeavesUncheckedCoinsOut() {
        val (tiers, swept) = pay(0, sendMax = true)
        assertEquals(setOf("11", "33").map { it.repeat(32) + ":0" }.toSet(), swept.toSet())
        assertEquals(5, pay(0, includeHeld = true, sendMax = true).second.size)
        assertEquals(2, tiers.uncheckedIn(pay(0, includeHeld = true, sendMax = true).second))
    }

    @Test
    fun pegInUsesCleanCoinsOnly() {
        val (tiers, _) = tieredScan()
        for(includeHeld in listOf(false, true)) {
            for(sendMax in listOf(false, true)) {
                assertEquals(listOf(30_000L, 10_000L), tiers.candidates(includeHeld, sendMax, pegIn = true).map { it.value })
            }
        }
        assertFailsWith<IllegalArgumentException> { pay(45_000, includeHeld = true, pegIn = true) }
    }

    @Test
    fun sendCoinsListCleanThenUncheckedThenIncludedHeld() {
        val coins = SendCoins.split(listOf(
            ScannedCoin(30_000, 40), ScannedCoin(50_000, 40, state = CoinState.UNCHECKED), ScannedCoin(80_000, 40, state = CoinState.HELD),
            ScannedCoin(10_000, 40, state = CoinState.UNCHECKED)
        ), tip = 50, minConfirmations = 6)
        assertEquals(listOf(30_000L), coins.spendable)
        assertEquals(listOf(50_000L, 10_000L), coins.unchecked)
        assertEquals(listOf(30_000L, 50_000L, 10_000L), coins.usable(includeHeld = false))
        assertEquals(listOf(30_000L, 50_000L, 10_000L, 80_000L), coins.usable(includeHeld = true))
        assertEquals(listOf(30_000L), coins.sweepable(includeHeld = false))
        assertEquals(listOf(30_000L, 50_000L, 10_000L, 80_000L), coins.sweepable(includeHeld = true))
        assertFalse(coins.heldInscribed)
    }

    @Test
    fun heldCoinsAreNamedInscribedOnlyWhenEveryOneIs() {
        val all = SendCoins.split(listOf(ScannedCoin(10_000, 40, state = CoinState.HELD, inscribed = true),
            ScannedCoin(20_000, 40, state = CoinState.HELD, inscribed = true)), tip = 50, minConfirmations = 6)
        assertTrue(all.heldInscribed)
        val some = SendCoins.split(listOf(ScannedCoin(10_000, 40, state = CoinState.HELD, inscribed = true),
            ScannedCoin(20_000, 40, state = CoinState.HELD)), tip = 50, minConfirmations = 6)
        assertFalse(some.heldInscribed)
        //a maturing inscribed coin does not count: only the ready held ones are named
        val none = SendCoins.split(listOf(ScannedCoin(10_000, 49, state = CoinState.HELD, inscribed = true)), tip = 50, minConfirmations = 6)
        assertFalse(none.heldInscribed)
    }

    @Test
    fun aCoinbaseWaitsForItsMaturity() {
        // tip 100: a coinbase 40 deep under a 70-block rule waits, one 70 deep is spendable
        val coins = SendCoins.split(listOf(ScannedCoin(1_000, 61, coinbaseMaturity = 70), ScannedCoin(2_000, 31, coinbaseMaturity = 70),
            ScannedCoin(3_000, 61)), tip = 100, minConfirmations = 6)
        assertEquals(listOf(2_000L, 3_000L), coins.spendable)
        assertEquals(1_000L, coins.maturing)
        assertTrue(spendableAt(tip = 100, height = 31, minConfirmations = 6, coinbaseMaturity = 70))
        assertFalse(spendableAt(tip = 100, height = 32, minConfirmations = 6, coinbaseMaturity = 70))
    }
}
