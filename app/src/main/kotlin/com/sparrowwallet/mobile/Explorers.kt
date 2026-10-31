package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network

/** Block explorer links, matching desktop's BlockExplorer defaults. */
object Explorers {
    fun txUrl(coin: Coin, network: Network, txid: String): String? {
        val base = when(network) {
            Network.MAINNET -> when(coin) {
                Coin.LITECOIN -> "https://litecoinspace.org/tx/"
                Coin.DOGECOIN -> "https://blockchair.com/dogecoin/transaction/"
                Coin.PEPECOIN -> "https://peppool.space/tx/"
                Coin.LUCKYCOIN -> "https://luckyscan.org/tx/"
                Coin.JUNKCOIN -> "https://explorer.junk-coin.com/tx/"
                Coin.BELLSCOIN -> "https://nintondo.io/bells/mainnet/explorer/tx/"
                Coin.DINGOCOIN -> "https://explorer.dingocoin.com/tx/"
            }
            Network.TESTNET -> if(coin == Coin.LITECOIN) "https://litecoinspace.org/testnet/tx/" else null
        }
        return base?.let { it + txid }
    }
}
