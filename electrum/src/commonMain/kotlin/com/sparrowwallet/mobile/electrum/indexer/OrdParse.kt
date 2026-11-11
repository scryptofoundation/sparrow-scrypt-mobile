package com.sparrowwallet.mobile.electrum.indexer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads ord 0.20's JSON answers (/status, /outputs, /output, /inscription), a port of the desktop's Ord020Dialect with
 * its checks: inscription ids and rune names are matched before they are kept, rune balances must be whole and their
 * divisibility at most 38, and anything not understood marks the output as [IndexerOutput.unlisted], so it still
 * carries something. Stricter than the desktop: a field of the wrong type is unlisted rather than read as empty, and an
 * output is vouched for as empty only when the server indexes runes, lists both inscriptions and runes, and holds it
 * unspent; otherwise an empty answer is not indexed (unknown). A malformed answer throws [IllegalArgumentException].
 */
object OrdParse {
    const val MAX_JSON_CHARS = 4_000_000
    private const val MAX_DIVISIBILITY = 38
    private const val MAX_CONTENT_TYPE = 255
    private const val NO_SYMBOL = "¤"

    internal val INSCRIPTION_ID = Regex("[0-9a-f]{64}i[0-9]{1,10}")
    internal val OUTPOINT = Regex("[0-9a-f]{64}:[0-9]{1,10}")
    private val RUNE_NAME = Regex("[A-Z•]{1,40}")
    private val DIGITS = Regex("[0-9]{1,40}")
    private val SMALL = Regex("[0-9]{1,2}")
    private val LONG = Regex("-?[0-9]{1,19}")

    fun status(json: String): IndexerStatus {
        val node = readTree(json) as? JsonObject ?: throw IllegalArgumentException("Not an ord server status")
        val chain = node["chain"].text() ?: throw IllegalArgumentException("Not an ord server status")
        return IndexerStatus(chain, node["rune_index"].isTrue(), node["height"].long()?.takeIf { it >= 0 })
    }

    fun output(outpoint: String, json: String, runeIndex: Boolean): IndexerOutput = output(outpoint, readTree(json), runeIndex)

    /** The answer to POST /outputs, which carries no output index, so entries are matched to [outpoints] by position. */
    fun outputs(outpoints: List<String>, json: String, runeIndex: Boolean): Map<String, IndexerOutput> {
        val node = readTree(json)
        if(node !is JsonArray || node.size != outpoints.size) {
            throw IllegalArgumentException("Inscriptions indexer answered ${(node as? JsonArray)?.size ?: 0} outputs for ${outpoints.size} asked")
        }
        val outputs = LinkedHashMap<String, IndexerOutput>()
        outpoints.forEachIndexed { i, outpoint -> outputs[outpoint] = output(outpoint, node[i], runeIndex) }
        return outputs
    }

    fun inscription(json: String): IndexerInscription {
        val node = readTree(json) as? JsonObject ?: throw IllegalArgumentException("Unexpected inscription from inscriptions indexer")
        val id = node["id"].text()
        if(id == null || !INSCRIPTION_ID.matches(id)) throw IllegalArgumentException("Unexpected inscription from inscriptions indexer")
        //cursed inscriptions have negative numbers
        return IndexerInscription(
            id,
            node["number"].long(),
            node["content_type"].text()?.takeIf { it.length <= MAX_CONTENT_TYPE },
            node["content_length"].long()?.takeIf { it >= 0 }
        )
    }

    private fun output(outpoint: String, node: JsonElement, runeIndex: Boolean): IndexerOutput {
        if(node !is JsonObject) throw IllegalArgumentException("Unexpected output from inscriptions indexer")
        val transaction = node["transaction"].text()
        if(transaction != null && transaction != outpoint.substringBefore(':')) {
            throw IllegalArgumentException("Inscriptions indexer answered for another transaction")
        }
        var unlisted = false

        var inscriptionsListed = true
        val inscriptions = ArrayList<String>()
        when(val list = node["inscriptions"]) {
            null, JsonNull -> inscriptionsListed = false
            is JsonArray -> for(id in list) {
                val text = id.text()
                if(text != null && INSCRIPTION_ID.matches(text)) inscriptions += text else unlisted = true
            }
            else -> unlisted = true
        }

        //ord writes runes as an object keyed by name; some versions write an array of [name, balance] pairs
        var runesListed = true
        val entries = ArrayList<Pair<JsonElement, JsonElement>>()
        when(val r = node["runes"]) {
            null, JsonNull -> runesListed = false
            is JsonObject -> r.forEach { (name, balance) -> entries += JsonPrimitive(name) to balance }
            is JsonArray -> for(pair in r) {
                if(pair is JsonArray && pair.size == 2) entries += pair[0] to pair[1] else unlisted = true
            }
            else -> unlisted = true
        }
        val runes = LinkedHashMap<String, RuneBalance>()
        for((nameNode, balanceNode) in entries) {
            val name = nameNode.text()
            val balance = balance(balanceNode)
            if(name == null || !RUNE_NAME.matches(name) || balance == null) unlisted = true else runes[name] = balance
        }

        val carries = inscriptions.isNotEmpty() || runes.isNotEmpty() || unlisted
        //An answer showing nothing vouches for the output only when nothing it needs is missing (the desktop's forServer, and more)
        val vouchesEmpty = runeIndex && runesListed && !node["spent"].isTrue()
        val indexed = node["indexed"].isTrue() && inscriptionsListed && (carries || vouchesEmpty)
        return IndexerOutput(outpoint, indexed, inscriptions, runes, unlisted)
    }

    private fun balance(node: JsonElement): RuneBalance? {
        val o = node as? JsonObject ?: return null
        val amount = (o["amount"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return null
        if(!DIGITS.matches(amount)) return null
        val divisibility = (o["divisibility"] as? JsonPrimitive)?.takeIf { !it.isString && SMALL.matches(it.content) }?.content?.toInt() ?: return null
        if(divisibility > MAX_DIVISIBILITY) return null
        return RuneBalance(amount.trimStart('0').ifEmpty { "0" }, divisibility, symbol(o["symbol"]))
    }

    /** One showable character, or the generic currency sign: no controls, format characters (bidi overrides), private use or unassigned. */
    private fun symbol(node: JsonElement?): String {
        val s = node.text() ?: return NO_SYMBOL
        val codePoint = when {
            s.length == 1 && !s[0].isSurrogate() -> s[0].code
            s.length == 2 && s[0].isHighSurrogate() && s[1].isLowSurrogate() -> 0x10000 + ((s[0].code - 0xD800) shl 10) + (s[1].code - 0xDC00)
            else -> return NO_SYMBOL
        }
        return if(showableSymbol(codePoint)) s else NO_SYMBOL
    }

    private fun readTree(json: String): JsonElement {
        if(json.length > MAX_JSON_CHARS) throw IllegalArgumentException("Inscriptions indexer response too large")
        return try {
            Json.parseToJsonElement(json)
        } catch(e: Exception) {
            throw IllegalArgumentException("Unreadable inscriptions indexer response", e)
        }
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Only the JSON literal true: not "true", not 1. */
    private fun JsonElement?.isTrue(): Boolean = this is JsonPrimitive && this !is JsonNull && !isString && content == "true"

    private fun JsonElement?.long(): Long? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString && LONG.matches(it.content) }?.content?.toLongOrNull()
}

/** Whether a code point may be shown as a rune's symbol (the desktop's OrdClient.symbol rules, by Unicode category). */
internal expect fun showableSymbol(codePoint: Int): Boolean
