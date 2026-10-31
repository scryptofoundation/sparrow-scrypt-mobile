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
}
