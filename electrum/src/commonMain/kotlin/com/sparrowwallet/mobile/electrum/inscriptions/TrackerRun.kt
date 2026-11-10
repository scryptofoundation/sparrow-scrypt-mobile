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
 * desktop's TrackerRun, with a cap on the lookups of the run and a stop at the first server error.
 */
object TrackerRun {
    /** The most server lookups one run spends on coins that are not being checked deeper. */
    const val RUN_BUDGET = 600

    /** Why a coin was not checked when the run's time ran out (the background worker's window); retried next sync. */
    const val TIME_UP = "Check stopped for time, will continue next sync"

    /** An unspent coin to check: its outpoint and the hash of the block that confirmed its transaction (null when unconfirmed). */
    class Coin(val txid: String, val vout: Int, val confirmingBlock: String?) {
        val outpoint: String get() = "$txid:$vout"
    }

    /**
     * The reasons of an Unchecked row a new trace would only reach again while the coin's block is unchanged: MWEB, a
     * mined coin whose fees need Check deeper or do not add up, and runes the policy cannot rule out over a Clean trace
     * (the creating transaction's runestone does not change). "Mined coin height unknown", budget and server-error rows
     * are not here: they are retried.
     */
    val DEAD_ENDS: Set<String> = setOf(
        InscriptionTracer.THROUGH_MWEB, InscriptionTracer.FEES_NEED_DEEPER, InscriptionTracer.DOES_NOT_ADD_UP, CheckPolicy.MAY_CARRY_RUNES
    )

    /** An Unchecked row that met a dead end and knows its block: it stands while that block is the coin's. */
    fun deadEnd(check: InscriptionCheck): Boolean =
        check.verdict == Verdict.UNCHECKED && check.reason in DEAD_ENDS && check.blockHash != null

    /**
     * Whether a stored row stands for its coin without a trace: complete, or a [deadEnd]; from the coin's
     * [confirmingBlock]; and with no indexed answer that contradicts it (a dead end stands only with no indexed answer).
     */
    fun standing(check: InscriptionCheck, confirmingBlock: String?, answer: IndexerAnswer? = null): Boolean {
        if(check.blockHash == null || check.blockHash != confirmingBlock) {
            return false
        }
        return if(deadEnd(check)) answer == null || !answer.indexed else stands(check, answer)
    }

    /** A failure of a call to the chain source, carrying its message. */
    private class ServerFailure(message: String) : Exception(message)

    /** The lookups spent so far and, once set, why no more server calls are made. */
    private class State(val limit: Int) {
        var used = 0
        var stop: String? = null
        fun exhausted(deep: Boolean): Boolean = !deep && used >= limit
    }

    /**
     * Returns a check for every coin in utxos, in order, keyed by outpoint. A stored complete check stands while its
     * coin's confirming block is unchanged and the indexer's answer does not contradict it; a changed block (a reorg)
     * drops it. Others are traced, resuming the open pieces of a stored UNCHECKED check. Coins in deeper are traced first,
     * each with the full deeper budget and the block walk, outside runBudget; the rest share runBudget (their traces, an
     * indexer reveal check and a creating transaction read each count). Once it is used up, or after a server error, no
     * more server calls are made: a coin that would need one keeps its stored result when that proves nothing it should
     * not, and is otherwise left UNCHECKED. Once timeUp says so (checked before each coin that needs the server), the
     * run stops the same way with [TIME_UP]. onResult is told each coin's result as soon as it is decided, in run order,
     * so a caller can save as it goes; progress is told (done, total) after each coin.
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
        timeUp: () -> Boolean = { false },
        onResult: suspend (outpoint: String, check: InscriptionCheck) -> Unit = { _, _ -> },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, InscriptionCheck> {
        val results = HashMap<String, InscriptionCheck>()
        //The coin being traced is never its own known result (a row dropped for a reorg must not come back that way),
        //and only complete checks proven against a mined block stand for their units (an unconfirmed transaction's
        //witness can still change, so a Clean without a block is not passed on)
        var tracing: String? = null
        val known = KnownResults { txid, vout ->
            val outpoint = "$txid:$vout"
            val check = if(outpoint == tracing) null else results[outpoint] ?: stored[outpoint]
            if(check != null && CheckPolicy.complete(check) && check.blockHash != null) KnownOutput(check.verdict, check.found) else null
        }
        val tracer = InscriptionTracer(coin, network, source, known, cache)

        val state = State(runBudget)
        var done = 0
        for(utxo in utxos.filter { it.outpoint in deeper } + utxos.filter { it.outpoint !in deeper }) {
            val outpoint = utxo.outpoint
            val deep = outpoint in deeper
            val blockHash = utxo.confirmingBlock
            val answer = answers[outpoint]
            var previous = stored[outpoint]
            if(previous != null && previous.blockHash != null && previous.blockHash != blockHash) {
                previous = null
            }

            val stands = previous != null && !deep && standing(previous, blockHash, answer)
            if(!stands && state.stop == null && timeUp()) {
                state.stop = TIME_UP
            }
            if(stands) {
                results[outpoint] = previous!!
            } else if(state.stop != null || state.exhausted(deep)) {
                //No server call: keep what is stored unless it claims more than it can now show, or say why nothing was checked
                results[outpoint] = if(previous != null && (!(previous.verdict == Verdict.CLEAN || CheckPolicy.complete(previous)) || stands(previous, answer))) {
                    previous
                } else {
                    held(utxo, indexerSource, state.stop ?: InscriptionTracer.BUDGET_USED, blockHash, null)
                }
            } else {
                tracing = outpoint
                //Only an UNCHECKED trace resumes; an INSCRIBED one that stopped short starts over, so what it found is found again
                val resume: List<Piece> = if(previous != null && previous.verdict == Verdict.UNCHECKED) previous.open else emptyList()
                val perCoin = if(deep) InscriptionTracer.DEEPER_BUDGET else InscriptionTracer.DEFAULT_BUDGET
                val trace = tracer.trace(utxo.txid, utxo.vout, resume, if(deep) perCoin else minOf(perCoin, runBudget - state.used), deep)
                if(!deep) {
                    state.used += trace.lookups
                }
                if(trace.reason?.startsWith(InscriptionTracer.SERVER_ERROR_PREFIX) == true) {
                    state.stop = trace.reason
                }
                results[outpoint] = finish(utxo, answer, indexerSource, trace, source, cache, blockHash, deep, state)
            }
            onResult(outpoint, results.getValue(outpoint))
            progress(++done, utxos.size)
        }
        val ordered = LinkedHashMap<String, InscriptionCheck>()
        for(utxo in utxos) {
            ordered[utxo.outpoint] = results.getValue(utxo.outpoint)
        }
        return ordered
    }

    /** A stored check keeps standing for its coin: complete, from the coin's block, and not contradicted by the indexer. */
    private fun stands(check: InscriptionCheck, answer: IndexerAnswer?): Boolean =
        CheckPolicy.complete(check) && check.blockHash != null && agrees(check, answer)

