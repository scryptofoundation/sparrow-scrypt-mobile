package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.CarrierScan
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.track.ChainTx
import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.TraceResult
import com.sparrowwallet.mobile.crypto.track.Verdict

/**
 * Decides one coin from what an indexer said (if anything) and what the on-chain trace showed. Nothing is clean unless
 * shown clean; an indexer and the chain that disagree leave the coin held.
 */
object CheckPolicy {
    const val DISAGREE_NOT_ON_CHAIN = "Indexer and chain disagree: %s not found on chain"
    const val DISAGREE_CHAIN_SHOWS = "Indexer and chain disagree: chain shows %s"
    const val MAY_CARRY_RUNES = "May carry runes"

    /**
     * answer is the indexer's vetted answer, or null without one (no indexer, unreachable, or not indexed). reveals maps
     * each inscription id the indexer named to the transaction that creates it; a null value or a missing key means the
     * chain shows none.
     */
    fun decide(
        txid: String,
        vout: Int,
        answer: IndexerAnswer?,
        indexerSource: InscriptionCheck.Source?,
        trace: TraceResult,
        reveals: Map<String, ChainTx?>,
        creatingTx: RawTx.Parsed?,
        blockHash: String?
    ): InscriptionCheck {
        if(answer != null && answer.indexed) {
            val source = requireNotNull(indexerSource) { "An indexer answer needs its source" }
            val runes = answer.runes || answer.unlisted
            if(answer.inscriptions.isNotEmpty()) {
                val found = ArrayList<Found>()
                for(id in answer.inscriptions) {
                    val reveal = reveals[id]
                        ?: return unchecked(txid, vout, source, runes, DISAGREE_NOT_ON_CHAIN.replace("%s", id), blockHash, trace)
                    val traced = trace.found.firstOrNull { it.inscriptionId == id }
                    found.add(traced ?: Found(id, -1, null, reveal.tx.txid, reveal.height, reveal.blockTime))
                }
                //An inscription on the chain the indexer did not list: the two disagree, so the coin is held
                for(traced in trace.found) {
                    if(traced.inscriptionId !in answer.inscriptions) {
                        return unchecked(txid, vout, source, runes, DISAGREE_CHAIN_SHOWS.replace("%s", traced.inscriptionId), blockHash, trace)
                    }
                }
                return InscriptionCheck(txid, vout, Verdict.INSCRIBED, source, runes, found, null, blockHash, emptyList())
            }
            if(trace.verdict == Verdict.INSCRIBED) {
                return unchecked(txid, vout, source, runes, DISAGREE_CHAIN_SHOWS.replace("%s", trace.found.first().inscriptionId), blockHash, trace)
            }
            return InscriptionCheck(txid, vout, Verdict.CLEAN, source, runes, emptyList(), null, blockHash, emptyList())
        }

        val source = if(trace.usedChain) InscriptionCheck.Source.BUILT_IN else InscriptionCheck.Source.OWN
        if(trace.verdict == Verdict.CLEAN && creatingTx != null && (CarrierScan.hasRunestone(creatingTx) || CarrierScan.hasDunestone(creatingTx))) {
            return InscriptionCheck(txid, vout, Verdict.UNCHECKED, source, false, emptyList(), MAY_CARRY_RUNES, blockHash, emptyList())
        }
        //An Inscribed trace that stopped short keeps its reason and open pieces: it is not complete, and resumes
        val clean = trace.verdict == Verdict.CLEAN
        return InscriptionCheck(
            txid, vout, trace.verdict, source, false, trace.found, if(clean) null else trace.reason, blockHash,
            if(clean) emptyList() else trace.open
        )
    }

    private fun unchecked(txid: String, vout: Int, source: InscriptionCheck.Source, runes: Boolean, reason: String, blockHash: String?, trace: TraceResult) =
        InscriptionCheck(txid, vout, Verdict.UNCHECKED, source, runes, trace.found, reason, blockHash, emptyList())

    /**
     * Whether a check can stand for its coin's units in later traces: Clean, or Inscribed with nothing left to trace. An
     * Inscribed result that stopped short (a reason, open pieces) says nothing about the units it did not reach.
     */
    fun complete(check: InscriptionCheck?): Boolean {
        if(check == null || check.runes || check.reason != null) {
            return false
        }
        return check.verdict == Verdict.CLEAN || (check.verdict == Verdict.INSCRIBED && check.open.isEmpty())
    }
}
