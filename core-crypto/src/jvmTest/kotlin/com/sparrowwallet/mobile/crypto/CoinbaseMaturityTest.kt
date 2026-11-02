package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** Each chain's nCoinbaseMaturity, as drongo has it (from the chains' own chainparams). */
class CoinbaseMaturityTest {
    @Test
    fun coinbaseMaturityFollowsEachChain() {
        assertEquals(100, Coin.LITECOIN.coinbaseMaturity(Network.MAINNET, 1))
        assertEquals(30, Coin.DOGECOIN.coinbaseMaturity(Network.TESTNET, 144_999))
        assertEquals(240, Coin.DOGECOIN.coinbaseMaturity(Network.MAINNET, 145_000))
        assertEquals(70, Coin.JUNKCOIN.coinbaseMaturity(Network.MAINNET, 1))
        assertEquals(30, Coin.JUNKCOIN.coinbaseMaturity(Network.TESTNET, 1))
        assertEquals(70, Coin.LUCKYCOIN.coinbaseMaturity(Network.TESTNET, 1))
        assertEquals(30, Coin.BELLSCOIN.coinbaseMaturity(Network.MAINNET, 1))
        assertEquals(30, Coin.DINGOCOIN.coinbaseMaturity(Network.TESTNET, 4_999))
        assertEquals(240, Coin.DINGOCOIN.coinbaseMaturity(Network.TESTNET, 5_000))
        assertEquals(100, Coin.DINGOCOIN.coinbaseMaturity(Network.MAINNET, 1))
        assertEquals(100, Coin.PEPECOIN.coinbaseMaturity(Network.MAINNET, 1))
        assertEquals(240, Coin.DOGECOIN.longestCoinbaseMaturity(Network.MAINNET))
        assertEquals(30, Coin.BELLSCOIN.longestCoinbaseMaturity(Network.TESTNET))
    }
}
