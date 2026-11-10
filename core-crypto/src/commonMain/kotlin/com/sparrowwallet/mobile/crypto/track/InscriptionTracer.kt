package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.CarrierScan
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.ScriptReader
import kotlin.coroutines.cancellation.CancellationException

/**
 * Traces a coin's units back through its history until each piece is shown clean (it reaches a block before the
 * chain's first inscription, or a mined coin's reward) or inscribed (it reaches the transaction that created an
 * inscription on it), within a budget of server lookups. Anything not shown clean leaves the coin UNCHECKED.
 * Port of drongo's InscriptionTracer.
 */
class InscriptionTracer internal constructor(
    private val source: ChainSource,
    private val known: KnownResults,
    private val cache: TxCache,
    /** No unit of a block below this height carries an inscription. */
    private val firstInscriptionHeight: Int,
    /** The exact block reward at a height, or null where the chain's schedule is not exact. */
    private val subsidy: (height: Int) -> Long?
) {
    constructor(coin: Coin, network: Network, source: ChainSource, known: KnownResults, cache: TxCache) : this(
        source, known, cache, ChainRules.firstInscriptionHeight(coin, network), { height -> ChainRules.subsidy(coin, network, height) }
    )

    companion object {
        const val DEFAULT_BUDGET = 100
        const val DEEPER_BUDGET = 1_000

        const val BUDGET_USED = "Lookup budget used up; use Check deeper"
        /** What a reason starts with when the server failed; callers that must stop on a server error match this. */
        const val SERVER_ERROR_PREFIX = "Server error, will retry next sync"
        internal const val SERVER_ERROR = "$SERVER_ERROR_PREFIX: %s"
        /**
         * Dead ends: the trace meets the same one again while the coin's block is unchanged, so a stored row with one of
         * these reasons stands (still Unchecked) until the block changes or the user checks deeper.
         */
        const val FEES_NEED_DEEPER = "Mined coin fees need Check deeper"
        const val DOES_NOT_ADD_UP = "Mined coin does not add up"
        /**
         * The phone's stop at an MWEB input (see hasMwebInput); not a standing dead end. A HogEx no longer stops here: coins
         * out of MWEB are clean (the owner's rule, 2026-11-10).
         */
        const val THROUGH_MWEB = "Came through MWEB"
        /** Not a dead end: a server that gave no height may give one next time. */
        const val HEIGHT_UNKNOWN = "Mined coin height unknown"

        /** drongo's InscriptionContent.MAX_DOGINALS_STEPS: the longest Doginals chain the id walk follows. */
        private const val MAX_DOGINALS_STEPS = 2_000

        private const val OP_8 = 0x58
        private const val NO_INDEX = 0xFFFFFFFFL

        private fun serverError(message: String): String = SERVER_ERROR.replace("%s", message)

        /**
         * drongo's Transaction.isHogEx: a transaction with the MWEB flag and an extension of the single byte 0 (no MWEB
         * transaction, which only a HogEx carries), or one whose input 0 spends an output 0 and whose output 0 pays OP_8
         * and a 32 byte push. A peg-in carries its MWEB transaction and is traced through, as drongo does.
         */
        internal fun isHogEx(tx: RawTx.Parsed): Boolean {
            if(tx.mwebNullExtension) {
                return true
            }
            if(tx.inputs.isEmpty() || tx.outputs.isEmpty()) {
                return false
            }
            if(tx.inputs.first().prevVout != 0L) {
                return false
            }
            val chunks = ScriptReader.chunksUpToError(tx.outputs.first().script)
            return chunks.size == 2 && chunks[0].opcode == OP_8 && chunks[1].data?.size == 32
        }

        /** drongo's Transaction.isCoinBase: one input, and that a coinbase input. */
        private fun isCoinBase(tx: RawTx.Parsed): Boolean = tx.inputs.size == 1 && tx.inputs.first().isCoinbase

        /**
         * An input with no ordinary outpoint: an MWEB id on the 0xFFFFFFFF index with a non-zero hash (drongo's
         * Transaction.isMweb sentinel). drongo's tracer has no such check: its Transaction.getInputs leaves such inputs
         * out, so it would trace only the other inputs. The phone stops the piece as MWEB instead (stricter).
         */
        private fun hasMwebInput(tx: RawTx.Parsed): Boolean = tx.inputs.any { it.prevVout == NO_INDEX && !it.isCoinbase }

        private fun distinct(found: List<Found>): List<Found> {
            val byId = LinkedHashMap<String, Found>()
            for(f in found) {
                val existing = byId[f.inscriptionId]
                if(existing == null || (existing.offset < 0 && f.offset >= 0)) {
                    byId[f.inscriptionId] = f
                }
            }
            return ArrayList(byId.values)
        }
    }

    private class BudgetUsedException : Exception()

    /** A failure reading the chain, reported as a server error. */
    private class ChainException(message: String) : Exception(message)

    /** One trace's working state. */
    private inner class Run(val budget: Int, val walkBlocks: Boolean) {
        val queue = ArrayDeque<Piece>()
        val found = ArrayList<Found>()
        val open = ArrayList<Piece>()
        var deadEnd: String? = null
        var lookups = 0
        var usedChain = false

        suspend fun fetch(txid: String): ChainTx {
            val cached = cache.get(txid)
            if(cached != null) {
                return cached
            }
            if(lookups >= budget) {
                throw BudgetUsedException()
            }
            val tx = source.transaction(txid)
            if(!tx.tx.txid.equals(txid, ignoreCase = true)) {
                throw ChainException("The server returned another transaction for $txid")
            }
            if(tx.fromServer) {
                lookups++
                usedChain = true
            }
            cache.put(tx)
            return tx
        }

        suspend fun inputValues(tx: RawTx.Parsed): List<Long> {
            val values = ArrayList<Long>()
            for(input in tx.inputs) {
                val parent = fetch(input.prevTxid).tx
                val index = input.prevVout.toInt()
                if(index >= parent.outputs.size) {
                    throw ChainException("Transaction ${parent.txid} has no output $index")
                }
                values.add(parent.outputs[index].value)
            }
            return values
        }

        /** Counts a server call that is not a transaction fetch (a block's fee list, a position lookup). */
        fun serverCall() {
            if(lookups >= budget) {
                throw BudgetUsedException()
            }
            lookups++
            usedChain = true
        }

        fun deadEnd(reason: String) {
            if(deadEnd == null) {
                deadEnd = reason
            }
        }

        fun foundAt(piece: Piece, id: String, offsetInOutput: Long, mint: ChainTx) {
            found.add(Found(id, piece.coinOffset + (offsetInOutput - piece.range.start), null, mint.tx.txid, mint.height, mint.blockTime))
        }
    }

    /**
     * Traces output vout of txid, or continues from resume when it holds the pieces a cut-off trace left open. budget
     * caps the server lookups; walkBlocks lets a mined coin's fees be worked out one block transaction at a time.
     */
    suspend fun trace(txid: String, vout: Int, resume: List<Piece>, budget: Int, walkBlocks: Boolean): TraceResult {
        val run = Run(budget, walkBlocks)
        var stopped: String? = null
        try {
            if(resume.isEmpty()) {
                val tx = run.fetch(txid).tx
                if(vout < 0 || vout >= tx.outputs.size) {
                    throw ChainException("Transaction $txid has no output $vout")
                }
                val value = tx.outputs[vout].value
                if(value <= 0) {
                    return TraceResult(Verdict.CLEAN, emptyList(), null, emptyList(), run.lookups, run.usedChain)
                }
                run.queue.add(Piece(txid, vout, UnitRange(0, value), 0))
            } else {
                run.queue.addAll(resume)
            }
        } catch(e: BudgetUsedException) {
            return TraceResult(Verdict.UNCHECKED, emptyList(), BUDGET_USED, resume.toList(), run.lookups, run.usedChain)
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            return TraceResult(Verdict.UNCHECKED, emptyList(), serverError(e.message ?: e.toString()), resume.toList(), run.lookups, run.usedChain)
        }

        while(run.queue.isNotEmpty()) {
            val piece = run.queue.removeFirst()
            try {
                step(run, piece)
            } catch(e: BudgetUsedException) {
                stopped = BUDGET_USED
            } catch(e: CancellationException) {
                throw e
            } catch(e: Exception) {
                stopped = serverError(e.message ?: e.toString())
            }
            if(stopped != null) {
                run.open.add(piece)
                run.open.addAll(run.queue)
                run.queue.clear()
            }
        }

        val found = distinct(run.found)
        val reason = stopped ?: run.deadEnd
        val verdict = if(found.isNotEmpty()) Verdict.INSCRIBED else if(reason != null) Verdict.UNCHECKED else Verdict.CLEAN
        //A dead end (an MWEB input, a coinbase that cannot be resolved) leaves nothing to resume: resuming only the other pieces
        //would forget it, so the next trace starts over from the coin and meets it again
        val open = if(run.deadEnd != null) emptyList() else run.open.toList()
        return TraceResult(verdict, found, if(verdict == Verdict.CLEAN) null else reason, open, run.lookups, run.usedChain)
    }

    private suspend fun step(run: Run, piece: Piece) {
        if(piece.vout != Piece.FEE) {
            val knownOutput = known.forOutput(piece.txid, piece.vout)
            if(knownOutput != null && knownOutput.verdict != Verdict.UNCHECKED) {
                for(f in knownOutput.found) {
                    if(f.offset < 0) {
                        run.found.add(f.withOffset(-1))
                    } else if(f.offset >= piece.range.start && f.offset < piece.range.end) {
                        run.found.add(f.withOffset(piece.coinOffset + (f.offset - piece.range.start)))
                    }
                }
                return
            }
        }

        val chainTx = run.fetch(piece.txid)
        val tx = chainTx.tx
        if(chainTx.height > 0 && chainTx.height < firstInscriptionHeight) {
            return
        }
        if(isHogEx(tx)) {
            //Units that come out of MWEB (a peg-out, or the pool's own output) carry no history to follow, so they are clean
            return
        }
        if(isCoinBase(tx)) {
            //Without its height a coinbase's reward cannot be known (a server that gave no height reads as height 0)
            if(chainTx.height <= 0) {
                run.deadEnd(HEIGHT_UNKNOWN)
                return
            }
            minedCoin(run, piece, chainTx)
            return
        }
        if(hasMwebInput(tx)) {
            run.deadEnd(THROUGH_MWEB)
            return
        }

        //A Doginals inscription sits on the first unit of the last step's outputs (output 1 when output 0 holds nothing)
        val doginals = InscriptionLocator.doginals(tx)
        if(doginals != null && doginals.last && UnitFlow.outputTotal(tx) > 0) {
            val first = UnitFlow.place(tx, UnitRange(0, 1), 0).first()
            if(first.vout == piece.vout && first.range.start >= piece.range.start && first.range.start < piece.range.end) {
                val genesis = genesisTxid(run, tx, doginals)
                if(genesis != null) {
                    run.foundAt(piece, genesis + "i0", first.range.start, chainTx)
                }
            }
        }

        val inputValues = run.inputValues(tx)
        if(CarrierScan.hasEnvelope(tx)) {
            for(creation in InscriptionLocator.ord(tx, inputValues)) {
                if(creation.vout == Creation.ANYWHERE) {
                    run.found.add(Found(creation.inscriptionId, -1, null, tx.txid, chainTx.height, chainTx.blockTime))
                } else if(creation.vout == piece.vout && creation.offset >= piece.range.start && creation.offset < piece.range.end) {
                    run.foundAt(piece, creation.inscriptionId, creation.offset, chainTx)
                }
            }
        }
        run.queue.addAll(UnitFlow.back(tx, inputValues, piece))
    }

    /**
     * The txid that names a Doginals inscription: the chain's first step ("ord" first), found by walking input 0 back.
     * Null when the walk reaches a transaction that is no step (so this was no Doginals chain); "?" when the budget runs
     * out first, which still counts as an inscription.
     */
    private suspend fun genesisTxid(run: Run, last: RawTx.Parsed, step: DoginalsStep): String? {
        var current = last
        var currentStep = step
        try {
            var steps = 0
            while(!currentStep.genesis) {
                if(steps >= MAX_DOGINALS_STEPS) {
                    return "?"
                }
                current = run.fetch(currentStep.previousTxid).tx
                val previous = InscriptionLocator.doginals(current)
                if(previous == null || previous.last) {
                    return null
                }
                currentStep = previous
                steps++
            }
        } catch(e: BudgetUsedException) {
            return "?"
        }
        return current.txid
    }

    /** A piece on a coinbase: reward units are clean; fee units are traced into the block transactions that paid them. */
    private suspend fun minedCoin(run: Run, piece: Piece, coinbase: ChainTx) {
        val tx = coinbase.tx
        val total = UnitFlow.outputTotal(tx)
        var outputStart = 0L
        for(i in 0 until piece.vout) {
            outputStart += tx.outputs[i].value
        }
        val flow = piece.range.shift(outputStart)

        //A height read from confirmations can be one off as a block arrives; at a halving the smaller reward is the safe one
        val coded = smallestSubsidy(coinbase.height)
        if(coded != null && flow.end <= coded) {
            return
        }

        val fees = blockFees(run, coinbase, coded != null)
        if(fees == null) {
            run.deadEnd(FEES_NEED_DEEPER)
            run.open.add(piece)
            return
        }
        var feeTotal = 0L
        for(fee in fees) {
            feeTotal += fee.fee
        }
        val reward = coded ?: (total - feeTotal)
        if(reward < 0 || reward + feeTotal != total) {
            run.deadEnd(DOES_NOT_ADD_UP)
            return
        }
        if(flow.end <= reward) {
            return
        }

        //Never null here (reward < flow.end <= total); a null fails the piece as drongo's NullPointerException does
        val feePart = flow.intersect(UnitRange(reward, total))!!
        var cursor = reward
        for(fee in fees) {
            if(fee.fee > 0) {
                val shared = UnitRange(cursor, cursor + fee.fee).intersect(feePart)
                if(shared != null) {
                    run.queue.add(Piece(fee.txid, Piece.FEE, shared.shift(-cursor), piece.coinOffset + (shared.start - flow.start)))
                }
            }
            cursor += fee.fee
        }
    }

    /** The smallest reward of the heights one either side of height and height itself; null when any is not exact. */
    private fun smallestSubsidy(height: Int): Long? {
        var smallest: Long? = null
        for(h in maxOf(0, height - 1)..height + 1) {
            val reward = subsidy(h) ?: return null
            smallest = if(smallest == null || reward < smallest) reward else smallest
        }
        return smallest
    }

    /**
     * The fees of the coinbase's block in block order: from the server when it lists them (Esplora), otherwise, when
     * walkBlocks allows, by fetching each block transaction and its inputs. Null when neither is possible. rewardCoded
     * says whether the reward is known without the fees.
     */
    private suspend fun blockFees(run: Run, coinbase: ChainTx, rewardCoded: Boolean): List<BlockFee>? {
        var blockHash = coinbase.blockHash
        if(blockHash == null) {
            //The app's verbose answers carry no block hash: ask for the hash at the coinbase's height
            run.serverCall()
            blockHash = source.blockHash(coinbase.height)
        }
        //The listing's first request counts here, each later page through the hook (one lookup per request)
        run.serverCall()
        val listed = if(blockHash == null) null else source.blockFees(coinbase.height, blockHash) { run.serverCall() }
        if(listed != null || !run.walkBlocks) {
            return listed
        }
        //Position 0 must be this coinbase: otherwise the server cannot walk blocks (or walks another), and an empty walk
        //would read as a block without fees
        run.serverCall()
        val first = source.txidAt(coinbase.height, 0)
        if(first == null || !first.equals(coinbase.tx.txid, ignoreCase = true)) {
            return null
        }
        val walked = ArrayList<BlockFee>()
        var position = 1
        while(true) {
            run.serverCall()
            val txid = source.txidAt(coinbase.height, position) ?: break
            val blockTx = run.fetch(txid).tx
            if(!rewardCoded && (isHogEx(blockTx) || blockTx.mweb)) {
                //Stricter than the desktop on purpose: with the reward read as total less fees, a skipped fee would count as clean reward
                return null
            }
            if(isHogEx(blockTx)) {
                //Litecoin's MWEB fees are not a HogEx's input-output difference; a coinbase that needs them does not add up
                position++
                continue
            }
            walked.add(BlockFee(txid, UnitFlow.fee(blockTx, run.inputValues(blockTx))))
            position++
        }
        return walked
    }
}
