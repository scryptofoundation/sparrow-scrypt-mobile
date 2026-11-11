package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.ScriptReader

/** An inscription's content as its transactions carry it, read without an indexer. */
class InscriptionContent(val contentType: String?, val contentEncoding: String?, val body: ByteArray) {
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
        fun ord(reveal: RawTx.Parsed, index: Int, maxBytes: Int = Int.MAX_VALUE): InscriptionContent? {
            var seen = 0
            for(i in reveal.inputs.indices) {
                for(push in reveal.witnesses.getOrElse(i) { emptyList() }) {
                    val chunks = ScriptReader.chunksUpToError(push)
                    var found = 0
                    for(at in 0 until chunks.size - 2) {
                        if(chunks[at].opcode == OP_0 && chunks[at + 1].opcode == OP_IF && chunks[at + 2].data.contentEquals(ORD)) {
                            found++
                            if(seen++ == index) {
                                return envelope(chunks, at + 3, maxBytes)
                            }
                        }
                    }
                    //InscriptionLocator.ordIds also counts an envelope prefix found in a push's bytes that does not parse as
                    //one; count it here so later indexes line up, though it has no content to read
                    if(found == 0) {
                        repeat(InscriptionLocator.envelopeCount(push)) {
                            if(seen++ == index) {
                                return null
                            }
                        }
                    }
                }
            }
            return null
        }

        private fun envelope(chunks: List<ScriptReader.Chunk>, from: Int, maxBytes: Int): InscriptionContent? {
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
                            return null
                        }
                        parts.add(data)
                    }
                    i++
                }
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
        suspend fun fromChain(inscriptionId: String, lastStep: String?, source: ChainSource, maxBytes: Int): InscriptionContent? {
            val at = inscriptionId.lastIndexOf('i')
            if(at != 64) {
                return null
            }
            val txid = inscriptionId.substring(0, at)
            val index = inscriptionId.substring(at + 1).toIntOrNull() ?: return null
            if(index < 0 || !txid.all { it in '0'..'9' || it in 'a'..'f' }) {
                return null
            }
            val named = source.transaction(txid).tx
            val ordIndex = InscriptionLocator.ordIds(named).indexOf(inscriptionId)
            if(ordIndex >= 0) {
                return ord(named, ordIndex, maxBytes)
            }
            if(lastStep == null || index != 0) {
                return null
            }
            //The pieces carry the body plus a small countdown and framing each; give the walk that much room, the exact
            //limit is checked once the body is read
            val walkLimit = maxBytes.toLong() + maxBytes / 50 + 1_024
            var pieceBytes = 0L
            val steps = ArrayList<RawTx.Parsed>()
            var current = source.transaction(lastStep).tx
            for(n in 0 until MAX_DOGINALS_STEPS) {
                steps.add(current)
                val step = InscriptionLocator.doginals(current) ?: return null
                pieceBytes += current.scriptSigs.firstOrNull()?.size ?: 0
                if(pieceBytes > walkLimit) {
                    return null
                }
                if(step.genesis) {
                    steps.reverse()
                    return if(current.txid == txid) doginals(steps, maxBytes) else null
                }
                current = source.transaction(step.previousTxid).tx
            }
            return null
        }

        /**
         * A Doginals inscription from its chain's transactions, first step to last: each scriptSig's pushes before the
         * signature and the redeem script, joined, are "ord", the part count, the content type, then countdown and part
         * pairs. Null when the body is over [maxBytes].
         */
        fun doginals(genesisToLast: List<RawTx.Parsed>, maxBytes: Int = Int.MAX_VALUE): InscriptionContent? {
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
            val contentType = envelope[2].data?.decodeToString()
            var size = 0L
            val parts = ArrayList<ByteArray>()
            var i = 4
            while(i < envelope.size) {
                val data = envelope[i].data
                if(data != null) {
                    size += data.size
                    if(size > maxBytes) {
                        return null
                    }
                    parts.add(data)
                }
                i += 2
            }
            return InscriptionContent(contentType, null, join(parts, size.toInt()))
        }
    }
}
