package com.sparrowwallet.mobile.crypto

/**
 * Litecoin transaction model: serialization (legacy + segwit witness format), txid,
 * and BIP143 sighash. Verified byte-for-byte against drongo (TxParityTest).
 */

internal class ByteWriter {
    private var buf = ByteArray(256)
    private var size = 0

    fun bytes(b: ByteArray): ByteWriter {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
        return this
    }

    fun u8(v: Int): ByteWriter = bytes(byteArrayOf(v.toByte()))

    fun u32le(v: Long): ByteWriter = bytes(ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() })

    fun u64le(v: Long): ByteWriter = bytes(ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() })

    fun varInt(v: Long): ByteWriter = when {
        v < 0xFD -> u8(v.toInt())
        v <= 0xFFFF -> u8(0xFD).bytes(ByteArray(2) { ((v ushr (8 * it)) and 0xFF).toByte() })
        v <= 0xFFFFFFFFL -> u8(0xFE).u32le(v)
        else -> u8(0xFF).u64le(v)
    }

    fun varBytes(b: ByteArray): ByteWriter = varInt(b.size.toLong()).bytes(b)

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun ensure(n: Int) {
        if(size + n > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        }
    }
}

class TxInput(
    val prevTxid: String, // display (big-endian) hex, as shown by explorers
    val vout: Long,
    val sequence: Long = 0xFFFFFFFDL,
    var scriptSig: ByteArray = ByteArray(0),
    var witness: List<ByteArray> = emptyList()
) {
    internal fun outpoint(): ByteArray = prevTxid.hexToBytes().reversedArray() + ByteWriter().u32le(vout).toByteArray()
}

class TxOutput(val value: Long, val script: ByteArray) {
    internal fun serialize(): ByteArray = ByteWriter().u64le(value).varBytes(script).toByteArray()
}

class Tx(
    val version: Long,
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    val locktime: Long = 0
) {
    /**
     * Litecoin MWEB extension blob (as produced by mwebd), serialized between the witness
     * section and locktime with flag bit 0x08 — used by peg-in transactions. Not part of
     * the txid or the BIP143 sighash.
     */
    var mwebExtension: ByteArray? = null

    fun serialize(includeWitness: Boolean = true): ByteArray {
        val hasWitness = includeWitness && inputs.any { it.witness.isNotEmpty() }
        val hasMweb = includeWitness && mwebExtension != null
        val flag = (if(hasWitness) 1 else 0) or (if(hasMweb) 8 else 0)
        val w = ByteWriter().u32le(version)
        if(flag != 0) {
            w.u8(0).u8(flag) // segwit marker + extension flags
        }
        w.varInt(inputs.size.toLong())
        for(input in inputs) {
            w.bytes(input.outpoint()).varBytes(input.scriptSig).u32le(input.sequence)
        }
        w.varInt(outputs.size.toLong())
        for(output in outputs) {
            w.bytes(output.serialize())
        }
        if(hasWitness) {
            for(input in inputs) {
                w.varInt(input.witness.size.toLong())
                for(push in input.witness) {
                    w.varBytes(push)
                }
            }
        }
        if(hasMweb) {
            w.bytes(mwebExtension!!)
        }
        return w.u32le(locktime).toByteArray()
    }

    fun txid(): String = sha256d(serialize(false)).reversedArray().toHex()

    /**
     * Original (pre-segwit) SIGHASH_ALL digest: the transaction with every scriptSig emptied
     * except this input's, which carries the output script being spent; no witness data.
     */
    fun legacySighashAll(inputIndex: Int, prevOutputScript: ByteArray): ByteArray {
        val stripped = inputs.mapIndexed { i, input ->
            TxInput(input.prevTxid, input.vout, input.sequence, if(i == inputIndex) prevOutputScript else ByteArray(0))
        }
        val preimage = Tx(version, stripped, outputs, locktime).serialize(includeWitness = false) + ByteWriter().u32le(1).toByteArray()
        return sha256d(preimage)
    }

    /** BIP143 SIGHASH_ALL digest for a segwit v0 input. */
    fun bip143SighashAll(inputIndex: Int, scriptCode: ByteArray, amount: Long): ByteArray {
        val prevouts = ByteWriter()
        val sequences = ByteWriter()
        for(input in inputs) {
            prevouts.bytes(input.outpoint())
            sequences.u32le(input.sequence)
        }
        val outs = ByteWriter()
        for(output in outputs) {
            outs.bytes(output.serialize())
        }
        val input = inputs[inputIndex]
        val preimage = ByteWriter()
            .u32le(version)
            .bytes(sha256d(prevouts.toByteArray()))
            .bytes(sha256d(sequences.toByteArray()))
            .bytes(input.outpoint())
            .varBytes(scriptCode)
            .u64le(amount)
            .u32le(input.sequence)
            .bytes(sha256d(outs.toByteArray()))
            .u32le(locktime)
            .u32le(1) // SIGHASH_ALL
        return sha256d(preimage.toByteArray())
    }

    /**
     * BIP341 signature hash for a Taproot key-path spend (ext_flag 0, no annex). [hashType] is
     * 0 (DEFAULT) or 1 (ALL). [spentAmounts] and [spentScripts] cover every input, in order.
     */
    fun taprootKeyPathSighash(inputIndex: Int, spentAmounts: List<Long>, spentScripts: List<ByteArray>, hashType: Int = 0): ByteArray {
        require(hashType == 0 || hashType == 1) { "Only SIGHASH_DEFAULT and SIGHASH_ALL are supported" }
        require(inputIndex in inputs.indices) { "No input $inputIndex" }
        require(spentAmounts.size == inputs.size && spentScripts.size == inputs.size) { "One spent amount and script per input" }
        val prevouts = ByteWriter()
        val amounts = ByteWriter()
        val scripts = ByteWriter()
        val sequences = ByteWriter()
        for((i, input) in inputs.withIndex()) {
            prevouts.bytes(input.outpoint())
            amounts.u64le(spentAmounts[i])
            scripts.varBytes(spentScripts[i])
            sequences.u32le(input.sequence)
        }
        val outs = ByteWriter()
        for(output in outputs) {
            outs.bytes(output.serialize())
        }
        val msg = ByteWriter()
            .u8(0x00) // epoch
            .u8(hashType)
            .u32le(version)
            .u32le(locktime)
            .bytes(sha256(prevouts.toByteArray()))
            .bytes(sha256(amounts.toByteArray()))
            .bytes(sha256(scripts.toByteArray()))
            .bytes(sha256(sequences.toByteArray()))
            .bytes(sha256(outs.toByteArray()))
            .u8(0x00) // spend_type: key path, no annex
            .u32le(inputIndex.toLong())
        return Taproot.taggedHash("TapSighash", msg.toByteArray())
    }
}

