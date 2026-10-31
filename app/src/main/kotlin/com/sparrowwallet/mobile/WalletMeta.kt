package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The unencrypted `.meta` file beside each vault: what the wallet list needs without a password.
 * Files written before the coin field read as Litecoin; an unknown coin is an error. Balances never go here.
 */
object WalletMeta {
    data class Decoded(val network: Network, val coin: Coin, val scriptType: String, val linkedWallet: String?)

    fun encode(network: Network, coin: Coin, scriptType: String, linkedWallet: String?): String =
        buildJsonObject {
            put("network", network.id)
            put("coin", coin.id)
            put("scriptType", scriptType)
            linkedWallet?.let { put("linkedWallet", it) }
        }.toString()

    fun decode(json: String): Decoded {
        val obj = Json.parseToJsonElement(json).jsonObject
        fun str(key: String) = obj[key]?.jsonPrimitive?.content
        return Decoded(
            Network.fromId(str("network") ?: throw IllegalArgumentException("No network")),
            str("coin")?.let { Coin.fromId(it) } ?: Coin.LITECOIN,
            str("scriptType") ?: "P2WPKH",
            str("linkedWallet")?.takeIf { it.isNotEmpty() }
        )
    }
}
