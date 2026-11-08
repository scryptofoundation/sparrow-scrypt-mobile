package com.sparrowwallet.mobile.crypto

import kotlin.math.ceil
import kotlin.math.round

/** [mayCarry]: the coin it spends may carry an inscription or tokens ([CarrierScan]). */
data class ReviewInput(val index: Int, val value: Long?, val ours: Boolean, val mayCarry: Boolean = false)

data class ReviewOutput(
    val index: Int,
    /** null when the script has no address form (OP_RETURN, MWEB peg-in, unknown witness version). */
    val address: String?,
    val scriptHex: String,
    val value: Long,
    val change: Boolean
)

data class PsbtReview(
    val inputs: List<ReviewInput>,
    val outputs: List<ReviewOutput>,
    /** null when any input's amount is unknown (no witness UTXO or previous transaction). */
    val totalIn: Long?,
    val totalOut: Long,
    val fee: Long?,
    /** In the coin's smallest unit per vbyte (lits for Litecoin, sats for the others). */
    val feeRatePerVb: Double?,
    /** Why [PsbtSigner.sign] will refuse this PSBT, or null when it can be signed. */
    val refusal: String? = null,
    /** Something to know before signing, or null. */
    val warning: String? = null
)

/**
 * Signs PSBTs built elsewhere (desktop Sparrow with a watch-only copy of this wallet), for every
 * single-key script type. An input is ours only through a BIP32 derivation carrying this wallet's
 * master fingerprint, and is signed only after the key derived from that path is shown to own the
 * output being spent, in the wallet's script type. A Legacy input is signed only with its previous
 * transaction, the only proof of the output its signature covers. A Taproot input is signed on its
 * key path only, and only when the PSBT gives every input's spent output. Everything else passes through.
 */
object PsbtSigner {
    /** Segwit witness: count + 72-byte signature + 33-byte pubkey, with length bytes. */
    private const val WITNESS_BYTES = 108

    /** Legacy scriptSig: a 72-byte signature and 33-byte key, each with its push, plus its length. */
    private const val P2PKH_SCRIPTSIG_BYTES = 107

    /** Nested segwit scriptSig: the 22-byte redeem script push, plus its length. */
    private const val NESTED_SCRIPTSIG_BYTES = 23

    /** Taproot key-path witness: count + 64-byte signature with its length byte (as TxBuilder.estimateVsize). */
    private const val TAPROOT_WITNESS_BYTES = 66

    private const val SCRIPT_PATH_UNSUPPORTED = "Script-path Taproot spends are not supported on the phone yet"

    /** Litecoin Native Segwit, the signer's original scope. */
    fun review(parsed: Psbt.Parsed, master: HDKey, network: Network, accountPath: List<Long>): PsbtReview =
        review(parsed, master, network.litecoin, accountPath, ScriptType.P2WPKH)

    /** Litecoin Native Segwit, the signer's original scope. */
    fun sign(parsed: Psbt.Parsed, master: HDKey, network: Network, accountPath: List<Long>): ByteArray =
        sign(parsed, master, network.litecoin, accountPath, ScriptType.P2WPKH)

    /** Highest address index this wallet signs for or calls change; past it no wallet would scan. */
    private const val MAX_INDEX = 10_000L