object Scripts {
    fun p2wpkhOutput(pubKey: ByteArray): ByteArray = byteArrayOf(0x00, 0x14) + hash160(pubKey)

    /** P2PKH scriptPubKey: OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG */
    fun p2pkhOutput(pubKey: ByteArray): ByteArray =
        byteArrayOf(0x76.toByte(), 0xA9.toByte(), 0x14) + hash160(pubKey) + byteArrayOf(0x88.toByte(), 0xAC.toByte())

    /** P2SH-P2WPKH scriptPubKey: OP_HASH160 <hash160(redeemScript)> OP_EQUAL */
    fun p2shP2wpkhOutput(pubKey: ByteArray): ByteArray =
        byteArrayOf(0xA9.toByte(), 0x14) + hash160(byteArrayOf(0x00, 0x14) + hash160(pubKey)) + byteArrayOf(0x87.toByte())

    /** P2TR scriptPubKey: OP_1 PUSH32 <BIP86 output key> */
    fun p2trOutput(pubKey33: ByteArray): ByteArray = byteArrayOf(0x51, 0x20) + Taproot.outputKey(pubKey33)

    /** BIP143 scriptCode for spending a P2WPKH input (the implied P2PKH script). */
    fun p2pkhScriptCode(pubKey: ByteArray): ByteArray = p2pkhOutput(pubKey)

    /** The scriptPubKey a key pays to under [scriptType]. */
    fun outputScript(pubKey: ByteArray, scriptType: ScriptType): ByteArray = when(scriptType) {
        ScriptType.P2PKH -> p2pkhOutput(pubKey)
        ScriptType.P2SH_P2WPKH -> p2shP2wpkhOutput(pubKey)
        ScriptType.P2WPKH -> p2wpkhOutput(pubKey)
        ScriptType.P2TR -> p2trOutput(pubKey)
    }
}

