package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExplorersTest {
    private val txid = "ab".repeat(32)

    @Test
    fun explorerUrlPerCoin() {
        assertEquals("https://litecoinspace.org/tx/$txid", Explorers.txUrl(Coin.LITECOIN, Network.MAINNET, txid))
        assertEquals("https://litecoinspace.org/testnet/tx/$txid", Explorers.txUrl(Coin.LITECOIN, Network.TESTNET, txid))
        assertEquals("https://blockchair.com/dogecoin/transaction/$txid", Explorers.txUrl(Coin.DOGECOIN, Network.MAINNET, txid))
        assertEquals("https://peppool.space/tx/$txid", Explorers.txUrl(Coin.PEPECOIN, Network.MAINNET, txid))
        assertEquals("https://luckyscan.org/tx/$txid", Explorers.txUrl(Coin.LUCKYCOIN, Network.MAINNET, txid))
        assertEquals("https://explorer.junk-coin.com/tx/$txid", Explorers.txUrl(Coin.JUNKCOIN, Network.MAINNET, txid))
        assertEquals("https://nintondo.io/bells/mainnet/explorer/tx/$txid", Explorers.txUrl(Coin.BELLSCOIN, Network.MAINNET, txid))
        assertEquals("https://explorer.dingocoin.com/tx/$txid", Explorers.txUrl(Coin.DINGOCOIN, Network.MAINNET, txid))
    }

    @Test
    fun testnetWithoutExplorerIsNull() {
        assertNull(Explorers.txUrl(Coin.DOGECOIN, Network.TESTNET, txid))
        assertNull(Explorers.txUrl(Coin.PEPECOIN, Network.TESTNET, txid))
    }
}