    /**
     * [accountPath] is this wallet's account (for example m/84'/2'/0'). Keys count as ours
     * only at accountPath/0/i or accountPath/1/i with i below [MAX_INDEX]: an output there
     * is change, an input there is signed. Anything else is foreign even with our master
     * fingerprint, so a PSBT cannot hide a payment to a key the wallet never scans, nor get
     * a signature for another coin's path (Bitcoin shares BIP143) under a Litecoin review.
     * Coins on coin type 0 (Luckycoin, Junkcoin) share Bitcoin's own path, which only the
     * creator's coin mark ([Psbt.COIN_MARK_KEY]) tells apart: such a wallet signs only a PSBT
     * marked with its own coin, and any wallet refuses one marked for another coin.
     */
    fun review(parsed: Psbt.Parsed, master: HDKey, params: ChainParams, accountPath: List<Long>, scriptType: ScriptType): PsbtReview {
        val inputs = parsed.inputs.mapIndexed { i, input ->
            val txIn = parsed.tx.inputs[i]
            val value = input.spentOutput(txIn.prevTxid, txIn.vout)?.value
            val prevTx = input.nonWitnessUtxo?.let { raw -> runCatching { RawTx.parse(raw) }.getOrNull()?.takeIf { it.txid == txIn.prevTxid } }
            val mayCarry = value != null && CarrierScan.mayCarry(params.coin, prevTx, txIn.vout.toInt(), value)
            ReviewInput(i, value, ourDerivation(input, master, accountPath, scriptType) != null, mayCarry)
        }
        val outputs = parsed.tx.outputs.mapIndexed { j, out ->
            val psbtOutput = parsed.outputs.getOrNull(j)
            //a Taproot change output carries a Taproot derivation (0x07) instead of a BIP32 one
            val derivations = if(scriptType == ScriptType.P2TR) psbtOutput?.tapDerivations else psbtOutput?.derivations
            val change = derivations?.values?.any { (fp, path) ->
                fp.contentEquals(master.fingerprint) && inAccount(path, accountPath) &&
                    Scripts.outputScript(derive(master, path).pubKey, scriptType).contentEquals(out.script)
            } == true
            ReviewOutput(j, Addresses.fromScript(out.script, params), out.script.toHex(), out.value, change)
        }
        val totalIn = if(inputs.all { it.value != null }) inputs.sumOf { it.value!! } else null
        val totalOut = outputs.sumOf { it.value }
        val fee = totalIn?.let { it - totalOut }
        //each input sized by the output it spends; the wallet's type only when that is unknown
        val types = parsed.inputs.mapIndexed { i, input ->
            val txIn = parsed.tx.inputs[i]
            input.spentOutput(txIn.prevTxid, txIn.vout)?.script?.let { spentType(it) } ?: scriptType
        }
        val scriptSigBytes = types.sumOf {
            when(it) {
                ScriptType.P2PKH -> P2PKH_SCRIPTSIG_BYTES
                ScriptType.P2SH_P2WPKH -> NESTED_SCRIPTSIG_BYTES
                ScriptType.P2WPKH, ScriptType.P2TR -> 0
            }
        }
        val segwit = types.count { it != ScriptType.P2PKH }
        //marker and flag, a witness per segwit input, and an empty one per Legacy input beside them
        val witnessBytes = if(segwit == 0) 0 else 2 + types.sumOf {
            when(it) {
                ScriptType.P2PKH -> 1
                ScriptType.P2TR -> TAPROOT_WITNESS_BYTES
                ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH -> WITNESS_BYTES
            }
        }
        val vsize = parsed.unsignedTxBytes.size + scriptSigBytes + ceil(witnessBytes / 4.0)
        val rate = fee?.let { round(it / vsize * 100) / 100 }
        val carrying = inputs.count { it.mayCarry }
        val carryWarning = if(carrying > 0) {
            (if(carrying == 1) "1 input" else "$carrying inputs") + " may carry an inscription or tokens (made by an " +
                "inscription transaction, or holding an inscription's usual value). Signing spends them, and anything " +
                "they carry could be lost in a payment or the fee."
        } else null
        //another signer's script-path input is left for them; the phone signs only key-path spends
        val scriptPath = parsed.inputs.withIndex().filter { (i, input) -> input.hasLeafScripts && !inputs[i].ours }.map { it.index + 1 }
        val scriptPathWarning = if(scriptPath.isNotEmpty()) {
            "$SCRIPT_PATH_UNSUPPORTED: " + (if(scriptPath.size == 1) "input ${scriptPath[0]} is" else "inputs ${scriptPath.joinToString(", ")} are") +
                " left unsigned for another signer."
        } else null
        val warning = listOfNotNull(carryWarning, scriptPathWarning).joinToString(" ").ifEmpty { null }
        return PsbtReview(inputs, outputs, totalIn, totalOut, fee, rate, refusal(parsed, master, params.coin, accountPath, scriptType), warning)
    }

    fun sign(parsed: Psbt.Parsed, master: HDKey, params: ChainParams, accountPath: List<Long>, scriptType: ScriptType): ByteArray {
        var ours = 0
        //a Taproot signature commits to every input's spent amount and script, ours or not
        val prevouts by lazy {
            missingSpentOutput(parsed)?.let { throw IllegalArgumentException(it) }
            parsed.inputs.mapIndexed { i, input -> input.spentOutput(parsed.tx.inputs[i].prevTxid, parsed.tx.inputs[i].vout)!! }
        }
        val tapKeySigs = MutableList<ByteArray?>(parsed.inputs.size) { null }
        val sigs = parsed.inputs.mapIndexed { i, input ->
            val (pubHex, path) = ourDerivation(input, master, accountPath, scriptType) ?: return@mapIndexed emptyMap()
            ours++
            inputProblem(parsed, i, master, accountPath, scriptType)?.let { throw IllegalArgumentException(it) }
            val txIn = parsed.tx.inputs[i]
            val key = derive(master, path)
            val spent = input.spentOutput(txIn.prevTxid, txIn.vout)!!
            if(scriptType == ScriptType.P2TR) {
                val hashType = input.sighashType ?: 0
                val sighash = parsed.tx.taprootKeyPathSighash(i, prevouts.map { it.value }, prevouts.map { it.script }, hashType)
                val sig = Schnorr.signForInput(sighash, key.privKey, spent.script.copyOfRange(2, 34))
                tapKeySigs[i] = if(hashType == 1) sig + byteArrayOf(0x01) else sig
                return@mapIndexed emptyMap()
            }
            val sighash = when(scriptType) {
                ScriptType.P2PKH -> parsed.tx.legacySighashAll(i, spent.script)
                else -> parsed.tx.bip143SighashAll(i, Scripts.p2pkhScriptCode(key.pubKey), spent.value)
            }
            mapOf(pubHex to Ecdsa.signForInput(sighash, key.privKey))
        }
        require(ours > 0) { "This PSBT has no inputs belonging to this wallet" }
        refusal(parsed, master, params.coin, accountPath, scriptType)?.let { throw IllegalArgumentException(it) }
        return Psbt.serialize(parsed, sigs, tapKeySigs)
    }

