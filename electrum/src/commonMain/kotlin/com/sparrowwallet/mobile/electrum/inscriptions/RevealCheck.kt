package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.track.ChainSource
import com.sparrowwallet.mobile.crypto.track.ChainTx
import com.sparrowwallet.mobile.crypto.track.InscriptionLocator
import com.sparrowwallet.mobile.crypto.track.TxCache

/** Checks an indexer's inscription id against the chain: the transaction it names must create it. */
object RevealCheck {
    private val ID = Regex("([0-9a-f]{64})i(\\d{1,10})")

    /** The txid an inscription id names, or null when the id is not well formed. */
    fun txidOf(inscriptionId: String): String? = ID.matchEntire(inscriptionId)?.groupValues?.get(1)

    /**
     * The transaction that creates the inscription, or null when the chain shows no such inscription: an ord id names a
     * reveal with at least that many envelopes; a Doginals id (index 0) names a chain's first step, whose scriptSig starts
     * with "ord". A transaction the server cannot return, or returns for another txid, throws: an outage and an unknown txid look the same, and both
     * leave the coin held. A mined reveal is read from [cache] when a trace already holds it, and kept there when fetched
     * (the cache never keeps an unconfirmed one).
     */
    suspend fun reveal(inscriptionId: String, source: ChainSource, cache: TxCache): ChainTx? {
        val match = ID.matchEntire(inscriptionId) ?: return null
        val txid = match.groupValues[1]
        val tx = cache.get(txid) ?: source.transaction(txid).also {
            //Only the transaction asked for is kept or read; another one is an error, never "no inscription found"
            check(it.tx.txid.equals(txid, ignoreCase = true)) { "The server returned another transaction for $txid" }
            cache.put(it)
        }
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
