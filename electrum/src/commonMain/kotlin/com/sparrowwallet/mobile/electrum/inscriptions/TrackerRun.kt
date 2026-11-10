package com.sparrowwallet.mobile.electrum.inscriptions

import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.track.ChainSource
import com.sparrowwallet.mobile.crypto.track.ChainTx
import com.sparrowwallet.mobile.crypto.track.InscriptionTracer
import com.sparrowwallet.mobile.crypto.track.KnownOutput
import com.sparrowwallet.mobile.crypto.track.KnownResults
import com.sparrowwallet.mobile.crypto.track.Piece
import com.sparrowwallet.mobile.crypto.track.TraceResult
import com.sparrowwallet.mobile.crypto.track.TxCache
import com.sparrowwallet.mobile.crypto.track.Verdict
import kotlin.coroutines.cancellation.CancellationException

/**
 * One check of a wallet's unspent coins: stored results kept where still good, the rest traced and decided. Port of the
 * desktop's TrackerRun, with a cap on the lookups of the whole run and a stop at the first server error.
 */
object TrackerRun {
    /** The most server lookups one run makes over all its coins. */
    const val RUN_BUDGET = 600

    internal const val BUDGET_USED = "Lookup budget used up; use Check deeper"
    private const val SERVER_ERROR_PREFIX = "Server error, will retry next sync"

    /** An unspent coin to check: its outpoint and the hash of the block that confirmed its transaction (null when unconfirmed). */
    class Coin(val txid: String, val vout: Int, val confirmingBlock: String?) {
        val outpoint: String get() = "$txid:$vout"
    }

    /**
     * Returns a check for every coin in utxos, in order, keyed by outpoint. A stored complete check stands while its
     * coin's confirming block is unchanged and the indexer's answer does not contradict it; a changed block (a reorg)
     * drops it. Others are traced, resuming the open pieces of a stored UNCHECKED check, with the deeper budget and the
     * block walk for outpoints in deeper. Each trace gets what is left of runBudget at most; once it is gone, or after a
     * server error, no more server calls are made: a coin that would need one keeps its stored result, or is left
     * UNCHECKED. progress is told (done, total) after each coin.
     */
    suspend fun run(
        coin: com.sparrowwallet.mobile.crypto.Coin,
        network: Network,
        cache: TxCache,
        source: ChainSource,
        utxos: List<Coin>,
        stored: Map<String, InscriptionCheck>,
        answers: Map<String, IndexerAnswer>,
        indexerSource: InscriptionCheck.Source?,
        deeper: Set<String>,
        runBudget: Int = RUN_BUDGET,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, InscriptionCheck> {
        val results = LinkedHashMap<String, InscriptionCheck>()
        //The coin being traced is never its own known result (a row dropped for a reorg must not come back that way),
        //and only complete checks stand for their units
        var tracing: String? = null
        val known = KnownResults { txid, vout ->
            val outpoint = "$txid:$vout"
            val check = if(outpoint == tracing) null else results[outpoint] ?: stored[outpoint]
            if(check != null && CheckPolicy.complete(check)) KnownOutput(check.verdict, check.found) else null
        }
        val tracer = InscriptionTracer(coin, network, source, known, cache)

        var used = 0
        var stopReason: String? = null
        var done = 0
        for(utxo in utxos) {
            val outpoint = utxo.outpoint
            val blockHash = utxo.confirmingBlock
            val answer = answers[outpoint]
            var previous = stored[outpoint]
            if(previous != null && previous.blockHash != null && previous.blockHash != blockHash) {
                previous = null
            }

            if(previous != null && CheckPolicy.complete(previous) && previous.blockHash != null && outpoint !in deeper && agrees(previous, answer)) {
                results[outpoint] = previous
            } else if(stopReason != null || used >= runBudget) {
                //No server call: keep what is stored, or say why nothing was checked
                results[outpoint] = previous ?: InscriptionCheck(
                    utxo.txid, utxo.vout, Verdict.UNCHECKED, indexerSource ?: InscriptionCheck.Source.BUILT_IN, false, emptyList(),
                    stopReason ?: BUDGET_USED, blockHash, emptyList()
                )
            } else {
                val deep = outpoint in deeper
                tracing = outpoint
                //Only an UNCHECKED trace resumes; an INSCRIBED one that stopped short starts over, so what it found is found again
                val resume: List<Piece> = if(previous != null && previous.verdict == Verdict.UNCHECKED) previous.open else emptyList()
                val perCoin = if(deep) InscriptionTracer.DEEPER_BUDGET else InscriptionTracer.DEFAULT_BUDGET
                val trace = tracer.trace(utxo.txid, utxo.vout, resume, minOf(perCoin, runBudget - used), deep)
                used += trace.lookups
                if(trace.reason?.startsWith(SERVER_ERROR_PREFIX) == true) {
                    stopReason = trace.reason
                }
                results[outpoint] = decide(utxo, answer, indexerSource, trace, source, cache, blockHash) { stopReason = it }
            }
            progress(++done, utxos.size)
        }
        return results
    }

    private suspend fun decide(
        utxo: Coin,
        answer: IndexerAnswer?,
        indexerSource: InscriptionCheck.Source?,
        trace: TraceResult,
        source: ChainSource,
        cache: TxCache,
        blockHash: String?,
        stop: (String) -> Unit
    ): InscriptionCheck {
        val indexed = if(answer != null && answer.indexed) answer else null
        try {
            val reveals = HashMap<String, ChainTx?>()
            if(indexed != null) {
                for(id in indexed.inscriptions) {
                    reveals[id] = RevealCheck.reveal(id, source)
                }
            }
            return CheckPolicy.decide(utxo.txid, utxo.vout, indexed, indexerSource, trace, reveals, creatingTx(utxo, indexed, trace, source, cache), blockHash)
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            val reason = "$SERVER_ERROR_PREFIX: ${e.message ?: e.toString()}"
            stop(reason)
            return InscriptionCheck(
                utxo.txid, utxo.vout, Verdict.UNCHECKED, indexerSource ?: InscriptionCheck.Source.BUILT_IN, false, emptyList(), reason, blockHash, emptyList()
            )
        }
    }

    /**
     * The transaction that created the coin, for the runes rule (only a CLEAN trace without an indexer answer reads it).
     * The tracer has normally cached it already; a resumed trace may not have, and then it is read once. Null when it
     * cannot be had. Not counted against the budget.
     */
    private suspend fun creatingTx(utxo: Coin, indexed: IndexerAnswer?, trace: TraceResult, source: ChainSource, cache: TxCache): RawTx.Parsed? {
        if(indexed != null || trace.verdict != Verdict.CLEAN) {
            return null
        }
        cache.get(utxo.txid)?.let { return it.tx }
        return try {
            val tx = source.transaction(utxo.txid)
            cache.put(tx)
            tx.tx
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            null
        }
    }

    /** A stored result stands unless an indexer now answers otherwise about whether the coin carries something. */
    private fun agrees(check: InscriptionCheck, answer: IndexerAnswer?): Boolean {
        if(answer == null || !answer.indexed) {
            return true
        }
        val carries = answer.inscriptions.isNotEmpty() || answer.runes || answer.unlisted
        return carries == (check.verdict == Verdict.INSCRIBED || check.runes)
    }
}
