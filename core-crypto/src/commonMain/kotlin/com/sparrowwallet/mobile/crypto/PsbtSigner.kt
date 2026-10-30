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
    /** null when any input lacks a witness UTXO amount. */
    val totalIn: Long?,
    val totalOut: Long,
    val fee: Long?,
    val feeRateLitPerVb: Double?,
    /** Why [PsbtSigner.sign] will refuse this PSBT, or null when it can be signed. */
    val refusal: String? = null
)

/**
 * Signs PSBTs built elsewhere (desktop Sparrow with a watch-only copy of this wallet).
 * An input is ours only through a BIP32 derivation carrying this wallet's master
 * fingerprint, and is signed only after the key derived from that path is shown to
 * own the witness UTXO being spent. Everything else in the PSBT passes through.
 */
object PsbtSigner {
    /** P2WPKH witness: count + 72-byte signature + 33-byte pubkey, with length bytes. */
    private const val P2WPKH_WITNESS_BYTES = 108

    /** Highest address index this wallet signs for or calls change; past it no wallet would scan. */
    private const val MAX_INDEX = 10_000L

    /**
     * [accountPath] is this wallet's account (for example m/84'/2'/0'). Keys count as ours
     * only at accountPath/0/i or accountPath/1/i with i below [MAX_INDEX]: an output there
     * is change, an input there is signed. Anything else is foreign even with our master
     * fingerprint, so a PSBT cannot hide a payment to a key the wallet never scans, nor get
     * a signature for another coin's path (Bitcoin shares BIP143) under a Litecoin review.
     */
    fun review(parsed: Psbt.Parsed, master: HDKey, network: Network, accountPath: List<Long>): PsbtReview {
        val inputs = parsed.inputs.mapIndexed { i, input ->
            ReviewInput(i, input.witnessUtxoValue, ourDerivation(input.derivations, master, accountPath) != null)
        }
        val outputs = parsed.tx.outputs.mapIndexed { j, out ->
            val change = parsed.outputs.getOrNull(j)?.derivations?.values?.any { (fp, path) ->
                fp.contentEquals(master.fingerprint) && inAccount(path, accountPath) &&
                    Scripts.p2wpkhOutput(derive(master, path).pubKey).contentEquals(out.script)
            } == true
            ReviewOutput(j, Addresses.fromScript(out.script, network), out.script.toHex(), out.value, change)
        }
        val totalIn = if(inputs.all { it.value != null }) inputs.sumOf { it.value!! } else null
        val totalOut = outputs.sumOf { it.value }
        val fee = totalIn?.let { it - totalOut }
        val vsize = parsed.unsignedTxBytes.size + ceil((2 + P2WPKH_WITNESS_BYTES * inputs.size) / 4.0)
        val rate = fee?.let { round(it / vsize * 100) / 100 }
        return PsbtReview(inputs, outputs, totalIn, totalOut, fee, rate, prevTxRefusal(parsed, master, accountPath))
    }

    fun sign(parsed: Psbt.Parsed, master: HDKey, network: Network, accountPath: List<Long>): ByteArray {
        var ours = 0
        val sigs = parsed.inputs.mapIndexed { i, input ->
            val (pubHex, path) = ourDerivation(input.derivations, master, accountPath) ?: return@mapIndexed emptyMap()
            ours++
            val script = input.witnessUtxoScript
            val value = input.witnessUtxoValue
            require(script != null && value != null) { "Input ${i + 1} is not native segwit (P2WPKH)" }
            val key = derive(master, path)
            require(key.pubKey.toHex() == pubHex.lowercase() && Scripts.p2wpkhOutput(key.pubKey).contentEquals(script)) {
                "Input ${i + 1}: witness UTXO does not match this wallet's key"
            }
            val sighash = parsed.tx.bip143SighashAll(i, Scripts.p2pkhScriptCode(key.pubKey), value)
            mapOf(pubHex to Ecdsa.signForInput(sighash, key.privKey))
        }
        require(ours > 0) { "This PSBT has no inputs belonging to this wallet" }
        prevTxRefusal(parsed, master, accountPath)?.let { throw IllegalArgumentException(it) }
        return Psbt.serialize(parsed, sigs)
    }

    /**
     * A BIP143 signature commits only to its own input's amount. Signing two of our inputs in
     * two sessions, each lying about the other input's amount, yields a transaction whose real
     * fee was never shown. Parsing checks each full previous transaction against its witness
     * UTXO, so requiring them whenever more than one input is ours pins every amount.
     */
    private fun prevTxRefusal(parsed: Psbt.Parsed, master: HDKey, accountPath: List<Long>): String? {
        val ours = parsed.inputs.withIndex().filter { ourDerivation(it.value.derivations, master, accountPath) != null }
        val missing = ours.filter { it.value.nonWitnessUtxo == null }.map { it.index + 1 }
        if(ours.size < 2 || missing.isEmpty()) return null
        val which = if(missing.size == 1) "Input ${missing[0]} is" else "Inputs ${missing.joinToString(", ")} are"
        return "$which missing the full previous transaction. When a PSBT spends more than one of this " +
            "wallet's coins, the phone needs each one to check the fee. On the PC, import this wallet " +
            "with the Sparrow Mobile option."
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
