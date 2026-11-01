package com.sparrowwallet.mobile.crypto

import kotlin.math.ceil
import kotlin.math.round

data class ReviewInput(val index: Int, val value: Long?, val ours: Boolean)

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
 * transaction, the only proof of the output its signature covers. Everything else passes through.
 */
object PsbtSigner {
    /** Segwit witness: count + 72-byte signature + 33-byte pubkey, with length bytes. */
    private const val WITNESS_BYTES = 108

    /** Legacy scriptSig: a 72-byte signature and 33-byte key, each with its push, plus its length. */
    private const val P2PKH_SCRIPTSIG_BYTES = 107

    /** Nested segwit scriptSig: the 22-byte redeem script push, plus its length. */
    private const val NESTED_SCRIPTSIG_BYTES = 23

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
     * Coins on coin type 0 (Luckycoin, Junkcoin) share Bitcoin's own path, which no check can
     * tell apart: the review warns instead.
     */
    fun review(parsed: Psbt.Parsed, master: HDKey, params: ChainParams, accountPath: List<Long>, scriptType: ScriptType): PsbtReview {
        val inputs = parsed.inputs.mapIndexed { i, input ->
            val txIn = parsed.tx.inputs[i]
            ReviewInput(i, input.spentOutput(txIn.prevTxid, txIn.vout)?.value, ourDerivation(input.derivations, master, accountPath) != null)
        }
        val outputs = parsed.tx.outputs.mapIndexed { j, out ->
            val change = parsed.outputs.getOrNull(j)?.derivations?.values?.any { (fp, path) ->
                fp.contentEquals(master.fingerprint) && inAccount(path, accountPath) &&
                    Scripts.outputScript(derive(master, path).pubKey, scriptType).contentEquals(out.script)
            } == true
            ReviewOutput(j, Addresses.fromScript(out.script, params), out.script.toHex(), out.value, change)
        }
        val totalIn = if(inputs.all { it.value != null }) inputs.sumOf { it.value!! } else null
        val totalOut = outputs.sumOf { it.value }
        val fee = totalIn?.let { it - totalOut }
        val n = inputs.size
        val (scriptSigBytes, witnessBytes) = when(scriptType) {
            ScriptType.P2PKH -> P2PKH_SCRIPTSIG_BYTES * n to 0
            ScriptType.P2SH_P2WPKH -> NESTED_SCRIPTSIG_BYTES * n to 2 + WITNESS_BYTES * n
            ScriptType.P2WPKH -> 0 to 2 + WITNESS_BYTES * n
        }
        val vsize = parsed.unsignedTxBytes.size + scriptSigBytes + ceil(witnessBytes / 4.0)
        val rate = fee?.let { round(it / vsize * 100) / 100 }
        val warning = if(accountPath.getOrNull(1)?.let { it and 0x7FFFFFFFL } == 0L) {
            "This wallet's keys are on Bitcoin's path (coin type 0), so a Bitcoin transaction would " +
                "sign the same way. Sign only PSBTs you made yourself."
        } else null
        return PsbtReview(inputs, outputs, totalIn, totalOut, fee, rate, refusal(parsed, master, accountPath, scriptType), warning)
    }

    fun sign(parsed: Psbt.Parsed, master: HDKey, params: ChainParams, accountPath: List<Long>, scriptType: ScriptType): ByteArray {
        var ours = 0
        val sigs = parsed.inputs.mapIndexed { i, input ->
            val (pubHex, path) = ourDerivation(input.derivations, master, accountPath) ?: return@mapIndexed emptyMap()
            ours++
            val txIn = parsed.tx.inputs[i]
            val key = derive(master, path)
            //a Legacy signature covers the spent output's script, which only the previous transaction proves
            //(parsing has checked it against any witness UTXO, so either source gives the same output)
            if(scriptType == ScriptType.P2PKH) {
                require(input.nonWitnessUtxo != null) { "Input ${i + 1} is a Legacy coin without its previous transaction" }
            }
            val spent = input.spentOutput(txIn.prevTxid, txIn.vout)
            require(spent != null) { "Input ${i + 1} has no witness UTXO or previous transaction" }
            require(key.pubKey.toHex() == pubHex.lowercase() && Scripts.outputScript(key.pubKey, scriptType).contentEquals(spent.script)) {
                "Input ${i + 1}: ${if(scriptType == ScriptType.P2PKH) "previous output" else "witness UTXO"} does not match this wallet's key"
            }
            val sighash = when(scriptType) {
                ScriptType.P2PKH -> parsed.tx.legacySighashAll(i, spent.script)
                ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH -> parsed.tx.bip143SighashAll(i, Scripts.p2pkhScriptCode(key.pubKey), spent.value)
            }
            mapOf(pubHex to Ecdsa.signForInput(sighash, key.privKey))
        }
        require(ours > 0) { "This PSBT has no inputs belonging to this wallet" }
        refusal(parsed, master, accountPath, scriptType)?.let { throw IllegalArgumentException(it) }
        return Psbt.serialize(parsed, sigs)
    }

    private fun refusal(parsed: Psbt.Parsed, master: HDKey, accountPath: List<Long>, scriptType: ScriptType): String? {
        if(scriptType == ScriptType.P2PKH) {
            val missing = parsed.inputs.withIndex().filter { (_, input) ->
                input.nonWitnessUtxo == null && ourDerivation(input.derivations, master, accountPath) != null
            }.map { it.index + 1 }
            if(missing.isNotEmpty()) {
                val which = if(missing.size == 1) "Input ${missing[0]} is" else "Inputs ${missing.joinToString(", ")} are"
                return "$which missing the full previous transaction, which a Legacy coin needs to be signed. " +
                    "On the PC, import this wallet with the Sparrow Mobile option."
            }
        }
        return prevTxRefusal(parsed)
    }

    /**
     * A BIP143 signature commits only to its own input's amount. Two sessions, each signing one
     * input while lying about the other's amount, yield a transaction whose real fee was never
     * shown. Which inputs are ours is the PSBT's own claim (a desktop can drop a derivation), so
     * whenever there is more than one input every one must carry its full previous transaction,
     * which parsing checks against the witness UTXO. A single input's amount is in its signature.
     */
    private fun prevTxRefusal(parsed: Psbt.Parsed): String? {
        val missing = parsed.inputs.withIndex().filter { it.value.nonWitnessUtxo == null }.map { it.index + 1 }
        if(parsed.inputs.size < 2 || missing.isEmpty()) return null
        val which = if(missing.size == 1) "Input ${missing[0]} is" else "Inputs ${missing.joinToString(", ")} are"
        return "$which missing the full previous transaction. When a PSBT has more than one input, the " +
            "phone needs each one to check the fee. On the PC, import this wallet with the Sparrow Mobile option."
    }

    /** The derivation naming this wallet's account (pubkey hex to path), not just the first one listed. */
    private fun ourDerivation(
        derivations: Map<String, Pair<ByteArray, List<Long>>>,
        master: HDKey,
        accountPath: List<Long>
    ): Pair<String, List<Long>>? =
        derivations.entries.firstOrNull { (_, d) -> d.first.contentEquals(master.fingerprint) && inAccount(d.second, accountPath) }
            ?.let { it.key to it.value.second }

    private fun inAccount(path: List<Long>, accountPath: List<Long>): Boolean =
        path.size == accountPath.size + 2 && path.take(accountPath.size) == accountPath &&
            path[accountPath.size] in 0L..1L && path.last() in 0L until MAX_INDEX

    private fun derive(master: HDKey, path: List<Long>): HDKey {
        var key = master
        for(child in path) key = key.deriveChild(child)
        return key
    }
}