object Ecdsa {
    /** DER-encoded low-S signature with the sighash byte appended (0x01 = SIGHASH_ALL). */
    fun signForInput(messageHash: ByteArray, privKey: ByteArray, sigHashType: Int = 1): ByteArray {
        val compact = secpSign(messageHash, privKey)
        return derEncode(compact.copyOfRange(0, 32), compact.copyOfRange(32, 64)) + byteArrayOf(sigHashType.toByte())
    }

    private fun derEncode(r: ByteArray, s: ByteArray): ByteArray {
        val rEnc = derInt(r)
        val sEnc = derInt(s)
        return byteArrayOf(0x30, (rEnc.size + sEnc.size).toByte()) + rEnc + sEnc
    }

    private fun derInt(value: ByteArray): ByteArray {
        var v = value.dropWhile { it.toInt() == 0 }.toByteArray()
        if(v.isEmpty() || v[0].toInt() < 0) {
            v = byteArrayOf(0) + v // keep the integer positive
        }
        return byteArrayOf(0x02, v.size.toByte()) + v
    }
}

object Schnorr {
    /**
     * BIP340 signature (64 bytes, zero aux) of [hash] with the BIP86-tweaked [privKey], checked
     * against the output key before it is returned. SIGHASH_DEFAULT: no sighash byte appended.
     */
    fun signForInput(hash: ByteArray, privKey: ByteArray): ByteArray {
        val tweaked = Taproot.tweakPrivKey(privKey)
        val sig = secpSignSchnorr(hash, tweaked)
        val outputKey = Taproot.xOnly(secpPublicKeyCreate(tweaked))
        check(secpVerifySchnorr(sig, hash, outputKey)) { "Generated signature failed verification" }
        return sig
    }
}

/**
 * Reads any serialized Litecoin transaction far enough to know its txid and outputs:
 * legacy scriptSigs, segwit witnesses (flag 0x01) and the MWEB extension (flag 0x08,
 * which runs from after the witnesses to the locktime). Used to check a PSBT's
 * non-witness UTXO, so it does not go through [Tx], which assumes unsigned inputs.
 */
object RawTx {
    /** [scriptSigs] and [witnesses] are per input, for [CarrierScan]; a witness is empty for a legacy input. */
    class Parsed(
        val txid: String,
        val outputs: List<TxOutput>,
        val scriptSigs: List<ByteArray> = emptyList(),
        val witnesses: List<List<ByteArray>> = emptyList()
    )

    fun parse(bytes: ByteArray): Parsed {
        val r = ByteReader(bytes)
        val version = r.bytes(4)
        var flag = 0
        val afterVersion = r.remaining()
        if(r.remaining() >= 2 && bytes[4].toInt() == 0 && bytes[5].toInt() != 0) {
            r.bytes(1)
            flag = r.bytes(1)[0].toInt() and 0xFF
        }
        val bodyStart = 4 + (afterVersion - r.remaining())

        val inputCount = r.varInt()
        require(inputCount in 0..100_000) { "Unreasonable input count" }
        val scriptSigs = List(inputCount.toInt()) {
            r.bytes(36)   // outpoint
            val scriptSig = r.varBytes()
            r.bytes(4)    // sequence
            scriptSig
        }
        val outputCount = r.varInt()
        require(outputCount in 0..100_000) { "Unreasonable output count" }
        val outputs = List(outputCount.toInt()) { TxOutput(r.u64le(), r.varBytes()) }
        val bodyEnd = bytes.size - r.remaining()

        val witnesses = if(flag and 0x01 != 0) {
            List(inputCount.toInt()) {
                val items = r.varInt()
                require(items in 0..100_000) { "Unreasonable witness size" }
                List(items.toInt()) { r.varBytes() }
            }
        } else {
            List(inputCount.toInt()) { emptyList() }
        }
        if(flag and 0x08 != 0) {
            require(r.remaining() >= 4) { "Truncated transaction" }
            r.bytes(r.remaining() - 4) // MWEB extension: not part of the txid
        }
        val locktime = r.bytes(4)
        require(r.remaining() == 0) { "Trailing bytes after transaction" }

        val stripped = version + bytes.copyOfRange(bodyStart, bodyEnd) + locktime
        return Parsed(sha256d(stripped).reversedArray().toHex(), outputs, scriptSigs, witnesses)
    }
}
