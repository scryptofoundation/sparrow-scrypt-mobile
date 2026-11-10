package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx
import kotlinx.serialization.Serializable

/** A run of units (sats, koinu, photons) by offset, start included, end excluded. */
@Serializable
data class UnitRange(val start: Long, val end: Long) {
    init {
        require(start >= 0 && end > start) { "Not a unit range: [$start, $end)" }
    }

    val length: Long get() = end - start

    fun overlaps(other: UnitRange): Boolean = start < other.end && other.start < end

    /** The units both ranges hold, or null when they share none. */
    fun intersect(other: UnitRange): UnitRange? {
        val from = maxOf(start, other.start)
        val to = minOf(end, other.end)
        return if(from < to) UnitRange(from, to) else null
    }

    fun shift(delta: Long): UnitRange = UnitRange(start + delta, end + delta)

    override fun toString(): String = "$start-$end"
}

@Serializable
enum class Verdict { CLEAN, INSCRIBED, UNCHECKED }

/**
 * Part of a traced coin, found on an output of some transaction (or in its fee when vout is [FEE]). range is by offset
 * within that output (or fee); coinOffset is the offset in the traced coin that range.start stands for.
 */
@Serializable
data class Piece(val txid: String, val vout: Int, val range: UnitRange, val coinOffset: Long) {
    companion object {
        const val FEE = -1
    }
}

/**
 * An inscription a transaction creates, on the unit at offset in output vout (or in its fee when vout is [Piece.FEE]). vout
 * [ANYWHERE] means the envelope pointer could not be read, so the inscription may sit on any unit of the transaction.
 */
data class Creation(val inscriptionId: String, val vout: Int, val offset: Long) {
    companion object {
        const val ANYWHERE = -2
    }
}

/**
 * An inscription on a coin: its id, the offset of its unit in the coin (-1 when unknown), its number (null unless an
 * indexer gave one), and the transaction, height and time it was minted in.
 */
@Serializable
data class Found(
    val inscriptionId: String,
    val offset: Long,
    val number: Long?,
    val mintTxid: String,
    val mintHeight: Int,
    val mintTime: Long
) {
    fun withOffset(newOffset: Long): Found = copy(offset = newOffset)
}

/** A result already decided for an output, such as a wallet coin checked before; found offsets are within that output. */
data class KnownOutput(val verdict: Verdict, val found: List<Found>)

fun interface KnownResults {
    fun forOutput(txid: String, vout: Int): KnownOutput?

    companion object {
        val NONE = KnownResults { _, _ -> null }
    }
}

/**
 * What a trace showed. reason says why an UNCHECKED coin is not decided; open holds the pieces still to trace, for a
 * later resume; usedChain is true when any transaction came from the server rather than the wallet.
 */
data class TraceResult(
    val verdict: Verdict,
    val found: List<Found>,
    val reason: String?,
    val open: List<Piece>,
    val lookups: Int,
    val usedChain: Boolean
)

/**
 * A transaction as the chain source has it: its height (0 when unconfirmed), block hash and time (null and 0 when
 * unconfirmed), and whether getting it took a server lookup (false for the wallet's own transactions).
 */
data class ChainTx(val tx: RawTx.Parsed, val height: Int, val blockHash: String?, val blockTime: Long, val fromServer: Boolean)

/** A block transaction's fee, listed in block order. */
data class BlockFee(val txid: String, val fee: Long)

/** What the tracer reads from the coin's server. Null means the server cannot say; failures throw from the backend. */
interface ChainSource {
    suspend fun transaction(txid: String): ChainTx

    /** The block's non-coinbase transactions with their fees, in block order, or null when the server cannot list them. */
    suspend fun blockFees(height: Int, blockHash: String): List<BlockFee>?

    /**
     * As the other [blockFees], telling [extraRequest] before each server request past the first (a paged listing), so
     * each counts against the trace's budget; [extraRequest] throws to stop the listing. A source that makes one
     * request (or replays recorded answers) never calls it.
     */
    suspend fun blockFees(height: Int, blockHash: String, extraRequest: () -> Unit): List<BlockFee>? = blockFees(height, blockHash)

    /** The txid at a position in the block at a height (0 is the coinbase), or null past its end or when unsupported. */
    suspend fun txidAt(height: Int, position: Int): String?

    /** The hash of the block at a height on the server's chain, for a transaction it returned without one; null when it cannot say. */
    suspend fun blockHash(height: Int): String? = null
}

/**
 * The mined transactions traces have read, shared between traces of one coin and network, least recently used dropped
 * first. Not thread safe: use it from one coroutine at a time.
 */
class TxCache(private val size: Int = 5_000) {
    private val transactions = HashMap<String, ChainTx>()
    private val order = ArrayDeque<String>() // least recently used first

    fun get(txid: String): ChainTx? {
        val tx = transactions[txid] ?: return null
        order.remove(txid)
        order.addLast(txid)
        return tx
    }

    /**
     * Keeps a mined transaction. An unconfirmed one is not kept: until it is mined its witness can still be swapped for
     * another with the same txid, and its height is not known, so it is read again each time.
     */
    fun put(tx: ChainTx) {
        if(tx.height <= 0) {
            return
        }
        val txid = tx.tx.txid
        if(transactions.put(txid, tx) != null) {
            order.remove(txid)
        }
        order.addLast(txid)
        while(transactions.size > size) {
            transactions.remove(order.removeFirst())
        }
    }
}
