package com.sparrowwallet.mobile.crypto

import com.sparrowwallet.mobile.crypto.PsbtFixtures.accountPath
import com.sparrowwallet.mobile.crypto.PsbtFixtures.changeScript
import com.sparrowwallet.mobile.crypto.PsbtFixtures.derivationValue
import com.sparrowwallet.mobile.crypto.PsbtFixtures.destKey
import com.sparrowwallet.mobile.crypto.PsbtFixtures.destScript
import com.sparrowwallet.mobile.crypto.PsbtFixtures.edit
import com.sparrowwallet.mobile.crypto.PsbtFixtures.fixturePsbt
import com.sparrowwallet.mobile.crypto.PsbtFixtures.inputs
import com.sparrowwallet.mobile.crypto.PsbtFixtures.key1
import com.sparrowwallet.mobile.crypto.PsbtFixtures.master
import kotlin.math.ceil
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Signing a PSBT that came from somewhere else: review what it does, sign only what is ours. */
class PsbtSignerTest {
    private val net = Network.MAINNET

    @Test fun reviewMarksChangeAndFee() {
        val parsed = Psbt.parse(fixturePsbt())
        val r = PsbtSigner.review(parsed, master, net)
        assertEquals(listOf(false, true), r.outputs.map { it.change })
        assertEquals(Addresses.p2wpkh(destKey.pubKey), r.outputs[0].address)
        assertEquals(60_000_000, r.outputs[0].value)
        assertEquals(80_000_000L, r.totalIn)
        assertEquals(80_000_000L - r.totalOut, r.fee)
        assertEquals(listOf(true, true), r.inputs.map { it.ours })
        val vsize = parsed.unsignedTxBytes.size + ceil((2 + 108 * 2) / 4.0)
        assertEquals(round(r.fee!! / vsize * 100) / 100, r.feeRateLitPerVb)
    }

    @Test fun changeWithForeignScriptIsNotChange() {
        // the output pays destKey; its derivation names destKey's pubkey but the change path,
        // which derives a different key: claiming it is change would hide a payment
        val lying = edit(fixturePsbt()) { maps ->
            maps[3] += (byteArrayOf(0x02) + destKey.pubKey) to derivationValue(master.fingerprint, accountPath(1, 0))
        }
        val r = PsbtSigner.review(Psbt.parse(lying), master, net)
        assertEquals(listOf(false, true), r.outputs.map { it.change })
    }

    @Test fun unknownScriptShownRawNotChange() {
        val opReturn = byteArrayOf(0x6a, 0x04, 1, 2, 3, 4)
        val psbt = PsbtFixtures.createPsbt(TxBuilder.buildUnsigned(inputs, opReturn, 60_000_000, 2, changeScript))
        val r = PsbtSigner.review(Psbt.parse(psbt), master, net)
        assertNull(r.outputs[0].address)
        assertEquals("6a0401020304", r.outputs[0].scriptHex)
        assertEquals(false, r.outputs[0].change)
    }

    @Test fun witnessUtxoForOtherKeyIsRefused() {
        val swapped = edit(fixturePsbt()) { maps ->
            val i = maps[1].indexOfFirst { it.first[0].toInt() == 0x01 }
            maps[1][i] = maps[1][i].first to ByteWriter().u64le(50_000_000).varBytes(destScript).toByteArray()
        }
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(swapped), master, net) }
        assertEquals("Input 1: witness UTXO does not match this wallet's key", e.message)
    }

    @Test fun derivationPubkeyMismatchIsRefused() {
        // input 0 claims key1's pubkey at key0's path
        val mislabeled = edit(fixturePsbt()) { maps ->
            val i = maps[1].indexOfFirst { it.first[0].toInt() == 0x06 }
            maps[1][i] = (byteArrayOf(0x06) + key1.pubKey) to maps[1][i].second
        }
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(mislabeled), master, net) }
        assertEquals("Input 1: witness UTXO does not match this wallet's key", e.message)
    }

    @Test fun notSegwitInputIsRefused() {
        val noUtxo = edit(fixturePsbt()) { maps -> maps[1].removeAll { it.first[0].toInt() == 0x01 } }
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(noUtxo), master, net) }
        assertEquals("Input 1 is not native segwit (P2WPKH)", e.message)
        assertNull(PsbtSigner.review(Psbt.parse(noUtxo), master, net).fee)
    }

    @Test fun foreignInputsAreLeftUnsigned() {
        val shared = edit(fixturePsbt()) { maps ->
            val i = maps[2].indexOfFirst { it.first[0].toInt() == 0x06 }
            maps[2][i] = maps[2][i].first to derivationValue(ByteArray(4), accountPath(0, 1))
        }
        val signed = Psbt.parse(PsbtSigner.sign(Psbt.parse(shared), master, net))
        assertEquals(1, signed.inputs[0].partialSigs.size)
        assertEquals(0, signed.inputs[1].partialSigs.size)
        assertEquals(listOf(true, false), PsbtSigner.review(Psbt.parse(shared), master, net).inputs.map { it.ours })
    }

    @Test fun foreignOnlyPsbtIsRefused() {
        val other = HDKey.fromSeed(Bip39.seed(
            "legal winner thank year wave sausage worth useful legal winner thank yellow", ""))
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(fixturePsbt()), other, net) }
        assertEquals("This PSBT has no inputs belonging to this wallet", e.message)
    }

    @Test fun signMatchesDirectSigning() {
        val direct = TxBuilder.build(inputs, destScript, 60_000_000, 2, changeScript)
        val signed = PsbtSigner.sign(Psbt.parse(fixturePsbt()), master, net)
        assertContentEquals(direct.tx.serialize(), Psbt.finalize(Psbt.parse(signed)).serialize())
    }
}
