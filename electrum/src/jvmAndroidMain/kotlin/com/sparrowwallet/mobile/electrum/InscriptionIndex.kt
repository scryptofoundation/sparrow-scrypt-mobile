package com.sparrowwallet.mobile.electrum

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.util.Locale

/**
 * Asks an inscriptions indexer whether a coin carries an inscription or runes, like desktop's OrdClient: ord's
 * /output (Litecoin, ordinalslite.com or the user's own), or Nintondo's find_meta (Bellscoin, Dogecoin, Pepecoin),
 * which follows inscriptions as they move. Answers are untrusted; anything not plainly clean is not clean.
 */
class InscriptionIndex(baseUrl: String, proxy: SocksProxy? = null) {
    private val http = HttpEndpoint(baseUrl, proxy)
    private val nintondo = isNintondo(baseUrl)
    private var runeIndex: Boolean? = null

    /** True when the coin at [txid]:[vout] carries something, false when clean, null when the indexer cannot say. */
    suspend fun carries(txid: String, vout: Int): Boolean? {
        require(TXID.matches(txid) && vout >= 0) { "Invalid outpoint" }
        if(nintondo) {
            val response = http.get("/find_meta/${txid}i$vout", "application/json")
            return if(response.status == 200) parseFindMeta(response.body) else null
        }
        val runes = runeIndex ?: run {
            val status = http.get("/status", "application/json")
            if(status.status != 200) return null
            (Json.parseToJsonElement(status.body) as? JsonObject)?.get("rune_index")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
        }.also { runeIndex = it }
        val response = http.get("/output/$txid:$vout", "application/json")
        return if(response.status == 200) parseOrdOutput(response.body, runes) else null
    }

    fun close() = http.close()

    companion object {
        private val TXID = Regex("[0-9a-f]{64}")
        private const val NINTONDO_DOMAIN = ".nintondo.io"

        //ordinalslite.com indexes Litecoin mainnet only; Nintondo's doge testnet answered with 502s, so it is left out.
        //Junkcoin and Luckycoin have no public indexer, and no Dingocoin coin carries an NFT.
        private val PUBLIC = mapOf(
            "litecoin" to mapOf("mainnet" to "https://ordinalslite.com"),
            "bellscoin" to mapOf("mainnet" to "https://bells-mainnet-api.nintondo.io", "testnet" to "https://bells-testnet-api.nintondo.io"),
            "dogecoin" to mapOf("mainnet" to "https://doge-mainnet-api.nintondo.io"),
            "pepecoin" to mapOf("mainnet" to "https://pepe-mainnet-api.nintondo.io", "testnet" to "https://pepe-testnet-api.nintondo.io")
        )

        /** The public indexer for a coin id and network id, or null when there is none. */
        fun publicUrl(coinId: String, networkId: String): String? = PUBLIC[coinId]?.get(networkId)

        fun isNintondo(url: String): Boolean =
            URI(url).host?.lowercase(Locale.ROOT)?.endsWith(NINTONDO_DOMAIN) == true

        /** ord's /output: null while not indexed, or when it shows nothing but runes are not indexed. */
        fun parseOrdOutput(json: String, runeIndex: Boolean): Boolean? {
            val node = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("Unexpected ord output")
            if((node["indexed"] as? JsonPrimitive)?.booleanOrNull != true) return null
            val inscriptions = (node["inscriptions"] as? JsonArray)?.size ?: 0
            val runes = when(val r = node["runes"]) {
                is JsonObject -> r.size
                is JsonArray -> r.size
                else -> 0
            }
            return when {
                inscriptions > 0 || runes > 0 -> true
                runeIndex -> false
                else -> null
            }
        }

        /** find_meta: the inscriptions on the output; empty is clean. */
        fun parseFindMeta(json: String): Boolean {
            val node = Json.parseToJsonElement(json) as? JsonArray ?: throw IllegalArgumentException("Unexpected find_meta answer")
            return node.isNotEmpty()
        }
    }
}
