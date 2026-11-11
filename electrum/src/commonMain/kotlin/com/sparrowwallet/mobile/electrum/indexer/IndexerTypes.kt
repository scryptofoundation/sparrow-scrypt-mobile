package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.electrum.inscriptions.IndexerAnswer
import kotlinx.serialization.Serializable

/**
 * A rune balance on one output: [amount] is the raw integer as decimal text, [divisibility] the places after the point.
 * Serializable because the check results keep it for display ([com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck.runeList]).
 */
@Serializable
data class RuneBalance(val amount: String, val divisibility: Int, val symbol: String?)

/**
 * What an indexer knows about one output, like the desktop's OrdOutput. [indexed] false means unknown, never empty.
 * [unlisted] is true when the indexer reported something that was not safe to keep (a malformed id, rune name or
 * balance, or a field of the wrong type): the output still carries something.
 */
data class IndexerOutput(
    val outpoint: String,
    val indexed: Boolean,
    val inscriptions: List<String>,
    val runes: Map<String, RuneBalance>,
    val unlisted: Boolean
) {
    val carries: Boolean get() = inscriptions.isNotEmpty() || runes.isNotEmpty() || unlisted

    fun toAnswer(): IndexerAnswer = IndexerAnswer(indexed, inscriptions, runes.isNotEmpty(), unlisted)

    companion object {
        fun unknown(outpoint: String): IndexerOutput = IndexerOutput(outpoint, false, emptyList(), emptyMap(), false)
    }
}

/** An inscription's details; [number] and the content fields are null when the indexer did not give them. */
data class IndexerInscription(val id: String, val number: Long?, val contentType: String?, val contentLength: Long?)

/** The indexer's status: its network name ("mainnet", "regtest"...), whether it indexes runes, and its height when given. */
data class IndexerStatus(val chain: String, val runeIndex: Boolean, val height: Long?)

/**
 * An inscriptions indexer. Every answer is untrusted. A rate-limited request fails at once and is never waited out; any
 * other failure throws too, so a failed request never reads as an empty answer.
 */
interface InscriptionIndexer {
    /**
     * True when the indexer says it indexes runes, so an output it lists with nothing on it has no runes either. ord's
     * rune index speaks for Litecoin runes only: on other chains (dunes on Dogecoin, Pepecoin, Junkcoin) it never sees
     * the tokens, so the caller must keep its per-coin gate and not read this as "no tokens" there.
     */
    val runesAware: Boolean

    /**
     * True for a reader that asks one output per request (Nintondo's find_meta): a caller with a deadline then asks it a
     * coin at a time, so the answers that came before the deadline are not lost with the rest.
     */
    val asksOneAtATime: Boolean get() = false

    suspend fun status(): IndexerStatus

    /** The indexer's view of each outpoint: an entry for every outpoint asked, unknown where it cannot say. */
    suspend fun outputs(outpoints: List<String>): Map<String, IndexerOutput>

    /** The inscription's details, or null when the indexer does not know it. */
    suspend fun inscription(id: String): IndexerInscription?

    fun close()
}
