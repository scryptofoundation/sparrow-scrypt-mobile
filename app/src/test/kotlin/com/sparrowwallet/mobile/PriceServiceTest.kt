package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceServiceTest {
    @Test
    fun spotPriceIsReadByCoinGeckoId() {
        assertEquals(0.0001, PriceService.parseSpot("""{"pepecoin-network":{"usd":0.0001}}""", Coin.PEPECOIN))
        assertEquals(84.5, PriceService.parseSpot("""{"litecoin":{"usd":84.5}}""", Coin.LITECOIN))
    }

    @Test
    fun anotherCoinsPriceIsNotRead() {
        assertNull(PriceService.parseSpot("""{"litecoin":{"usd":84.5}}""", Coin.DOGECOIN))
        assertNull(PriceService.parseSpot("""not json""", Coin.DOGECOIN))
    }
}
