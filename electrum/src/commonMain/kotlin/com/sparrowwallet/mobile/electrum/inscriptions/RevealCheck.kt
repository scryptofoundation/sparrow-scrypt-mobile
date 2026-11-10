package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.track.ChainSource
import com.sparrowwallet.mobile.crypto.track.ChainTx
import com.sparrowwallet.mobile.crypto.track.InscriptionLocator

/** Checks an indexer's inscription id against the chain: the transaction it names must create it. */
object RevealCheck {
    private val ID = Regex("([0-9a-f]{64})i(\\d{1,10})")

    /**
     * The transaction that creates the inscription, or null when the chain shows no such inscription: an ord id names a
     * reveal with at least that many envelopes; a Doginals id (index 0) names a chain's first step, whose scriptSig starts
     * with "ord". A transaction the server cannot return throws: an outage and an unknown txid look the same, and both
     * leave the coin held.
     */
    suspend fun reveal(inscriptionId: String, source: ChainSource): ChainTx? {
        val match = ID.matchEntire(inscriptionId) ?: return null
        val tx = source.transaction(match.groupValues[1])
        if(inscriptionId in InscriptionLocator.ordIds(tx.tx)) {
            return tx
        }
        val step = InscriptionLocator.doginals(tx.tx)
        if(step != null && step.genesis && match.groupValues[2] == "0") {
            return tx
        }
        return null
    }
}
