package com.sparrowwallet.mobile.electrum.indexer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Reads the JSON answers of Nintondo's indexer for Bellscoin, Dogecoin and Pepecoin (/status, /find_meta, /location on
 * the api host, /pub/{id}/info on the search host), a port of the desktop's NintondoDialect with its checks. Nintondo
 * indexes inscriptions only: no runes or dunes, so its status never says it indexes runes.
 *
 * Stricter than the desktop: find_meta answers an empty list both for a clean output and for one it has not indexed yet
 * (a block past its ord_height, or unconfirmed), and nothing here knows the output's height, so an empty answer is not
 * indexed (unknown), never clean. Anything it lists stands, indexed and carrying: an entry without a usable inscription
 * id marks the output [IndexerOutput.unlisted]. A malformed answer throws [IllegalArgumentException].
 */
object NintondoParse {
    private const val TESTNET = " Testnet"

    //the inscription's current output (txid i vout), offset and number: <txid>i<vout>i<offset>i<number>
    private val LOCATION = Regex("([0-9a-f]{64})i([0-9]{1,10})i([0-9]{1,19})i([0-9]{1,18})")

    /**
     * Nintondo's status names the coin and network ("Pepecoin", "Pepecoin Testnet"); it must be [expectedNetworkName]
     * exactly. One for another coin, or for this coin's other network, is refused with the desktop's wording.
     */
    fun status(json: String, expectedNetworkName: String): IndexerStatus {
        val node = OrdParse.readTree(json) as? JsonObject ?: throw IllegalArgumentException("Not a Nintondo indexer")
        val network = node["network"].text() ?: ""
        val coinName = expectedNetworkName.removeSuffix(TESTNET)
        if(network != coinName && network != coinName + TESTNET) {
            throw IllegalArgumentException(if(network.isEmpty()) "Not a Nintondo indexer" else "Inscriptions server is for ${shown(network)}, wallet is $coinName")
        }
        val chain = chain(network)
        val wanted = chain(expectedNetworkName)
        if(chain != wanted) throw IllegalArgumentException("Inscriptions server is on $chain, wallet is on ${wanted.replaceFirstChar { it.uppercase() }}")
        return IndexerStatus(chain, false, node["ord_height"].long()?.takeIf { it >= 0 })
    }

    /** find_meta's answer: the inscriptions on the output, each by its genesis id. Empty is unknown, not clean. */
    fun meta(outpoint: String, json: String): IndexerOutput {
        val node = OrdParse.readTree(json) as? JsonArray ?: throw IllegalArgumentException("Unexpected answer from Nintondo indexer")
        var unlisted = false
        val inscriptions = ArrayList<String>()
        for(entry in node) {
            val genesis = (entry as? JsonObject)?.get("genesis").text()
            if(genesis != null && OrdParse.INSCRIPTION_ID.matches(genesis)) inscriptions += genesis else unlisted = true
        }
        //Anything listed stands; an empty list cannot tell clean from not indexed yet
        val carries = inscriptions.isNotEmpty() || unlisted
        return IndexerOutput(outpoint, carries, inscriptions, emptyMap(), unlisted)
    }

    /** The inscription's current output and offset from /location: (txid, vout, offset), or null when it gives none well formed. */
    fun location(json: String): Triple<String, Int, Long>? {
        val node = OrdParse.readTree(json) as? JsonObject ?: throw IllegalArgumentException("Unexpected location from Nintondo indexer")
        val match = LOCATION.matchEntire(node["location"].text() ?: return null) ?: return null
        val vout = match.groupValues[2].toIntOrNull() ?: return null
        val offset = match.groupValues[3].toLongOrNull() ?: return null
        return Triple(match.groupValues[1], vout, offset)
    }

    /** The search host's info: number, type and size. It must name the inscription asked. */
    fun info(id: String, json: String): IndexerInscription {
        val node = OrdParse.readTree(json) as? JsonObject ?: throw IllegalArgumentException("Unexpected inscription info from Nintondo indexer")
        if(node["id"].text() != id) throw IllegalArgumentException("Unexpected inscription info from Nintondo indexer")
        return IndexerInscription(
            id,
            node["number"].long(),
            node["mime"].text()?.takeIf { it.length <= OrdParse.MAX_CONTENT_TYPE },
            node["file_size"].long()?.takeIf { it >= 0 }
        )
    }

    private fun chain(networkName: String): String = if(networkName.endsWith(TESTNET)) "testnet" else "mainnet"
}
