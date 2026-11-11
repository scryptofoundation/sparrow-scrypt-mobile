package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.ScriptReader

/** One Doginals step: whether it starts the inscription's envelope ("ord" first), whether it is the last, and the transaction it spends. */
data class DoginalsStep(val genesis: Boolean, val last: Boolean, val previousTxid: String)

/**
 * Finds the units a transaction puts inscriptions on, by the rules the indexers follow. Errs towards finding one: an
 * envelope that starts but does not parse still counts, as ord counts malformed envelopes.
 */
object InscriptionLocator {
    private const val OP_0 = 0x00
    private const val OP_1 = 0x51
    private const val OP_16 = 0x60
    private const val OP_IF = 0x63
    private const val OP_ENDIF = 0x68

    //OP_FALSE OP_IF OP_PUSHBYTES_3 "ord"
    private val ENVELOPE = byteArrayOf(0x00, 0x63, 0x03, 0x6f, 0x72, 0x64)
    private val ORD = "ord".encodeToByteArray()
    private val TAG_POINTER = byteArrayOf(2)

    //A pointer field that is present but cannot be placed here: the inscription may sit anywhere in the transaction
    private const val UNREADABLE = -1L

    /**
     * The inscriptions an ord reveal (Litecoin, Bellscoin) creates, numbered across the transaction in input then
     * envelope order. Each sits on the unit its pointer names when that is below the outputs' total, otherwise on the
     * first unit of the input that carries it.
     */
    fun ord(tx: RawTx.Parsed, inputValues: List<Long>): List<Creation> {
        val txid = tx.txid
        val outputs = UnitFlow.outputTotal(tx)
        val total = inputValues.sum()

        val creations = ArrayList<Creation>()
        var inputStart = 0L
        for(i in tx.inputs.indices) {
            for(push in tx.witnesses.getOrElse(i) { emptyList() }) {
                if(!contains(push, ENVELOPE)) {
                    continue
                }
                for(pointer in envelopePointers(push)) {
                    if(pointer != null && pointer == UNREADABLE) {
                        creations.add(Creation(txid + "i" + creations.size, Creation.ANYWHERE, -1))
                        continue
                    }
                    val flowOffset = if(pointer != null && pointer < outputs) pointer else inputStart
                    if(flowOffset >= total) {
                        continue
                    }
                    val placed = UnitFlow.place(tx, UnitRange(flowOffset, flowOffset + 1), 0).first()
                    creations.add(Creation(txid + "i" + creations.size, placed.vout, placed.range.start))
                }
            }
            inputStart += inputValues[i]
        }
        return creations
    }

    /** The ids of the inscriptions an ord reveal creates, in the same order as [ord], without needing input values. */
    fun ordIds(tx: RawTx.Parsed): List<String> {
        val ids = ArrayList<String>()
        for(i in tx.inputs.indices) {
            for(push in tx.witnesses.getOrElse(i) { emptyList() }) {
                if(contains(push, ENVELOPE)) {
                    for(n in envelopePointers(push).size downTo 1) {
                        ids.add(tx.txid + "i" + ids.size)
                    }
                }
            }
        }
        return ids
    }

    /** How many envelopes [ordIds] counts in a witness push. */
    internal fun envelopeCount(push: ByteArray): Int = if(contains(push, ENVELOPE)) envelopePointers(push).size else 0

    /**
     * One entry per envelope in a witness push: its pointer, or null when it has none. Chunks are read as far as the
     * script parses (as drongo's Script keeps them). When it has no whole envelope, each envelope prefix found in the
     * bytes counts once, pointerless.
     */
    private fun envelopePointers(push: ByteArray): List<Long?> {
        val pointers = ArrayList<Long?>()
        val chunks = ScriptReader.chunksUpToError(push)
        for(i in 0 until chunks.size - 2) {
            if(chunks[i].opcode == OP_0 && chunks[i + 1].opcode == OP_IF && chunks[i + 2].data.contentEquals(ORD)) {
                pointers.add(pointer(chunks, i + 3))
            }
        }
        if(pointers.isEmpty()) {
            var i = 0
            while(i + ENVELOPE.size <= push.size) {
                if(matchesAt(push, i, ENVELOPE)) {
                    pointers.add(null)
                }
                i++
            }
        }
        return pointers
    }

