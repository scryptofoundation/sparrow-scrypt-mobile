package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A wallet's file name on disk: unsafe characters become _, at most 40 characters. */
fun walletFileName(name: String): String =
    name.trim().map { if(it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
        .joinToString("").take(40)

/**
 * A wallet's links to other wallets on this phone, by name. A public wallet names its companions
 * ([mweb], the Private wallet, and [taproot], the same seed's Taproot wallet); a companion names its [parent].
 */
data class WalletLinks(val mweb: String? = null, val taproot: String? = null, val parent: String? = null) {
    /** The companions this wallet names: MWEB, then Taproot. */
    val companions: List<String> get() = listOfNotNull(mweb, taproot)
    val isCompanion: Boolean get() = parent != null

    /** The MWEB counterpart, as the single link of the previous build meant it: the parent of an MWEB wallet, else its MWEB companion. */
    fun mwebCounterpart(scriptType: String): String? = if(scriptType == "MWEB") parent else mweb

    companion object {
        /** Reads the previous build's single `linkedWallet` name, which only ever linked an MWEB pair. */
        fun fromOldField(linkedWallet: String?, scriptType: String): WalletLinks {
            val name = linkedWallet?.takeIf { it.isNotEmpty() } ?: return WalletLinks()
            return if(scriptType == "MWEB") WalletLinks(parent = name) else WalletLinks(mweb = name)
        }
    }
}

/**
 * The unencrypted `.meta` file beside each vault: what the wallet list needs without a password.
 * Files written before the coin field read as Litecoin; an unknown coin is an error. Balances never go here.
 * Links are written as a `links` object, and the MWEB counterpart also as the old `linkedWallet` field,
 * so the previous build still opens an MWEB pair. Files with only the old field read as an MWEB link.
 */
object WalletMeta {
    data class Decoded(val network: Network, val coin: Coin, val scriptType: String, val links: WalletLinks = WalletLinks())

    fun encode(network: Network, coin: Coin, scriptType: String, links: WalletLinks = WalletLinks()): String =
        buildJsonObject {
            put("network", network.id)
            put("coin", coin.id)
            put("scriptType", scriptType)
            links.mwebCounterpart(scriptType)?.let { put("linkedWallet", it) }
            if(links != WalletLinks()) put("links", buildJsonObject {
                links.mweb?.let { put("mweb", it) }
                links.taproot?.let { put("taproot", it) }
                links.parent?.let { put("parent", it) }
            })
        }.toString()

    fun decode(json: String): Decoded {
        val obj = Json.parseToJsonElement(json).jsonObject
        fun str(key: String) = obj[key]?.jsonPrimitive?.content
        val scriptType = str("scriptType") ?: "P2WPKH"
        return Decoded(
            Network.fromId(str("network") ?: throw IllegalArgumentException("No network")),
            str("coin")?.let { Coin.fromId(it) } ?: Coin.LITECOIN,
            scriptType,
            readLinks(obj["links"]) ?: WalletLinks.fromOldField(str("linkedWallet"), scriptType)
        )
    }

    /** The `links` object, or null when it is missing or not an object; a value that is not a non-empty string reads as no link. */
    private fun readLinks(element: JsonElement?): WalletLinks? {
        val obj = element as? JsonObject ?: return null
        fun name(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        return WalletLinks(name("mweb"), name("taproot"), name("parent"))
    }
}
