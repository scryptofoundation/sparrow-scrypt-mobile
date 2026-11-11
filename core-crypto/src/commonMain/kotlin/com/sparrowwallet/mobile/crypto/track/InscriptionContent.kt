package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.ScriptReader

/**
 * An inscription's content as its transactions carry it, read without an indexer. [size] is the body's size in bytes.
 * An incomplete content ([complete] false) is one over the reader's limit: its body is empty (never assembled) and
 * [size] is what it counted, or with [sizeOver] only a bound the body is larger than (the type is then unknown too).
 */
class InscriptionContent(
    val contentType: String?, val contentEncoding: String?, val body: ByteArray,
    val size: Long = body.size.toLong(), val complete: Boolean = true, val sizeOver: Boolean = false
) {
    companion object {
        private const val OP_0 = 0x00
        private const val OP_IF = 0x63
        private const val OP_ENDIF = 0x68
        private val ORD = "ord".encodeToByteArray()
        private val TAG_CONTENT_TYPE = byteArrayOf(1)
        private val TAG_CONTENT_ENCODING = byteArrayOf(9)

        /** The most steps a Doginals chain is walked back to its first. */
        const val MAX_DOGINALS_STEPS = 2_000

        /**
         * The index-th envelope of an ord reveal, counted across inputs as the inscription ids are, or null when there is
         * none, it cannot be read, or its body is over [maxBytes] (the body is not assembled then).
         */
        fun ord(reveal: RawTx.Parsed, index: Int, maxBytes: Int = Int.MAX_VALUE): InscriptionContent? =
            ord(reveal, index, maxBytes, keepOversized = false)

        private fun ord(reveal: RawTx.Parsed, index: Int, maxBytes: Int, keepOversized: Boolean): InscriptionContent? {
            var seen = 0
            for(i in reveal.inputs.indices) {
                for(push in reveal.witnesses.getOrElse(i) { emptyList() }) {
                    //Numbered exactly as InscriptionLocator.ordIds does: only a push holding the minimal envelope bytes counts
                    val counted = InscriptionLocator.envelopeCount(push)
                    if(counted == 0) {
                        continue
                    }
                    val chunks = ScriptReader.chunksUpToError(push)
                    var found = 0
                    for(at in 0 until chunks.size - 2) {
                        if(chunks[at].opcode == OP_0 && chunks[at + 1].opcode == OP_IF && chunks[at + 2].data.contentEquals(ORD)) {
                            found++
                            if(seen++ == index) {
                                return envelope(chunks, at + 3, maxBytes, keepOversized)
                            }
                        }
                    }
                    //InscriptionLocator.ordIds also counts an envelope prefix found in a push's bytes that does not parse as
                    //one; count it here so later indexes line up, though it has no content to read
                    if(found == 0) {
                        repeat(counted) {
                            if(seen++ == index) {
                                return null
                            }
                        }
                    }
                }
            }
            return null
        }

        private fun envelope(chunks: List<ScriptReader.Chunk>, from: Int, maxBytes: Int, keepOversized: Boolean): InscriptionContent? {
            var contentType: String? = null
            var contentEncoding: String? = null
            var i = from
            while(i + 1 < chunks.size) {
                val tag = chunks[i]
                if(tag.opcode == OP_0 || tag.opcode == OP_ENDIF) {
                    break
                }
                val value = chunks[i + 1].data
                val tagData = tag.data
                if(tagData != null && value != null) {
                    if(contentType == null && tagData.contentEquals(TAG_CONTENT_TYPE)) {
                        contentType = value.decodeToString()
                    } else if(contentEncoding == null && tagData.contentEquals(TAG_CONTENT_ENCODING)) {
                        contentEncoding = value.decodeToString()
                    }
                }
                i += 2
            }
            var size = 0L
            val parts = ArrayList<ByteArray>()
            if(i < chunks.size && chunks[i].opcode == OP_0) {
                i++
                while(i < chunks.size && chunks[i].opcode != OP_ENDIF) {
                    val data = chunks[i].data
                    if(data != null) {
                        size += data.size
                        if(size > maxBytes) {
                            if(!keepOversized) {
                                return null
                            }
                            //Counted on, never kept: the body is not assembled
                            parts.clear()
                        } else {
                            parts.add(data)
                        }
                    }
                    i++
                }
            }
            if(size > maxBytes) {
                return InscriptionContent(contentType, contentEncoding, ByteArray(0), size, complete = false)
            }
            return InscriptionContent(contentType, contentEncoding, join(parts, size.toInt()))
        }

        private fun join(parts: List<ByteArray>, size: Int): ByteArray {
            val out = ByteArray(size)
            var at = 0
            for(part in parts) {
                part.copyInto(out, at)
                at += part.size
            }
            return out
        }

        /**
         * Reads an inscription's content from the chain: an ord id from its reveal; a Doginals id by walking input 0 back
         * from the chain's last step ([lastStep], the mint transaction a trace found) to the step the id names. Null when
         * the transactions hold no such inscription, a Doginals id comes without its last step, or the body would be over
         * [maxBytes] (reading stops before it is assembled). Failures reading a transaction throw from the source.
         */
        suspend fun fromChain(inscriptionId: String, lastStep: String?, source: ChainSource, maxBytes: Int): InscriptionContent? =
            readFromChain(inscriptionId, lastStep, source, maxBytes)?.takeIf { it.complete }

        /**
         * As [fromChain], but an inscription over [maxBytes] gives an incomplete content (see [InscriptionContent]) instead
         * of null: its type and counted size, or for a Doginals walk stopped at its allowance only that it is over the
         * limit. The body is never assembled past the limit.
         */
        suspend fun readFromChain(inscriptionId: String, lastStep: String?, source: ChainSource, maxBytes: Int): InscriptionContent? {
            val at = inscriptionId.lastIndexOf('i')
            if(at != 64) {
                return null
            }
            val txid = inscriptionId.substring(0, at)
            val index = inscriptionId.substring(at + 1).toIntOrNull() ?: return null
            if(index < 0 || index.toString() != inscriptionId.substring(at + 1) || !txid.all { it in '0'..'9' || it in 'a'..'f' }) {
                return null
            }
            val named = source.transaction(txid).tx
            val ordIndex = InscriptionLocator.ordIds(named).indexOf(inscriptionId)
            if(ordIndex >= 0) {
                return ord(named, ordIndex, maxBytes, keepOversized = true)
            }
            if(lastStep == null || index != 0) {
                return null
            }
            //Counts the data pushes of each piece (not the signature and redeem script every step also carries); they hold
            //the body plus a small countdown each, so give the walk that much room, the exact limit is checked once the
            //body is read
            val walkLimit = maxBytes.toLong() + maxBytes / 50 + 1_024
            var pieceBytes = 0L
            val steps = ArrayList<RawTx.Parsed>()
            var current = source.transaction(lastStep).tx
            for(n in 0 until MAX_DOGINALS_STEPS) {
                steps.add(current)
                val step = InscriptionLocator.doginals(current) ?: return null
                val pushes = ScriptReader.chunksUpToError(current.scriptSigs.getOrElse(0) { ByteArray(0) })
                for(k in 0 until maxOf(0, pushes.size - 2)) {
                    pieceBytes += pushes[k].data?.size ?: 0
                }
                if(pieceBytes > walkLimit) {
                    return InscriptionContent(null, null, ByteArray(0), maxBytes.toLong(), complete = false, sizeOver = true)
                }
                if(step.genesis) {
                    steps.reverse()
                    return if(current.txid == txid) doginals(steps, maxBytes, keepOversized = true) else null
                }
                current = source.transaction(step.previousTxid).tx
            }
            return null
        }

        /**
         * A Doginals inscription from its chain's transactions, first step to last: each scriptSig's pushes before the
         * signature and the redeem script, joined, are "ord", the part count, the content type, then countdown and part
         * pairs. Null when the body is over [maxBytes], or when the steps do not hold every part the genesis step declares
         * (the desktop reader does not check this; the phone refuses to show part of an inscription as the whole).
         */
        fun doginals(genesisToLast: List<RawTx.Parsed>, maxBytes: Int = Int.MAX_VALUE): InscriptionContent? =
            doginals(genesisToLast, maxBytes, keepOversized = false)

        private fun doginals(genesisToLast: List<RawTx.Parsed>, maxBytes: Int, keepOversized: Boolean): InscriptionContent? {
            val envelope = ArrayList<ScriptReader.Chunk>()
            for(step in genesisToLast) {
                if(step.inputs.isEmpty()) {
                    continue
                }
                val chunks = ScriptReader.chunksUpToError(step.scriptSigs.getOrElse(0) { ByteArray(0) })
                envelope.addAll(chunks.subList(0, maxOf(0, chunks.size - 2)))
            }
            if(envelope.size < 3 || !envelope[0].data.contentEquals(ORD)) {
                return null
            }
            //The genesis step declares how many parts the inscription has: with fewer collected (a chain walked from a step
            //that is not the last, e.g. an indexer-named id whose "last step" is its first) the body would be only part of it
            val declared = InscriptionLocator.number(envelope[1])
            if((envelope.size - 3) % 2 != 0 || ((envelope.size - 3) / 2).toLong() != declared) {
                return null
            }
            val contentType = envelope[2].data?.decodeToString()
            var size = 0L
            val parts = ArrayList<ByteArray>()
            var i = 4
            while(i < envelope.size) {
                val data = envelope[i].data
                if(data != null) {
                    size += data.size
                    if(size > maxBytes) {
                        if(!keepOversized) {
                            return null
                        }
                        parts.clear()
                    } else {
                        parts.add(data)
                    }
                }
                i += 2
            }
            if(size > maxBytes) {
                return InscriptionContent(contentType, null, ByteArray(0), size, complete = false)
            }
            return InscriptionContent(contentType, null, join(parts, size.toInt()))
        }
    }
}
