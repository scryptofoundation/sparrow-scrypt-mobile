package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletGroupsTest {
    private fun w(name: String, coin: Coin, scriptType: String = "P2PKH", linked: String? = null,
                  network: Network = Network.MAINNET) =
        WalletRepository.WalletInfo(name, network, scriptType, linked, coin)

    @Test
    fun groupsFollowTheCoinOrder() {
        val groups = groupByCoin(listOf(w("d", Coin.DOGECOIN), w("l", Coin.LITECOIN, "P2WPKH"), w("p", Coin.PEPECOIN)))
        assertEquals(listOf(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN), groups.map { it.first })
        assertEquals(listOf("d"), groups[1].second.map { it.name })
    }

    @Test
    fun coinsWithoutWalletsAreLeftOut() {
        assertEquals(listOf(Coin.JUNKCOIN), groupByCoin(listOf(w("j", Coin.JUNKCOIN))).map { it.first })
        assertEquals(emptyList(), groupByCoin(emptyList()))
    }

    @Test
    fun linkedMwebWalletStaysUnderItsPublicWallet() {
        val groups = groupByCoin(listOf(
            w("pub", Coin.LITECOIN, "P2WPKH", linked = "priv"),
            w("priv", Coin.LITECOIN, "MWEB", linked = "pub")
        ))
        assertEquals(listOf("pub"), groups.single().second.map { it.name })
    }

    @Test
    fun totalSumsKnownBalances() {
        val wallets = listOf(w("a", Coin.DOGECOIN), w("b", Coin.DOGECOIN), w("c", Coin.DOGECOIN))
        val balances = mapOf("a" to 100L, "c" to 50L)
        assertEquals(150L, coinTotal(wallets, Network.MAINNET) { balances[it] })
        assertNull(coinTotal(wallets, Network.MAINNET) { null })
    }

    @Test
    fun totalsKeepMainnetAndTestnetApart() {
        val wallets = listOf(w("m", Coin.PEPECOIN), w("t", Coin.PEPECOIN, network = Network.TESTNET))
        val balances = mapOf("m" to 100L, "t" to 7L)
        assertEquals(100L, coinTotal(wallets, Network.MAINNET) { balances[it] })
        assertEquals(7L, coinTotal(wallets, Network.TESTNET) { balances[it] })
    }

    @Test
    fun walletBalanceAddsItsLinkedMwebWallet() {
        val wallets = listOf(
            w("pub", Coin.LITECOIN, "P2WPKH", linked = "priv"),
            w("priv", Coin.LITECOIN, "MWEB", linked = "pub"),
            w("other", Coin.LITECOIN, "P2WPKH", linked = "pub2"),
            w("pub2", Coin.LITECOIN, "P2WPKH")
        )
        assertEquals(150L, walletBalance(wallets[0], wallets, mapOf("pub" to 100L, "priv" to 50L)))
        assertEquals(50L, walletBalance(wallets[0], wallets, mapOf("priv" to 50L)))
        assertNull(walletBalance(wallets[0], wallets, emptyMap()))
        assertEquals(5L, walletBalance(wallets[2], wallets, mapOf("other" to 5L, "pub2" to 9L)))
    }
}
