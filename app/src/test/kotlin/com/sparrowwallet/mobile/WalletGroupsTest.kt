package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletGroupsTest {
    private fun w(name: String, coin: Coin, scriptType: String = "P2PKH", linked: String? = null) =
        WalletRepository.WalletInfo(name, Network.MAINNET, scriptType, linked, coin)

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
        assertEquals(150L, coinTotal(wallets) { balances[it] })
        assertNull(coinTotal(wallets) { null })
    }
}
