package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.electrum.InscriptionIndex

/**
 * Where a coin's inscription checks go, per coin and network like desktop: Off (the default; the local rule holds
 * coins that may carry one), the coin's public indexer, or the user's own. A public indexer sees the coins asked about.
 */
sealed interface InscriptionSetting {
    /** The indexer to ask, or null when checks are off or the coin has no public indexer on this network. */
    fun url(coin: Coin, network: Network): String?

    fun stored(): String

    data object Off : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String? = null
        override fun stored() = "off"
    }

    data object Public : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String? = InscriptionIndex.publicUrl(coin.id, network.id)
        override fun stored() = "public"
    }

    data class Custom(val customUrl: String) : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String = customUrl.trimEnd('/')
        override fun stored() = "custom:$customUrl"
    }

    companion object {
        fun parse(stored: String?): InscriptionSetting = when {
            stored == "public" -> Public
            stored != null && stored.startsWith("custom:") -> stored.removePrefix("custom:").let { url ->
                if(url.startsWith("http://") || url.startsWith("https://")) Custom(url) else Off
            }
            else -> Off
        }
    }
}

/**
 * Whether a coin is held: the indexer's answer when it has one, otherwise the local rule. An indexer that does not
 * index tokens (Nintondo: inscriptions only) cannot clear a coin whose transaction [writesTokens] (a runestone or
 * dunestone).
 */
inline fun heldFrom(serverAnswer: Boolean?, tokensIndexed: Boolean = true, writesTokens: () -> Boolean = { false },
                    localMayCarry: () -> Boolean): Boolean = when(serverAnswer) {
    true -> true
    false -> !tokensIndexed && writesTokens()
    null -> localMayCarry()
}
