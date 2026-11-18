package com.sparrowwallet.mobile.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * BIP174 partially-signed transactions for the external-signer flow, scoped to what this wallet
 * produces: single-key Legacy (P2PKH), Nested Segwit (P2SH-P2WPKH) and Native Segwit (P2WPKH)
 * inputs with BIP32 derivations, and BIP86 Taproot (P2TR) inputs with BIP371 Taproot derivations.
 * Segwit inputs carry a witness utxo (and nested ones their redeem script); any input but a
 * Taproot one can carry its full previous transaction, which a Legacy input must.
 * The creator emits everything a hardware wallet or desktop Sparrow needs to sign;
 * the finalizer accepts the signed result and extracts the network transaction.
 */
object Psbt {
    private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte()) // "psbt\xff"

    private const val GLOBAL_UNSIGNED_TX = 0x00
    private const val IN_NON_WITNESS_UTXO = 0x00
    private const val IN_WITNESS_UTXO = 0x01
    private const val IN_PARTIAL_SIG = 0x02
    private const val IN_SIGHASH_TYPE = 0x03
    private const val IN_REDEEM_SCRIPT = 0x04
    private const val IN_BIP32_DERIVATION = 0x06
    private const val IN_FINAL_SCRIPTSIG = 0x07
    private const val IN_FINAL_SCRIPTWITNESS = 0x08
    private const val IN_TAP_KEY_SIG = 0x13
    private const val IN_TAP_LEAF_SCRIPT = 0x15
    private const val IN_TAP_BIP32_DERIVATION = 0x16
    private const val IN_TAP_INTERNAL_KEY = 0x17
    private const val OUT_BIP32_DERIVATION = 0x02
    private const val OUT_TAP_INTERNAL_KEY = 0x05
    private const val OUT_TAP_BIP32_DERIVATION = 0x07

    class PsbtInput(
        val witnessUtxoValue: Long?,
        val witnessUtxoScript: ByteArray?,
        /** compressed pubkey hex → signature bytes (DER + sighash byte). */
        val partialSigs: Map<String, ByteArray>,
        /** pubkey hex → master fingerprint (4) + path child numbers. */
        val derivations: Map<String, Pair<ByteArray, List<Long>>>,
        val finalWitness: List<ByteArray>?,
        /** The full previous transaction, when the creator included it. */
        val nonWitnessUtxo: ByteArray? = null,
        /** Every pair this phone does not interpret, in original order. */
        val unknown: List<Pair<ByteArray, ByteArray>> = emptyList(),
        /** Every pair as read, so re-encoding never drops or reorders anything. */
        internal val entries: List<Pair<ByteArray, ByteArray>> = emptyList(),
        /** A nested segwit input's redeem script (0014<key hash>). */
        val redeemScript: ByteArray? = null,
        /** A scriptSig another signer already finalized. */
        val finalScriptSig: ByteArray? = null,
        /** The signature hash type the creator asks for (0x03), or null when it names none. */
        val sighashType: Int? = null,
        /** A Taproot key-path signature (0x13): 64 bytes, or 65 ending in a hash type other than DEFAULT. */
        val tapKeySig: ByteArray? = null,
        /** x-only key hex → master fingerprint (4) + path child numbers (0x16); the leaf hashes are skipped. */
        val tapDerivations: Map<String, Pair<ByteArray, List<Long>>> = emptyMap(),
        /** The Taproot internal key, x only (0x17). */
        val tapInternalKey: ByteArray? = null,
        /** The input carries a Taproot leaf script (0x15): a script-path spend. */
        val hasLeafScripts: Boolean = false
    ) {
        /** The output this input spends: from the witness utxo, else from the previous transaction. */
        fun spentOutput(prevTxid: String, vout: Long): TxOutput? =
            if(witnessUtxoScript != null && witnessUtxoValue != null) TxOutput(witnessUtxoValue, witnessUtxoScript)
            else nonWitnessUtxo?.let { raw -> runCatching { RawTx.parse(raw) }.getOrNull()
                ?.takeIf { it.txid == prevTxid }?.outputs?.getOrNull(vout.toInt()) }
    }

    class PsbtOutput(
        /** pubkey hex → master fingerprint (4) + path child numbers. */
        val derivations: Map<String, Pair<ByteArray, List<Long>>>,
        val unknown: List<Pair<ByteArray, ByteArray>>,
        /** Every pair as read, so re-encoding never drops or reorders anything. */
        internal val entries: List<Pair<ByteArray, ByteArray>> = emptyList(),
        /** x-only key hex → master fingerprint (4) + path child numbers (0x07); the leaf hashes are skipped. */
        val tapDerivations: Map<String, Pair<ByteArray, List<Long>>> = emptyMap()
    )

    class Parsed(
        val unsignedTxBytes: ByteArray,
        val tx: Tx,
        val inputs: List<PsbtInput>,
        val outputs: List<PsbtOutput> = emptyList(),
        val globalUnknown: List<Pair<ByteArray, ByteArray>> = emptyList(),
        internal val globalEntries: List<Pair<ByteArray, ByteArray>> = emptyList()
    ) {
        /** The coin id the creator marked this PSBT with ([COIN_MARK_KEY]), or null when it has none. */
        val coinMark: String?
            get() = globalEntries.firstOrNull { it.first.contentEquals(COIN_MARK_KEY) }?.second?.decodeToString()
    }

    /**
     * Names the coin a PSBT spends, as a BIP174 proprietary global: type fc, identifier "sparrow-scrypt", subtype 0;
     * the value is the coin's id. Desktop drongo writes the same key (PSBT.COIN_MARK_KEY). Luckycoin and Junkcoin
     * keys sit on Bitcoin's path, so nothing else in a PSBT tells their spends from a Bitcoin one.
     */
    val COIN_MARK_KEY: ByteArray = byteArrayOf(0xFC.toByte(), 14) + "sparrow-scrypt".encodeToByteArray() + byteArrayOf(0)

    /**
     * Creates a PSBT from an unsigned build: the serialized unsigned transaction, plus
     * per input its witness utxo and BIP32 derivation ([masterFingerprint] and the full
     * path from the master key), and the change output's derivation when [changePath]
     * covers it.
     */
    fun create(
        unsigned: UnsignedBuild,
        masterFingerprint: ByteArray,
        pathFor: (SpendableInput) -> List<Long>,
        changeIndex: Int? = null,
        changePubKey: ByteArray? = null,
        changePath: List<Long>? = null
    ): ByteArray {
        require(masterFingerprint.size == 4) { "Master fingerprint must be 4 bytes" }
        val w = ByteWriter()
        w.bytes(MAGIC)

        // global map: the unsigned transaction (no witnesses, empty scriptSigs) and the coin it spends
        writeEntry(w, byteArrayOf(GLOBAL_UNSIGNED_TX.toByte()), unsigned.tx.serialize(false))
        writeEntry(w, COIN_MARK_KEY, unsigned.coin.id.encodeToByteArray())
        w.bytes(ByteArray(1)) // map separator

        for(input in unsigned.inputs) {
            val pub = input.key.pubKey
            val spentScript = Scripts.outputScript(pub, input.scriptType)
            input.prevTx?.let { raw ->
                val spent = runCatching { RawTx.parse(raw) }.getOrNull()?.takeIf { it.txid == input.txid }
                    ?.outputs?.getOrNull(input.vout.toInt())
                require(spent != null && spent.value == input.value && spent.script.contentEquals(spentScript)) {
                    "The previous transaction for ${input.txid}:${input.vout} does not match the coin"
                }
                // a Taproot signature commits to every spent amount, so the desktop never adds it there either
                if(input.scriptType != ScriptType.P2TR) writeEntry(w, byteArrayOf(IN_NON_WITNESS_UTXO.toByte()), raw)
            }
            val witnessUtxo = ByteWriter().u64le(input.value).varBytes(spentScript).toByteArray()
            when(input.scriptType) {
                ScriptType.P2PKH -> require(input.prevTx != null) { "A Legacy input needs its previous transaction" }
                ScriptType.P2SH_P2WPKH -> {
                    writeEntry(w, byteArrayOf(IN_WITNESS_UTXO.toByte()), witnessUtxo)
                    writeEntry(w, byteArrayOf(IN_REDEEM_SCRIPT.toByte()), Scripts.p2wpkhOutput(pub))
                }
                ScriptType.P2WPKH, ScriptType.P2TR -> writeEntry(w, byteArrayOf(IN_WITNESS_UTXO.toByte()), witnessUtxo)
            }
            if(input.scriptType == ScriptType.P2TR) {
                // as drongo writes it: the internal key's derivation with no leaf hashes, then the internal key
                val xOnly = Taproot.xOnly(pub)
                writeEntry(w, byteArrayOf(IN_TAP_BIP32_DERIVATION.toByte()) + xOnly,
                    byteArrayOf(0) + derivationValue(masterFingerprint, pathFor(input)))
                writeEntry(w, byteArrayOf(IN_TAP_INTERNAL_KEY.toByte()), xOnly)
            } else {
                writeEntry(w, byteArrayOf(IN_BIP32_DERIVATION.toByte()) + pub,
                    derivationValue(masterFingerprint, pathFor(input)))
            }
            w.bytes(ByteArray(1))
        }

        for((index, output) in unsigned.tx.outputs.withIndex()) {
            if(index == changeIndex && changePubKey != null && changePath != null) {
                if(Scripts.isP2tr(output.script)) {
                    // drongo's order: the derivation, then the internal key
                    val xOnly = Taproot.xOnly(changePubKey)
                    writeEntry(w, byteArrayOf(OUT_TAP_BIP32_DERIVATION.toByte()) + xOnly,
                        byteArrayOf(0) + derivationValue(masterFingerprint, changePath))
                    writeEntry(w, byteArrayOf(OUT_TAP_INTERNAL_KEY.toByte()), xOnly)
                } else {
                    writeEntry(w, byteArrayOf(OUT_BIP32_DERIVATION.toByte()) + changePubKey,
                        derivationValue(masterFingerprint, changePath))
                }
            }
            w.bytes(ByteArray(1))
        }
        return w.toByteArray()
    }

    private fun derivationValue(fingerprint: ByteArray, path: List<Long>): ByteArray {
        val w = ByteWriter().bytes(fingerprint)
        for(child in path) w.u32le(child)
        return w.toByteArray()
    }

    private fun writeEntry(w: ByteWriter, key: ByteArray, value: ByteArray) {
        w.varBytes(key)
        w.varBytes(value)
    }

    fun parse(bytes: ByteArray): Parsed {
        val r = ByteReader(bytes)
        require(r.bytes(5).contentEquals(MAGIC)) { "Not a PSBT (bad magic)" }

        val globalEntries = readEntries(r)
        val txBytes = globalEntries.firstOrNull { it.first[0].toInt() == GLOBAL_UNSIGNED_TX }
            ?.also { requireBareKey(it.first) }?.second
            ?: throw IllegalArgumentException("PSBT has no unsigned transaction")
        val tx = parseUnsignedTx(txBytes)

        val inputs = ArrayList<PsbtInput>()
        for(i in tx.inputs.indices) {
            var utxoValue: Long? = null
            var utxoScript: ByteArray? = null
            var nonWitnessUtxo: ByteArray? = null
            val sigs = HashMap<String, ByteArray>()
            val derivations = HashMap<String, Pair<ByteArray, List<Long>>>()
            var finalWitness: List<ByteArray>? = null
            var redeemScript: ByteArray? = null
            var finalScriptSig: ByteArray? = null
            var sighashType: Int? = null
            var tapKeySig: ByteArray? = null
            val tapDerivations = HashMap<String, Pair<ByteArray, List<Long>>>()
            var tapInternalKey: ByteArray? = null
            var hasLeafScripts = false
            val unknown = ArrayList<Pair<ByteArray, ByteArray>>()
            val entries = readEntries(r)
            for((key, value) in entries) {
                when(key[0].toInt()) {
                    IN_SIGHASH_TYPE -> {
                        requireBareKey(key)
                        require(value.size == 4) { "Input ${i + 1}: malformed sighash type" }
                        sighashType = ByteReader(value).u32le().toInt()
                    }
                    IN_TAP_KEY_SIG -> {
                        requireBareKey(key)
                        require(value.size == 64 || value.size == 65) { "Input ${i + 1}: malformed Taproot signature" }
                        tapKeySig = value
                    }
                    IN_TAP_LEAF_SCRIPT -> {
                        hasLeafScripts = true
                        unknown += key to value
                    }
                    IN_TAP_BIP32_DERIVATION -> {
                        require(key.size == 33) { "Malformed PSBT key" }
                        tapDerivations[key.copyOfRange(1, key.size).toHex()] = parseTapDerivation(value)
                    }
                    IN_TAP_INTERNAL_KEY -> {
                        requireBareKey(key)
                        require(value.size == 32) { "Input ${i + 1}: malformed Taproot internal key" }
                        tapInternalKey = value
                    }
                    IN_NON_WITNESS_UTXO -> nonWitnessUtxo = value.also { requireBareKey(key) }
                    IN_WITNESS_UTXO -> {
                        requireBareKey(key)
                        val vr = ByteReader(value)
                        utxoValue = vr.u64le()
                        utxoScript = vr.varBytes()
                    }
                    IN_PARTIAL_SIG -> sigs[key.copyOfRange(1, key.size).toHex()] = value
                    IN_BIP32_DERIVATION -> derivations[key.copyOfRange(1, key.size).toHex()] = parseDerivation(value)
                    IN_REDEEM_SCRIPT -> redeemScript = value.also { requireBareKey(key) }
                    IN_FINAL_SCRIPTSIG -> finalScriptSig = value.also { requireBareKey(key) }
                    IN_FINAL_SCRIPTWITNESS -> {
                        requireBareKey(key)
                        val vr = ByteReader(value)
                        val count = vr.varInt()
                        require(count <= vr.remaining()) { "Truncated final witness" }
                        finalWitness = List(count.toInt()) { vr.varBytes() }
                    }
                    else -> unknown += key to value
                }
            }
            nonWitnessUtxo?.let { checkNonWitnessUtxo(i, it, tx.inputs[i], utxoValue, utxoScript) }
            inputs.add(PsbtInput(utxoValue, utxoScript, sigs, derivations, finalWitness,
                nonWitnessUtxo, unknown, entries, redeemScript, finalScriptSig,
                sighashType, tapKeySig, tapDerivations, tapInternalKey, hasLeafScripts))
        }
        val outputs = tx.outputs.indices.map {
            // some creators stop after the inputs; a missing output map reads as empty
            val entries = if(r.remaining() > 0) readEntries(r) else emptyList()
            val derivations = HashMap<String, Pair<ByteArray, List<Long>>>()
            val tapDerivations = HashMap<String, Pair<ByteArray, List<Long>>>()
            val unknown = ArrayList<Pair<ByteArray, ByteArray>>()
            for((key, value) in entries) {
                when {
                    key[0].toInt() == OUT_BIP32_DERIVATION -> derivations[key.copyOfRange(1, key.size).toHex()] = parseDerivation(value)
                    key[0].toInt() == OUT_TAP_BIP32_DERIVATION && key.size == 33 ->
                        tapDerivations[key.copyOfRange(1, key.size).toHex()] = parseTapDerivation(value)
                    else -> unknown += key to value
                }
            }
            PsbtOutput(derivations, unknown, entries, tapDerivations)
        }
        require(r.remaining() == 0) { "Trailing data after PSBT" }
        val globalUnknown = globalEntries.filter { it.first[0].toInt() != GLOBAL_UNSIGNED_TX && !it.first.contentEquals(COIN_MARK_KEY) }
        return Parsed(txBytes, tx, inputs, outputs, globalUnknown, globalEntries)
    }

    private fun parseDerivation(value: ByteArray): Pair<ByteArray, List<Long>> {
        val vr = ByteReader(value)
        val fp = vr.bytes(4)
        val path = ArrayList<Long>()
        while(vr.remaining() >= 4) path.add(vr.u32le())
        return fp to path
    }

    /** A Taproot derivation: the leaf hashes the key signs for (skipped), then fingerprint and path. */
    private fun parseTapDerivation(value: ByteArray): Pair<ByteArray, List<Long>> {
        val vr = ByteReader(value)
        val leaves = vr.varInt()
        require(leaves in 0L..(vr.remaining() / 32).toLong()) { "Truncated Taproot derivation" }
        vr.bytes(32 * leaves.toInt())
        return parseDerivation(vr.bytes(vr.remaining()))
    }

    /**
     * The full previous transaction must be the one the input spends, and must agree with
     * the witness UTXO: a signer that trusted a different amount could be tricked into fees.
     */
    private fun checkNonWitnessUtxo(i: Int, raw: ByteArray, input: TxInput, witnessValue: Long?, witnessScript: ByteArray?) {
        val prev = runCatching { RawTx.parse(raw) }.getOrNull()
        val spent = prev?.takeIf { it.txid == input.prevTxid }?.outputs?.getOrNull(input.vout.toInt())
        val agrees = spent != null && (witnessValue == null ||
            (spent.value == witnessValue && witnessScript != null && spent.script.contentEquals(witnessScript)))
        require(agrees) { "Input ${i + 1}: non-witness UTXO does not match the input" }
    }

    /**
     * Finalizes a signed PSBT into the broadcastable transaction. An input another signer
     * finalized keeps its final scriptSig and witness; otherwise the partial signature whose key
     * owns the spent output (others' signatures are ignored) is placed by that output's type:
     * P2PKH a scriptSig of signature and key, P2SH-P2WPKH a scriptSig pushing the redeem script
     * and a witness, P2WPKH a witness. A Taproot key-path signature becomes the witness on its own.
     */
    fun finalize(parsed: Parsed): Tx {
        for((i, input) in parsed.inputs.withIndex()) {
            val txIn = parsed.tx.inputs[i]
            if(input.finalScriptSig != null || input.finalWitness != null) {
                txIn.scriptSig = input.finalScriptSig ?: ByteArray(0)
                txIn.witness = input.finalWitness ?: emptyList()
                continue
            }
            input.tapKeySig?.let { sig ->
                val spent = input.spentOutput(txIn.prevTxid, txIn.vout)?.script
                    ?: throw IllegalArgumentException("Input ${i + 1} has no witness utxo or previous transaction")
                require(Scripts.isP2tr(spent)) { "Input ${i + 1}: a Taproot signature on a coin that is not Taproot" }
                txIn.scriptSig = ByteArray(0)
                txIn.witness = listOf(sig)
                continue
            }
            if(input.partialSigs.isEmpty()) throw IllegalArgumentException("Input ${i + 1} has no signature. Sign the PSBT first")
            val spent = input.spentOutput(txIn.prevTxid, txIn.vout)?.script
                ?: throw IllegalArgumentException("Input ${i + 1} has no witness utxo or previous transaction")
            val (pub, sig) = input.partialSigs.entries.map { it.key.hexToBytes() to it.value }
                // a key that is no valid compressed point has no Taproot output, rather than failing the search
                .firstOrNull { (pub, _) ->
                    ScriptType.entries.any { runCatching { Scripts.outputScript(pub, it) }.getOrNull()?.contentEquals(spent) == true }
                }
                ?: throw IllegalArgumentException("Input ${i + 1}: the signature's key does not own the coin")
            when {
                spent.contentEquals(Scripts.p2pkhOutput(pub)) -> {
                    txIn.scriptSig = push(sig) + push(pub)
                    txIn.witness = emptyList()
                }
                spent.contentEquals(Scripts.outputScript(pub, ScriptType.P2SH_P2WPKH)) -> {
                    txIn.scriptSig = push(Scripts.p2wpkhOutput(pub))
                    txIn.witness = listOf(sig, pub)
                }
                spent.contentEquals(Scripts.p2wpkhOutput(pub)) -> txIn.witness = listOf(sig, pub)
                else -> throw IllegalArgumentException("Input ${i + 1}: the signature's key does not own the coin")
            }
        }
        return parsed.tx
    }

    /** Direct push; signatures, keys and redeem scripts are all under 76 bytes. */
    private fun push(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data

    /**
     * Test helper: signs every input this [keyFor] can serve as Native Segwit, without the
     * ownership checks [PsbtSigner] makes. The app signs through [PsbtSigner] only. Its tests are Litecoin's, which
     * has no chain id.
     */
    internal fun sign(parsed: Parsed, keyFor: (String) -> HDKey?): ByteArray {
        val sigsPerInput = parsed.inputs.mapIndexed { i, input ->
            val pubHex = input.derivations.keys.firstOrNull()
                ?: input.partialSigs.keys.firstOrNull()
                ?: return@mapIndexed emptyMap<String, ByteArray>()
            val key = keyFor(pubHex) ?: return@mapIndexed emptyMap<String, ByteArray>()
            val value = input.witnessUtxoValue
                ?: throw IllegalArgumentException("Input ${i + 1} has no witness utxo")
            val sighash = parsed.tx.bip143SighashAll(i, Scripts.p2pkhScriptCode(key.pubKey), value, ChainSig.NONE)
            mapOf(pubHex to Ecdsa.signForInput(sighash, key.privKey))
        }
        return serialize(parsed, sigsPerInput)
    }

    /**
     * Writes [parsed] back out with every pair exactly as read, adding [extraSigs] (per
     * input, pubkey hex to signature) as partial signatures and [tapKeySigs] (per input, or
     * null) as Taproot key-path signatures at the end of each input map.
     */
    fun serialize(
        parsed: Parsed,
        extraSigs: List<Map<String, ByteArray>> = emptyList(),
        tapKeySigs: List<ByteArray?> = emptyList()
    ): ByteArray {
        val w = ByteWriter()
        w.bytes(MAGIC)
        for((key, value) in parsed.globalEntries) writeEntry(w, key, value)
        w.bytes(ByteArray(1))
        for((i, input) in parsed.inputs.withIndex()) {
            val sigs = extraSigs.getOrNull(i).orEmpty()
            val tapSig = tapKeySigs.getOrNull(i)
            // a signature already present for a key we sign with is replaced, not trusted
            for((key, value) in input.entries) {
                val replaced = (key[0].toInt() == IN_PARTIAL_SIG && key.copyOfRange(1, key.size).toHex() in sigs) ||
                    (key[0].toInt() == IN_TAP_KEY_SIG && tapSig != null)
                if(!replaced) writeEntry(w, key, value)
            }
            for((pubHex, sig) in sigs) {
                writeEntry(w, byteArrayOf(IN_PARTIAL_SIG.toByte()) + pubHex.hexToBytes(), sig)
            }
            tapSig?.let { writeEntry(w, byteArrayOf(IN_TAP_KEY_SIG.toByte()), it) }
            w.bytes(ByteArray(1))
        }
        for(j in parsed.tx.outputs.indices) {
            for((key, value) in parsed.outputs.getOrNull(j)?.entries.orEmpty()) writeEntry(w, key, value)
            w.bytes(ByteArray(1))
        }
        return w.toByteArray()
    }

    private fun readEntries(r: ByteReader): List<Pair<ByteArray, ByteArray>> {
        val entries = ArrayList<Pair<ByteArray, ByteArray>>()
        val seen = HashSet<String>()
        readMap(r) { key, value ->
            require(seen.add(key.toHex())) { "Duplicate key in PSBT" } // BIP174: keys are unique per map
            entries += key to value
        }
        return entries
    }

    /** Key types whose key is the type byte alone (BIP174). */
    private fun requireBareKey(key: ByteArray) {
        require(key.size == 1) { "Malformed PSBT key" }
    }

    private fun readMap(r: ByteReader, onEntry: (ByteArray, ByteArray) -> Unit) {
        while(true) {
            val keyLen = r.varInt()
            if(keyLen == 0L) return
            val key = r.bytes(keyLen.toInt())
            val value = r.varBytes()
            onEntry(key, value)
        }
    }

    private fun parseUnsignedTx(bytes: ByteArray): Tx {
        val r = ByteReader(bytes)
        val version = r.u32le()
        val inputCount = r.varInt()
        require(inputCount in 1..10_000) { "Unreasonable input count in PSBT transaction" }
        val inputs = List(inputCount.toInt()) {
            val txid = r.bytes(32).reversedArray().toHex()
            val vout = r.u32le()
            val scriptSig = r.varBytes()
            require(scriptSig.isEmpty()) { "PSBT transaction must be unsigned" }
            val sequence = r.u32le()
            TxInput(txid, vout, sequence)
        }
        val outputCount = r.varInt()
        require(outputCount in 0..r.remaining() / 9L) { "Unreasonable output count in PSBT transaction" } // 9 bytes minimum each
        val outputs = List(outputCount.toInt()) {
            val value = r.u64le()
            TxOutput(value, r.varBytes())
        }
        val locktime = r.u32le()
        require(r.remaining() == 0) { "Trailing data in PSBT transaction" }
        return Tx(version, inputs, outputs, locktime)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun toBase64(bytes: ByteArray): String = Base64.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    fun fromBase64(text: String): ByteArray = try {
        Base64.decode(text.trim())
    } catch(e: IllegalArgumentException) {
        throw IllegalArgumentException("Not a valid PSBT (bad base64)", e)
    }
}

/** Minimal little-endian byte reader for PSBT decoding. */
internal class ByteReader(private val data: ByteArray) {
    private var pos = 0

    fun remaining(): Int = data.size - pos

    fun bytes(count: Int): ByteArray {
        require(count >= 0 && count <= data.size - pos) { "Truncated data" }
        return data.copyOfRange(pos, pos + count).also { pos += count }
    }

    fun u32le(): Long {
        val b = bytes(4)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
               ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    fun u64le(): Long {
        val b = bytes(8)
        var v = 0L
        for(i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun varInt(): Long {
        val first = bytes(1)[0].toInt() and 0xFF
        return when {
            first < 0xFD -> first.toLong()
            first == 0xFD -> (bytes(1)[0].toLong() and 0xFF) or ((bytes(1)[0].toLong() and 0xFF) shl 8)
            first == 0xFE -> u32le()
            else -> u64le()
        }
    }

    fun varBytes(): ByteArray = bytes(varInt().toInt())
}