    /**
     * The pointer among an envelope's fields (tag, value pairs up to the body's OP_0 or OP_ENDIF) as ord reads it: the
     * first pointer tag counts, tags and values written as OP_1 to OP_16 are the bytes [n], and bytes past the eighth must
     * be zero. Null without a pointer, UNREADABLE when the first pointer's value is not a u64 this code can place.
     */
    private fun pointer(chunks: List<ScriptReader.Chunk>, from: Int): Long? {
        var i = from
        while(i + 1 < chunks.size) {
            val tag = chunks[i]
            if(tag.opcode == OP_0 || tag.opcode == OP_ENDIF) {
                break
            }
            if(bytes(tag)?.contentEquals(TAG_POINTER) != true) {
                i += 2
                continue
            }
            val value = bytes(chunks[i + 1]) ?: return UNREADABLE
            for(b in 8 until value.size) {
                if(value[b] != 0.toByte()) {
                    return UNREADABLE
                }
            }
            var read = 0L
            for(b in minOf(value.size, 8) - 1 downTo 0) {
                read = (read shl 8) or (value[b].toLong() and 0xff)
            }
            return if(read < 0) UNREADABLE else read
        }
        return null
    }

    /** A push's bytes, with OP_0 as no bytes and OP_1 to OP_16 as the single byte n; null for other opcodes. */
    private fun bytes(chunk: ScriptReader.Chunk): ByteArray? {
        if(chunk.opcode == OP_0) {
            return ByteArray(0)
        }
        if(chunk.opcode in OP_1..OP_16) {
            return byteArrayOf((chunk.opcode - OP_1 + 1).toByte())
        }
        return chunk.data
    }

    /** The Doginals step input 0 of the transaction reveals, if it is one. */
    fun doginals(tx: RawTx.Parsed): DoginalsStep? {
        if(tx.inputs.isEmpty()) {
            return null
        }
        val input = tx.inputs.first()
        //The pushes alone decide, as ord-dogecoin reads them (it does not check the redeem script): the piece's pushes,
        //then a signature and the redeem script. A later step can only be told apart by walking back to an "ord" first
        //step, which the tracer does; anything shaped like a piece here is a candidate
        val chunks = ScriptReader.chunksUpToError(tx.scriptSigs.getOrElse(0) { ByteArray(0) })
        if(chunks.size < 4 || chunks.last().data == null) {
            return null
        }
        val piece = chunks.subList(0, chunks.size - 2)
        val genesis = piece.first().data.contentEquals(ORD)
        if(if(genesis) piece.size < 3 || piece.size % 2 == 0 else piece.size < 2 || piece.size % 2 == 1) {
            return null
        }
        val last = number(piece[piece.size - 2]) == 0L
        return DoginalsStep(genesis, last, input.prevTxid)
    }

    /** A Doginals number push: OP_0, OP_1 to OP_16, or little-endian bytes. */
    internal fun number(chunk: ScriptReader.Chunk): Long {
        if(chunk.opcode == OP_0) {
            return 0
        }
        if(chunk.opcode in OP_1..OP_16) {
            return (chunk.opcode - OP_1 + 1).toLong()
        }
        val data = chunk.data
        if(data == null || data.isEmpty()) {
            return 0
        }
        var value = 0L
        for(b in minOf(data.size, 8) - 1 downTo 0) {
            value = (value shl 8) or (data[b].toLong() and 0xff)
        }
        return value
    }

    private fun matchesAt(data: ByteArray, at: Int, pattern: ByteArray): Boolean {
        for(k in pattern.indices) {
            if(data[at + k] != pattern[k]) {
                return false
            }
        }
        return true
    }

    private fun contains(data: ByteArray, pattern: ByteArray): Boolean {
        for(i in 0..data.size - pattern.size) {
            if(matchesAt(data, i, pattern)) {
                return true
            }
        }
        return false
    }
}
