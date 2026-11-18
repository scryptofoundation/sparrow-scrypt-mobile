package com.sparrowwallet.mobile.crypto

/**
 * Signs single-key inputs of one script type, matching drongo byte for byte (SignedTxCoinParityTest):
 * P2PKH with the original sighash and a scriptSig, P2WPKH with BIP143 and a witness, P2SH-P2WPKH
 * with BIP143, a witness and a scriptSig pushing the redeem script. SIGHASH_ALL, no FORKID. The legacy and the
 * BIP143 digests start with [ChainSig]'s prefix, which the caller chooses for the chain the transaction is meant for
 * (Luckycoin's chain id, or nothing).
 */
object InputSigner {
    /**
     * Signs every input. All digests are taken before any script is set: a legacy digest covers
     * the other inputs' scriptSigs, which must still be empty.
     */
    fun signAll(tx: Tx, keys: List<HDKey>, scriptType: ScriptType, values: List<Long>, chainSig: ChainSig) {
        require(keys.size == tx.inputs.size && values.size == tx.inputs.size) { "One key and one value per input" }
        val digests = keys.mapIndexed { i, key -> digest(tx, i, key, scriptType, values[i], chainSig) }
        for((i, key) in keys.withIndex()) {
            apply(tx.inputs[i], key, scriptType, Ecdsa.signForInput(digests[i], key.privKey))
        }
    }

    private fun digest(tx: Tx, i: Int, key: HDKey, scriptType: ScriptType, value: Long, chainSig: ChainSig): ByteArray = when(scriptType) {
        ScriptType.P2PKH -> tx.legacySighashAll(i, Scripts.p2pkhOutput(key.pubKey), chainSig)
        ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH -> tx.bip143SighashAll(i, Scripts.p2pkhScriptCode(key.pubKey), value, chainSig)
    }

    private fun apply(input: TxInput, key: HDKey, scriptType: ScriptType, sig: ByteArray) {
        when(scriptType) {
            ScriptType.P2PKH -> input.scriptSig = push(sig) + push(key.pubKey)
            ScriptType.P2SH_P2WPKH -> {
                input.scriptSig = push(Scripts.p2wpkhOutput(key.pubKey))
                input.witness = listOf(sig, key.pubKey)
            }
            ScriptType.P2WPKH -> input.witness = listOf(sig, key.pubKey)
        }
    }

    /** Direct push; every push here (signature, key, redeem script) is under 76 bytes. */
    private fun push(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data
}
