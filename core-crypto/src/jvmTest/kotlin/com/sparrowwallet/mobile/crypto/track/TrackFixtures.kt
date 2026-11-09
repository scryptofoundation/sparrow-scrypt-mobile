package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes

/** Transactions for the tracker tests, built as raw bytes and read back with [RawTx.parse]. */
object TrackFixtures {
    /** A one byte output script (OP_1). */
    val PLAIN = byteArrayOf(0x51)

    /** A 64 character txid made of the number n. */
    fun hash(n: Int): String = n.toString(16).padStart(64, '0')

    private fun varInt(n: Int): ByteArray = when {
        n < 0xFD -> byteArrayOf(n.toByte())
        else -> byteArrayOf(0xFD.toByte(), (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
    }

    private fun le(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { ((value shr (8 * it)) and 0xFF).toByte() }

    /**
     * One input per entry of spends (txid, vout), paying the given output values to [PLAIN]. scriptSigs and witnesses are
     * per input (null for none: empty scriptSig, no witness); any non-empty witness list makes the transaction segwit.
     */
    fun tx(
        spends: List<Pair<String, Int>>,
        outputs: List<Long>,
        scriptSigs: List<ByteArray>? = null,
        witnesses: List<List<ByteArray>>? = null,
        version: Int = 2,
        locktime: Long = 0
    ): RawTx.Parsed {
        val segwit = witnesses != null && witnesses.any { it.isNotEmpty() }
        val body = ArrayList<Byte>()
        fun add(b: ByteArray) = b.forEach { body.add(it) }
        add(le(version.toLong(), 4))
        if(segwit) add(byteArrayOf(0, 1))
        add(varInt(spends.size))
        spends.forEachIndexed { i, (txid, vout) ->
            add(txid.hexToBytes().reversedArray())
            add(le(vout.toLong(), 4))
            val sig = scriptSigs?.get(i) ?: ByteArray(0)
            add(varInt(sig.size)); add(sig)
            add(le(0xFFFFFFFFL, 4))
        }
        add(varInt(outputs.size))
        for(value in outputs) {
            add(le(value, 8)); add(varInt(PLAIN.size)); add(PLAIN)
        }
        if(segwit) {
            for(i in spends.indices) {
                val items = witnesses!!.getOrElse(i) { emptyList() }
                add(varInt(items.size))
                items.forEach { add(varInt(it.size)); add(it) }
            }
        }
        add(le(locktime, 4))
        return RawTx.parse(body.toByteArray())
    }

    /** A plain spend of the given outpoints. */
    fun spend(spends: List<Pair<String, Int>>, vararg outputs: Long): RawTx.Parsed = tx(spends, outputs.toList())

    /** A transaction spending output 0 of each given previous txid. */
    fun spendZero(prevs: List<String>, vararg outputs: Long): RawTx.Parsed = tx(prevs.map { it to 0 }, outputs.toList())

    // ---- Script pieces, each chunk as its encoded bytes ----

    /** Key stand-ins: a fixed 32 byte x-only key and 33 byte compressed key. */
    private val XONLY = ByteArray(32) { 4 }
    private val PUBKEY = ByteArray(33) { 4 }

    const val OP_0 = 0x00
    const val OP_IF = 0x63
    const val OP_ENDIF = 0x68
    const val OP_CHECKSIG = 0xac
    const val OP_CHECKSIGVERIFY = 0xad
    const val OP_DROP = 0x75
    const val OP_TRUE = 0x51

    /** A single opcode, as one chunk. */
    fun op(opcode: Int): ByteArray = byteArrayOf(opcode.toByte())

    /** OP_1 to OP_16 for n in 1..16. */
    fun opNumber(n: Int): ByteArray = op(0x50 + n)

    /** A push: OP_0 when empty, else the shortest direct or PUSHDATA1/2 form, never an OP_N (as doginals.js and ord write them). */
    fun push(data: ByteArray): ByteArray = when {
        data.isEmpty() -> op(OP_0)
        data.size < 0x4c -> byteArrayOf(data.size.toByte()) + data
        data.size <= 0xff -> byteArrayOf(0x4c, data.size.toByte()) + data
        else -> byteArrayOf(0x4d, (data.size and 0xFF).toByte(), ((data.size shr 8) and 0xFF).toByte()) + data
    }

    fun script(chunks: List<ByteArray>): ByteArray = chunks.fold(ByteArray(0)) { acc, c -> acc + c }

    private val ORD_BYTES = "ord".encodeToByteArray()

    /** The key and OP_CHECKSIG that open an ord reveal script. */
    private fun envelopeHead(): List<ByteArray> = listOf(push(XONLY), op(OP_CHECKSIG))

    /** The chunks of an ord envelope from OP_FALSE on, with extra fields (tag then value chunks) after the content type. */
    fun envelopeBody(text: String, fields: List<ByteArray> = emptyList()): List<ByteArray> =
        listOf(op(OP_0), op(OP_IF), push(ORD_BYTES), push(byteArrayOf(1)), push("text/plain;charset=utf-8".encodeToByteArray())) +
            fields + listOf(op(OP_0), push(text.encodeToByteArray()), op(OP_ENDIF))

    /** An ord reveal script (as chunks) holding one text envelope. */
    fun envelope(text: String): List<ByteArray> = envelopeHead() + envelopeBody(text)

    /** An ord envelope with a pointer field (tag 2, little endian). */
    fun envelopeWithPointer(text: String, pointer: Long): List<ByteArray> =
        envelopeHead() + envelopeBody(text, listOf(push(byteArrayOf(2)), push(littleEndian(pointer))))

    /** An ord envelope with one extra field, its tag and value chunks given as written. */
    fun envelopeWithFields(text: String, tag: ByteArray, value: ByteArray): List<ByteArray> =
        envelopeHead() + envelopeBody(text, listOf(tag, value))

    fun littleEndian(value: Long): ByteArray {
        val bytes = ArrayList<Byte>()
        var v = value
        while(v != 0L) { bytes.add((v and 0xFF).toByte()); v = v ushr 8 }
        return bytes.toByteArray()
    }

    /** The witness of a taproot script-path spend of the script: signature, script, control block stand-ins. */
    fun revealWitness(script: ByteArray): List<ByteArray> = listOf(ByteArray(64), script, ByteArray(33))

    /** One input per spend with the given reveal scripts (null for none) in their witnesses, paying the outputs. */
    fun reveal(spends: List<Pair<String, Int>>, scripts: List<ByteArray?>, vararg outputs: Long): RawTx.Parsed =
        tx(spends, outputs.toList(), witnesses = scripts.map { if(it == null) emptyList() else revealWitness(it) })

    // ---- Doginals chains ----

    private const val MAX_PART = 240
    private const val MAX_PIECE = 1500

    /** doginals.js numberToChunk: 0 is OP_0, 1..16 OP_N, then one byte below 128, else two bytes little-endian. */
    private fun number(n: Int): ByteArray = when {
        n == 0 -> op(OP_0)
        n <= 16 -> opNumber(n)
        n < 128 -> byteArrayOf(1, n.toByte())
        else -> byteArrayOf(2, (n % 256).toByte(), (n / 256).toByte())
    }

    private fun minimalPush(data: ByteArray): Boolean = data.size != 1 || !((data[0] in 1..16) || (data[0].toInt() and 0xFF) == 0x81)

    /** The pieces of a Doginals envelope for a body, cut as drongo's DoginalsMint.pieces does: each a list of encoded chunks. */
    fun doginalsPieces(contentType: String, body: ByteArray): List<List<ByteArray>> {
        val parts = ArrayList<ByteArray>()
        var from = 0
        while(from < body.size) { parts.add(body.copyOfRange(from, minOf(from + MAX_PART, body.size))); from += MAX_PART }
        if(!minimalPush(parts.last())) {
            val before = parts[parts.size - 2]
            parts[parts.size - 2] = before.copyOf(before.size - 1)
            parts[parts.size - 1] = byteArrayOf(before.last(), parts.last()[0])
        }
        val envelope = ArrayDeque<ByteArray>()
        envelope.add(push(ORD_BYTES)); envelope.add(number(parts.size)); envelope.add(push(contentType.encodeToByteArray()))
        for(n in parts.indices) { envelope.add(number(parts.size - n - 1)); envelope.add(push(parts[n])) }
        val pieces = ArrayList<List<ByteArray>>()
        while(envelope.isNotEmpty()) {
            val piece = ArrayList<ByteArray>()
            if(pieces.isEmpty()) piece.add(envelope.removeFirst())
            while(script(piece).size <= MAX_PIECE && envelope.isNotEmpty()) {
                piece.add(envelope.removeFirst()); piece.add(envelope.removeFirst())
            }
            if(script(piece).size > MAX_PIECE) {
                envelope.addFirst(piece.removeLast()); envelope.addFirst(piece.removeLast())
            }
            pieces.add(piece)
        }
        return pieces
    }

    /** A Doginals redeem script: key, CHECKSIGVERIFY, one DROP per push of the piece, TRUE. */
    fun doginalsRedeem(piece: List<ByteArray>): ByteArray =
        script(listOf(push(PUBKEY), op(OP_CHECKSIGVERIFY)) + piece.map { op(OP_DROP) } + op(OP_TRUE))

    /** The scriptSig chunks of a Doginals step: the piece's pushes, a stand-in signature, the redeem script. */
    fun doginalsStepChunks(piece: List<ByteArray>): List<ByteArray> = piece + push(ByteArray(72)) + push(doginalsRedeem(piece))

    /** Every step of a Doginals chain for a body, the first spending output 0 of funding; each pays 100,000 to a plain output. */
    fun doginalsSteps(body: ByteArray, funding: String): List<RawTx.Parsed> {
        val steps = ArrayList<RawTx.Parsed>()
        var previous = funding
        for(piece in doginalsPieces("image/png", body)) {
            val step = tx(listOf(previous to 0), listOf(100_000L), scriptSigs = listOf(script(doginalsStepChunks(piece))), version = 1)
            steps.add(step)
            previous = step.txid
        }
        return steps
    }

    /** A deterministic body of the given length. */
    fun body(length: Int): ByteArray = kotlin.random.Random(length).nextBytes(length)
}