    /**
     * Why [sign] would refuse an input that names this wallet's account, or null. A Legacy signature
     * covers the spent output's script, which only the previous transaction proves (parsing has
     * checked it against any witness UTXO, so either source gives the same output); and the key
     * derived from the path must own that output in the wallet's script type. A Taproot input is
     * signed only with SIGHASH_DEFAULT or SIGHASH_ALL.
     */
    private fun inputProblem(parsed: Psbt.Parsed, i: Int, master: HDKey, accountPath: List<Long>, scriptType: ScriptType): String? {
        val input = parsed.inputs[i]
        val (pubHex, path) = ourDerivation(input, master, accountPath, scriptType) ?: return null
        if(scriptType == ScriptType.P2PKH && input.nonWitnessUtxo == null) {
            return "Input ${i + 1} is a Legacy coin without its previous transaction"
        }
        val txIn = parsed.tx.inputs[i]
        val spent = input.spentOutput(txIn.prevTxid, txIn.vout) ?: return "Input ${i + 1} has no witness UTXO or previous transaction"
        val key = derive(master, path)
        val keyHex = if(scriptType == ScriptType.P2TR) Taproot.xOnly(key.pubKey).toHex() else key.pubKey.toHex()
        if(keyHex != pubHex.lowercase() || !Scripts.outputScript(key.pubKey, scriptType).contentEquals(spent.script)) {
            //a script tree tweaks the output key, so a leaf-script input never matches the BIP86 output
            if(scriptType == ScriptType.P2TR && input.hasLeafScripts) return "Input ${i + 1}: $SCRIPT_PATH_UNSUPPORTED"
            return "Input ${i + 1}: ${if(scriptType == ScriptType.P2PKH) "previous output" else "witness UTXO"} does not match this wallet's key"
        }
        val hashType = input.sighashType
        if(scriptType == ScriptType.P2TR && hashType != null && hashType != 0 && hashType != 1) {
            return "Input ${i + 1} asks for signature hash type 0x${hashType.toLong().and(0xFFFFFFFFL).toString(16)}, " +
                "which the phone does not sign. Only SIGHASH_DEFAULT and SIGHASH_ALL are supported."
        }
        return null
    }

    /** The single-key type an output script is spent as; P2SH is taken as nested segwit, the only kind made here. */
    private fun spentType(script: ByteArray): ScriptType? = when {
        script.size == 25 && script[0] == 0x76.toByte() && script[1] == 0xa9.toByte() && script[2] == 0x14.toByte() -> ScriptType.P2PKH
        script.size == 23 && script[0] == 0xa9.toByte() && script[1] == 0x14.toByte() && script[22] == 0x87.toByte() -> ScriptType.P2SH_P2WPKH
        script.size == 22 && script[0] == 0x00.toByte() && script[1] == 0x14.toByte() -> ScriptType.P2WPKH
        Scripts.isP2tr(script) -> ScriptType.P2TR
        else -> null
    }

    /** The first input whose spent output the PSBT does not give, which a Taproot signature needs for every input. */
    private fun missingSpentOutput(parsed: Psbt.Parsed): String? =
        parsed.inputs.indices.firstOrNull { i ->
            parsed.inputs[i].spentOutput(parsed.tx.inputs[i].prevTxid, parsed.tx.inputs[i].vout) == null
        }?.let { "Input ${it + 1} has no witness UTXO or previous transaction" }