    /**
     * An UNCHECKED row for a coin that could not be decided. An Unchecked row never carries open pieces together with a
     * forgotten find: the row keeps no found list, and the next run resumes an Unchecked row from its open pieces alone, so
     * pieces are kept only when the trace found nothing. Otherwise the next run starts the trace over and finds it again.
     */
    private fun held(utxo: Coin, indexerSource: InscriptionCheck.Source?, reason: String, blockHash: String?, trace: TraceResult?) =
        InscriptionCheck(
            utxo.txid, utxo.vout, Verdict.UNCHECKED, indexerSource ?: InscriptionCheck.Source.BUILT_IN, false, emptyList(), reason, blockHash,
            if(trace != null && trace.found.isEmpty()) trace.open else emptyList()
        )

    /** Runs a call to the chain source; any failure but cancellation becomes a [ServerFailure]. */
    private suspend fun <T> server(call: suspend () -> T): T = try {
        call()
    } catch(e: CancellationException) {
        throw e
    } catch(e: Exception) {
        throw ServerFailure(e.message ?: e.toString())
    }

    /**
     * Decides one traced coin. What the decision needs from the chain beyond the trace (the indexer's reveal checks, and
     * the creating transaction for a CLEAN trace without an indexer answer, which the runes rule reads) is required: if it
     * cannot be had the coin is left UNCHECKED, for want of a server (the run stops) or of budget.
     */
    private suspend fun finish(
        utxo: Coin,
        answer: IndexerAnswer?,
        indexerSource: InscriptionCheck.Source?,
        trace: TraceResult,
        source: ChainSource,
        cache: TxCache,
        blockHash: String?,
        deep: Boolean,
        state: State
    ): InscriptionCheck {
        val indexed = if(answer != null && answer.indexed) answer else null
        val reveals = HashMap<String, ChainTx?>()
        var creating: RawTx.Parsed? = null
        try {
            if(indexed != null) {
                for(id in indexed.inscriptions) {
                    state.stop?.let { return held(utxo, indexerSource, it, blockHash, trace) }
                    if(state.exhausted(deep)) {
                        return held(utxo, indexerSource, InscriptionTracer.BUDGET_USED, blockHash, trace)
                    }
                    if(!deep) {
                        state.used++
                    }
                    reveals[id] = server { RevealCheck.reveal(id, source) }
                }
            } else if(trace.verdict == Verdict.CLEAN) {
                val cached = cache.get(utxo.txid)
                if(cached != null) {
                    creating = cached.tx
                } else {
                    //A resumed trace starts from ancestors and never read the coin's own transaction
                    if(state.exhausted(deep)) {
                        return held(utxo, indexerSource, InscriptionTracer.BUDGET_USED, blockHash, null)
                    }
                    val tx = server { source.transaction(utxo.txid) }
                    if(tx.fromServer && !deep) {
                        state.used++
                    }
                    if(!tx.tx.txid.equals(utxo.txid, ignoreCase = true)) {
                        throw ServerFailure("The server returned another transaction for ${utxo.txid}")
                    }
                    cache.put(tx)
                    creating = tx.tx
                }
            }
        } catch(e: ServerFailure) {
            val reason = "${InscriptionTracer.SERVER_ERROR_PREFIX}: ${e.message}"
            state.stop = reason
            return held(utxo, indexerSource, reason, blockHash, trace)
        }
        return CheckPolicy.decide(utxo.txid, utxo.vout, indexed, indexerSource, trace, reveals, creating, blockHash)
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
