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
    val feeRateLitPerVb: Double?
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

    /** Highest address index treated as change; anything past it no wallet would scan. */
    private const val MAX_CHANGE_INDEX = 10_000L

    /**
     * [accountPath] is this wallet's account (for example m/84'/2'/0'): an output counts as
     * change only at accountPath/0/i or accountPath/1/i with i below [MAX_CHANGE_INDEX], so a
     * PSBT cannot hide a payment to one of our keys at a path the wallet never looks at.
     */
    fun review(parsed: Psbt.Parsed, master: HDKey, network: Network, accountPath: List<Long>): PsbtReview {
        val inputs = parsed.inputs.mapIndexed { i, input ->
            ReviewInput(i, input.witnessUtxoValue, ourDerivation(input.derivations, master) != null)
        }
        val outputs = parsed.tx.outputs.mapIndexed { j, out ->
            val change = parsed.outputs.getOrNull(j)?.derivations?.values?.any { (fp, path) ->
                fp.contentEquals(master.fingerprint) &&
                    path.size == accountPath.size + 2 && path.take(accountPath.size) == accountPath &&
                    path[accountPath.size] in 0L..1L && path.last() in 0L until MAX_CHANGE_INDEX &&
                    Scripts.p2wpkhOutput(derive(master, path).pubKey).contentEquals(out.script)
            } == true
            ReviewOutput(j, Addresses.fromScript(out.script, network), out.script.toHex(), out.value, change)
        }
        val totalIn = if(inputs.all { it.value != null }) inputs.sumOf { it.value!! } else null
        val totalOut = outputs.sumOf { it.value }
        val fee = totalIn?.let { it - totalOut }
        val vsize = parsed.unsignedTxBytes.size + ceil((2 + P2WPKH_WITNESS_BYTES * inputs.size) / 4.0)
        val rate = fee?.let { round(it / vsize * 100) / 100 }
        return PsbtReview(inputs, outputs, totalIn, totalOut, fee, rate)
    }

    fun sign(parsed: Psbt.Parsed, master: HDKey, network: Network): ByteArray {
        var ours = 0
        val sigs = parsed.inputs.mapIndexed { i, input ->
            val (pubHex, path) = ourDerivation(input.derivations, master) ?: return@mapIndexed emptyMap()
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
        return Psbt.serialize(parsed, sigs)
    }

    /** The derivation naming this wallet (pubkey hex to path), not just the first one listed. */
    private fun ourDerivation(derivations: Map<String, Pair<ByteArray, List<Long>>>, master: HDKey): Pair<String, List<Long>>? =
        derivations.entries.firstOrNull { it.value.first.contentEquals(master.fingerprint) }?.let { it.key to it.value.second }

    private fun derive(master: HDKey, path: List<Long>): HDKey {
        var key = master
        for(child in path) key = key.deriveChild(child)
        return key
    }
}
