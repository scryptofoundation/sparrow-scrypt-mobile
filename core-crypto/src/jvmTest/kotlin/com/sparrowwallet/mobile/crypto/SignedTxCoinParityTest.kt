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
            InputSigner.signAll(tx, v.inputs.map { master.derivePath(it.keyPath) }, ScriptType.valueOf(v.scriptType), v.inputs.map { it.value })
            assertEquals(v.txHex, tx.serialize().toHex(), "${v.coin} ${v.network} ${v.scriptType}")
        }
        assertEquals(22, CoinVectors.file.signedTxs.size)
    }

    @Test fun legacyTxHasNoSegwitMarker() {
        val v = CoinVectors.file.signedTxs.first { it.coin == "DOGECOIN" && it.scriptType == "P2PKH" }
        assertNotEquals("0001", v.txHex.substring(8, 12))
    }
}
