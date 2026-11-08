package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Fully signed two-input transactions for every coin and script type, byte for byte against drongo. */
class SignedTxCoinParityTest {
    private val master = HDKey.fromSeed(Bip39.seed(CoinVectors.file.mnemonic, ""))

    @Test fun everySignedTxMatchesDrongoByteForByte() {
        for(v in CoinVectors.file.signedTxs) {
            val tx = Tx(
                2,
                v.inputs.map { TxInput(it.prevTxid, it.vout, it.sequence) },
                v.outputs.map { TxOutput(it.value, it.script.hexToBytes()) }
            )
            val scriptType = ScriptType.valueOf(v.scriptType)
            val keys = v.inputs.map { master.derivePath(it.keyPath) }
            // The scriptPubKey each input spends, as drongo derived it, must be the one the phone derives
            for((i, input) in v.inputs.withIndex()) {
                assertEquals(input.spentScript, Scripts.outputScript(keys[i].pubKey, scriptType).toHex(), "${v.coin} ${v.network} $scriptType input $i spent script")
            }
            InputSigner.signAll(tx, keys, scriptType, v.inputs.map { it.value })
            assertEquals(v.txHex, tx.serialize().toHex(), "${v.coin} ${v.network} ${v.scriptType}")
        }
        assertEquals(25, CoinVectors.file.signedTxs.size)
        assertEquals(3, CoinVectors.file.signedTxs.count { it.scriptType == "P2TR" })
    }

    @Test fun legacyTxHasNoSegwitMarker() {
        val v = CoinVectors.file.signedTxs.first { it.coin == "DOGECOIN" && it.scriptType == "P2PKH" }
        assertNotEquals("0001", v.txHex.substring(8, 12))
    }
}
