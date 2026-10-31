package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.ScriptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
