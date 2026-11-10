package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.Piece
import com.sparrowwallet.mobile.crypto.track.Verdict
import kotlinx.serialization.Serializable

/**
 * What was decided for one coin: its verdict, who said so, whether it may carry runes, the inscriptions found on it, and
 * (for an UNCHECKED or cut-short INSCRIBED coin) the reason and the pieces still to trace. blockHash is the block of the
 * transaction that created the coin, so a reorg can be told from a stale result.
 */
@Serializable
data class InscriptionCheck(
    val txid: String,
    val vout: Int,
    val verdict: Verdict,
    val source: Source,
    val runes: Boolean,
    val found: List<Found>,
    val reason: String?,
    val blockHash: String?,
    val open: List<Piece>
) {
    /** Who gave the answer: the wallet's own trace, a public indexer, the user's own indexer, or the built-in tracer on the coin's server. */
    enum class Source { OWN, PUBLIC, YOUR_OWN, BUILT_IN }

    val outpoint: String get() = "$txid:$vout"

    /**
     * A result checked against a mined block. A Clean result without one (an unconfirmed coin, or a block lookup that
     * failed) is provisional: callers that act on Clean must treat it as Unchecked.
     */
    val proven: Boolean get() = blockHash != null
}

/** What an indexer said about one output (the part of the desktop's OrdOutput the policy reads). */
data class IndexerAnswer(val indexed: Boolean, val inscriptions: List<String>, val runes: Boolean, val unlisted: Boolean)