    private fun refusal(parsed: Psbt.Parsed, master: HDKey, coin: Coin, accountPath: List<Long>, scriptType: ScriptType): String? {
        coinRefusal(parsed.coinMark, coin, accountPath)?.let { return it }
        if(scriptType == ScriptType.P2PKH) {
            val missing = parsed.inputs.withIndex().filter { (_, input) ->
                input.nonWitnessUtxo == null && ourDerivation(input, master, accountPath, scriptType) != null
            }.map { it.index + 1 }
            if(missing.isNotEmpty()) {
                val which = if(missing.size == 1) "Input ${missing[0]} is" else "Inputs ${missing.joinToString(", ")} are"
                return "$which missing the full previous transaction, which a Legacy coin needs to be signed. " +
                    "On the PC, import this wallet with the Sparrow Mobile option."
            }
        }
        parsed.inputs.indices.firstNotNullOfOrNull { inputProblem(parsed, it, master, accountPath, scriptType) }?.let { return it }
        if(scriptType == ScriptType.P2TR) missingSpentOutput(parsed)?.let { return it }
        return prevTxRefusal(parsed, scriptType)
    }

    /**
     * A PSBT marked for another coin is never signed. A wallet whose keys sit on Bitcoin's path (coin type 0) also
     * refuses an unmarked one: its inputs would sign the same way for Bitcoin, so only the mark says which chain it spends.
     */
    private fun coinRefusal(mark: String?, coin: Coin, accountPath: List<Long>): String? {
        if(mark != null && mark != coin.id) {
            val named = Coin.entries.firstOrNull { it.id == mark }?.displayName ?: mark
            return "This PSBT is for $named, not ${coin.displayName}."
        }
        if(mark == null && accountPath.getOrNull(1)?.let { it and 0x7FFFFFFFL } == 0L) {
            return "This PSBT does not say which coin it is for. This wallet's keys are on Bitcoin's path (coin type 0), " +
                "so a Bitcoin transaction would sign the same way. Make the PSBT in Sparrow-Scrypt on the PC, which marks its coin."
        }
        return null
    }

    /**
     * A BIP143 signature commits only to its own input's amount. Two sessions, each signing one
     * input while lying about the other's amount, yield a transaction whose real fee was never
     * shown. Which inputs are ours is the PSBT's own claim (a desktop can drop a derivation), so
     * whenever there is more than one input every one must carry its full previous transaction,
     * which parsing checks against the witness UTXO. A single input's amount is in its signature.
     * A Taproot wallet needs none: a BIP341 signature commits to every input's amount and script,
     * so a PSBT that lies about any of them gets a signature the network rejects.
     */
    private fun prevTxRefusal(parsed: Psbt.Parsed, scriptType: ScriptType): String? {
        //keyed on the wallet, not on each input: only our own BIP341 signature commits to the other inputs' amounts,
        //so a segwit v0 wallet still needs every previous transaction, even beside another signer's Taproot input
        if(scriptType == ScriptType.P2TR) return null
        val missing = parsed.inputs.withIndex().filter { it.value.nonWitnessUtxo == null }.map { it.index + 1 }
        if(parsed.inputs.size < 2 || missing.isEmpty()) return null
        val which = if(missing.size == 1) "Input ${missing[0]} is" else "Inputs ${missing.joinToString(", ")} are"
        return "$which missing the full previous transaction. When a PSBT has more than one input, the " +
            "phone needs each one to check the fee. On the PC, import this wallet with the Sparrow Mobile option."
    }

    /**
     * The derivation naming this wallet's account (pubkey hex to path), not just the first one listed. A Taproot
     * wallet reads the Taproot derivations, keyed by x-only key, and takes one only when the key derived from its
     * path has that x coordinate.
     */
    private fun ourDerivation(
        input: Psbt.PsbtInput,
        master: HDKey,
        accountPath: List<Long>,
        scriptType: ScriptType
    ): Pair<String, List<Long>>? {
        if(scriptType == ScriptType.P2TR) {
            return input.tapDerivations.entries.firstOrNull { (xOnly, d) ->
                d.first.contentEquals(master.fingerprint) && inAccount(d.second, accountPath) &&
                    Taproot.xOnly(derive(master, d.second).pubKey).toHex() == xOnly.lowercase()
            }?.let { it.key to it.value.second }
        }
        return input.derivations.entries.firstOrNull { (_, d) -> d.first.contentEquals(master.fingerprint) && inAccount(d.second, accountPath) }
            ?.let { it.key to it.value.second }
    }

    private fun inAccount(path: List<Long>, accountPath: List<Long>): Boolean =
        path.size == accountPath.size + 2 && path.take(accountPath.size) == accountPath &&
            path[accountPath.size] in 0L..1L && path.last() in 0L until MAX_INDEX

    private fun derive(master: HDKey, path: List<Long>): HDKey {
        var key = master
        for(child in path) key = key.deriveChild(child)
        return key
    }
}
