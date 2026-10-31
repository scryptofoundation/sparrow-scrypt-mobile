package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network

/** Preference keys for a coin's server settings. */
object ServerKeys {
    fun prefix(coin: Coin, network: Network): String = "server.${coin.id}.${network.id}"

    /** Where Litecoin's settings lived before the coin axis: read when the new keys are absent, never copied. */
    fun legacyPrefix(coin: Coin, network: Network): String? = if(coin == Coin.LITECOIN) "server.${network.id}" else null
}
