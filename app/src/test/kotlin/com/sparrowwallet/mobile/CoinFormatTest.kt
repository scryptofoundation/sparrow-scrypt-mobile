package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoinFormatTest {
    @Test
    fun formatUsesTheTicker() {
        assertEquals("1.50000000 DOGE", formatAmount(Coin.DOGECOIN, 150_000_000))
        assertEquals("0.00010000 LTC", formatAmount(Coin.LITECOIN, 10_000))
        assertEquals("1.5 PEP", formatAmountShort(Coin.PEPECOIN, 150_000_000))
    }

    @Test
    fun parseAmountIsExact() {
        assertEquals(10_000_000, parseAmount("0.1"))
        assertEquals(123_456_789, parseAmount("1.23456789"))
        assertNull(parseAmount("1.123456789"))
        assertNull(parseAmount("-1"))
        assertNull(parseAmount("abc"))
    }

    @Test
    fun largeAmountsKeepEveryDigit() {
        assertEquals("1234567890.12345678 DOGE", formatAmount(Coin.DOGECOIN, 123_456_789_012_345_678))
        assertEquals("1234567890.12345678", formatUnitsShort(123_456_789_012_345_678))
        assertEquals("0.5", formatUnitsShort(-50_000_000))
        assertEquals("1.0", formatUnitsShort(100_000_000))
        assertEquals("0.00000001", formatUnits(1))
    }

    @Test
    fun usdPriceKeepsSubCentDigits() {
        assertEquals("$0.000123", formatUsdPrice(0.0001234))
        assertEquals("$0.0798", formatUsdPrice(0.0798))
        assertEquals("$84.50", formatUsdPrice(84.5))
        assertEquals("$1,234.50", formatUsdPrice(1234.5))
    }

    @Test
    fun usdValueBelowACentSaysSo() {
        assertEquals("< $0.01", formatUsd(1_000, 0.0001))
        assertEquals("$0.00", formatUsd(0, 1.0))
        assertEquals("$126.75", formatUsd(150_000_000, 84.5))
        assertEquals("—", formatUsd(1, null))
    }
}
