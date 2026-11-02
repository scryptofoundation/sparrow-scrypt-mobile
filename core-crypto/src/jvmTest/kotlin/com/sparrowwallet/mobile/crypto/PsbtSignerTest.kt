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
    private val account = accountPath(0, 0).take(3)

    @Test fun reviewMarksChangeAndFee() {
        val parsed = Psbt.parse(fixturePsbt())
        val r = PsbtSigner.review(parsed, master, net, account)
        assertEquals(listOf(false, true), r.outputs.map { it.change })
        assertEquals(Addresses.p2wpkh(destKey.pubKey), r.outputs[0].address)
        assertEquals(60_000_000, r.outputs[0].value)
        assertEquals(80_000_000L, r.totalIn)
        assertEquals(80_000_000L - r.totalOut, r.fee)
        assertEquals(listOf(true, true), r.inputs.map { it.ours })
        val vsize = parsed.unsignedTxBytes.size + ceil((2 + 108 * 2) / 4.0)
        assertEquals(round(r.fee!! / vsize * 100) / 100, r.feeRatePerVb)
    }

    /** An input worth the coin's usual inscription postage may carry one: the signer says so before signing. */
    @Test fun reviewFlagsAnInputThatMayCarryAnInscription() {
        val postage = SpendableInput(inputs[0].txid, inputs[0].vout, 10_000, inputs[0].key, inputs[0].scriptType)
        val psbt = PsbtFixtures.createPsbt(TxBuilder.buildUnsigned(listOf(postage) + inputs.drop(1), destScript, 0, 2, changeScript,
            sendMax = true, params = Network.MAINNET.litecoin))
        val r = PsbtSigner.review(Psbt.parse(psbt), master, net, account)
        assertEquals(listOf(10_000L), r.inputs.filter { it.mayCarry }.map { it.value })
        assertEquals(true, r.warning?.contains("may carry an inscription"))

        val plain = PsbtSigner.review(Psbt.parse(fixturePsbt()), master, net, account)
        assertEquals(false, plain.inputs.any { it.mayCarry })
        assertNull(plain.warning)
    }

    @Test fun changeWithForeignScriptIsNotChange() {
        // the output pays destKey; its derivation names destKey's pubkey but the change path,
        // which derives a different key: claiming it is change would hide a payment
        val lying = edit(fixturePsbt()) { maps ->
            maps[3] += (byteArrayOf(0x02) + destKey.pubKey) to derivationValue(master.fingerprint, accountPath(1, 0))
        }
        val r = PsbtSigner.review(Psbt.parse(lying), master, net, account)
        assertEquals(listOf(false, true), r.outputs.map { it.change })
    }

    @Test fun ourKeyOutsideTheAccountChainsIsNotChange() {
        // both pay keys of this seed, but at paths neither wallet would ever scan
        val far = PsbtFixtures.account.deriveChild(1).deriveChild(500_000)
        val otherAccount = master.derivePath("m/84'/2'/1'/1/0")
        for((key, path) in listOf(
            far to accountPath(1, 500_000),
            otherAccount to listOf(84L + PsbtFixtures.HARDENED, 2L + PsbtFixtures.HARDENED, 1L + PsbtFixtures.HARDENED, 1, 0)
        )) {
            val script = Scripts.p2wpkhOutput(key.pubKey)
            val psbt = PsbtFixtures.createPsbt(TxBuilder.buildUnsigned(inputs, script, 60_000_000, 2, changeScript, params = Network.MAINNET.litecoin))
            val claimed = edit(psbt) { maps ->
                maps[3] += (byteArrayOf(0x02) + key.pubKey) to derivationValue(master.fingerprint, path)
            }
            val r = PsbtSigner.review(Psbt.parse(claimed), master, net, account)
            assertEquals(listOf(false, true), r.outputs.map { it.change }, "path $path")
        }
    }

    /** Input 2 spends a coin of this seed at [path] instead, with its previous transaction. */
    private fun withInput1At(path: List<Long>): ByteArray {
        var key = master
        for(child in path) key = key.deriveChild(child)
        val prev = Tx(2, listOf(TxInput("ee".repeat(32), 0, 0xFFFFFFFFL, ByteArray(0))),
            listOf(TxOutput(1_000, destScript), TxOutput(30_000_000, Scripts.p2wpkhOutput(key.pubKey))))
        val spend = listOf(inputs[0], SpendableInput(prev.txid(), 1, 30_000_000, key, ScriptType.P2WPKH))
        val psbt = Psbt.create(TxBuilder.buildUnsigned(spend, destScript, 60_000_000, 2, changeScript, params = Network.MAINNET.litecoin), master.fingerprint,
            pathFor = { if(it.key == PsbtFixtures.key0) accountPath(0, 0) else path },
            changeIndex = 1, changePubKey = PsbtFixtures.changeKey.pubKey, changePath = accountPath(1, 0))
        return edit(psbt) { maps ->
            maps[1].add(0, byteArrayOf(0x00) to PsbtFixtures.prevA.serialize())
            maps[2].add(0, byteArrayOf(0x00) to prev.serialize())
        }
    }

    @Test fun inputsOutsideTheAccountAreNotSigned() {
        val h = PsbtFixtures.HARDENED
        val bitcoinPath = listOf(84L + h, 0L + h, 0L + h, 0, 0)   // same seed, BTC: BIP143 sigs are valid there
        val otherAccount = listOf(84L + h, 2L + h, 1L + h, 0, 0)
        val farIndex = accountPath(0, 500_000)
        for(path in listOf(bitcoinPath, otherAccount, farIndex)) {
            val psbt = Psbt.parse(withInput1At(path))
            val signed = Psbt.parse(PsbtSigner.sign(psbt, master, net, account))
            assertEquals(1, signed.inputs[0].partialSigs.size, "path $path")
            assertEquals(0, signed.inputs[1].partialSigs.size, "path $path")
            assertEquals(listOf(true, false), PsbtSigner.review(psbt, master, net, account).inputs.map { it.ours })
        }
    }

    @Test fun onlyOutOfAccountInputsIsRefused() {
        val h = PsbtFixtures.HARDENED
        val btc = edit(withInput1At(listOf(84L + h, 0L + h, 0L + h, 0, 0))) { maps ->
            maps[1].removeAll { it.first[0].toInt() == 0x06 } // input 0 no longer names a key at all
        }
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(btc), master, net, account) }
        assertEquals("This PSBT has no inputs belonging to this wallet", e.message)
    }

    @Test fun staleSignatureForOurKeyIsReplaced() {
        val stale = edit(fixturePsbt()) { maps -> maps[1] += (byteArrayOf(0x02) + PsbtFixtures.key0.pubKey) to ByteArray(71) { 1 } }
        val signed = PsbtSigner.sign(Psbt.parse(stale), master, net, account)
        val direct = TxBuilder.build(inputs, destScript, 60_000_000, 2, changeScript, params = Network.MAINNET.litecoin)
        assertEquals(1, Psbt.parse(signed).inputs[0].partialSigs.size)
        assertContentEquals(direct.tx.serialize(), Psbt.finalize(Psbt.parse(signed)).serialize())
    }

    @Test fun unknownScriptShownRawNotChange() {
        val opReturn = byteArrayOf(0x6a, 0x04, 1, 2, 3, 4)
        val psbt = PsbtFixtures.createPsbt(TxBuilder.buildUnsigned(inputs, opReturn, 60_000_000, 2, changeScript, params = Network.MAINNET.litecoin))
        val r = PsbtSigner.review(Psbt.parse(psbt), master, net, account)
        assertNull(r.outputs[0].address)
        assertEquals("6a0401020304", r.outputs[0].scriptHex)
        assertEquals(false, r.outputs[0].change)
    }

    @Test fun witnessUtxoForOtherKeyIsRefused() {
        val swapped = edit(fixturePsbt()) { maps ->
            maps[1].removeAll { it.first[0].toInt() == 0x00 } // else parsing already refuses the swap
            val i = maps[1].indexOfFirst { it.first[0].toInt() == 0x01 }
            maps[1][i] = maps[1][i].first to ByteWriter().u64le(50_000_000).varBytes(destScript).toByteArray()
        }
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(swapped), master, net, account) }
        assertEquals("Input 1: witness UTXO does not match this wallet's key", e.message)
    }

    @Test fun derivationPubkeyMismatchIsRefused() {
        // input 0 claims key1's pubkey at key0's path
        val mislabeled = edit(fixturePsbt()) { maps ->
            val i = maps[1].indexOfFirst { it.first[0].toInt() == 0x06 }
            maps[1][i] = (byteArrayOf(0x06) + key1.pubKey) to maps[1][i].second
        }
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(mislabeled), master, net, account) }
        assertEquals("Input 1: witness UTXO does not match this wallet's key", e.message)
    }

    @Test fun inputWithNeitherUtxoIsRefused() {
        val noUtxo = edit(fixturePsbt()) { maps -> maps[1].removeAll { it.first[0].toInt() == 0x01 || it.first[0].toInt() == 0x00 } }
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(noUtxo), master, net, account) }
        assertEquals("Input 1 has no witness UTXO or previous transaction", e.message)
        assertNull(PsbtSigner.review(Psbt.parse(noUtxo), master, net, account).fee)
    }

    @Test fun segwitInputWithOnlyItsPreviousTxIsSigned() {
        //the full previous transaction proves the amount a BIP143 signature commits to
        val prevOnly = edit(fixturePsbt()) { maps -> maps[1].removeAll { it.first[0].toInt() == 0x01 } }
        val signed = Psbt.parse(PsbtSigner.sign(Psbt.parse(prevOnly), master, net, account))
        assertEquals(1, signed.inputs[0].partialSigs.size)
        assertEquals(PsbtSigner.review(Psbt.parse(fixturePsbt()), master, net, account).fee,
            PsbtSigner.review(Psbt.parse(prevOnly), master, net, account).fee)
    }

    @Test fun foreignInputsAreLeftUnsigned() {
        val shared = edit(fixturePsbt()) { maps ->
            val i = maps[2].indexOfFirst { it.first[0].toInt() == 0x06 }
            maps[2][i] = maps[2][i].first to derivationValue(ByteArray(4), accountPath(0, 1))
        }
        val signed = Psbt.parse(PsbtSigner.sign(Psbt.parse(shared), master, net, account))
        assertEquals(1, signed.inputs[0].partialSigs.size)
        assertEquals(0, signed.inputs[1].partialSigs.size)
        assertEquals(listOf(true, false), PsbtSigner.review(Psbt.parse(shared), master, net, account).inputs.map { it.ours })
    }

    @Test fun foreignOnlyPsbtIsRefused() {
        val other = HDKey.fromSeed(Bip39.seed(
            "legal winner thank year wave sausage worth useful legal winner thank yellow", ""))
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(fixturePsbt()), other, net, account) }
        assertEquals("This PSBT has no inputs belonging to this wallet", e.message)
    }

    @Test fun signMatchesDirectSigning() {
        val direct = TxBuilder.build(inputs, destScript, 60_000_000, 2, changeScript, params = Network.MAINNET.litecoin)
        val signed = PsbtSigner.sign(Psbt.parse(fixturePsbt()), master, net, account)
        assertContentEquals(direct.tx.serialize(), Psbt.finalize(Psbt.parse(signed)).serialize())
    }

    // Two signing sessions, each lying about a different input's amount, would combine into a
    // transaction whose fee the user never saw. The full previous transactions pin the amounts.
    private val prevTxRefusal = "Inputs 1, 2 are missing the full previous transaction. When a PSBT has " +
        "more than one input, the phone needs each one to check the fee. On the PC, import this wallet " +
        "with the Sparrow Mobile option."

    @Test fun severalOfOurInputsWithoutPrevTxsAreRefused() {
        val parsed = Psbt.parse(PsbtFixtures.withoutPrevTxs())
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(parsed, master, net, account) }
        assertEquals(prevTxRefusal, e.message)
        assertEquals(prevTxRefusal, PsbtSigner.review(parsed, master, net, account).refusal)
    }

    @Test fun oneMissingPrevTxIsNamed() {
        val half = edit(fixturePsbt()) { maps -> maps[2].removeAll { it.first[0].toInt() == 0x00 } }
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(half), master, net, account) }
        assertEquals(prevTxRefusal.replace("Inputs 1, 2 are", "Input 2 is"), e.message)
    }

    @Test fun inputHiddenAsForeignStillNeedsPrevTx() {
        // the desktop drops our derivation from input 2 in one session and from input 1 in the next:
        // each session signs one input, and the other input's amount in the shown fee is unchecked
        val hidden = edit(PsbtFixtures.withoutPrevTxs()) { maps -> maps[2].removeAll { it.first[0].toInt() == 0x06 } }
        val parsed = Psbt.parse(hidden)
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(parsed, master, net, account) }
        assertEquals(prevTxRefusal, e.message)
        assertEquals(listOf(true, false), PsbtSigner.review(parsed, master, net, account).inputs.map { it.ours })
    }

    @Test fun singleInputNeedsNoPrevTx() {
        // the one signature commits to the only input's amount, so the shown fee is the real one
        val psbt = PsbtFixtures.createPsbt(TxBuilder.buildUnsigned(inputs.take(1), destScript, 40_000_000, 2, changeScript, params = Network.MAINNET.litecoin))
        val parsed = Psbt.parse(psbt)
        assertNull(PsbtSigner.review(parsed, master, net, account).refusal)
        assertEquals(1, Psbt.parse(PsbtSigner.sign(parsed, master, net, account)).inputs[0].partialSigs.size)
    }

    @Test fun reviewOfACompletePsbtHasNoRefusal() {
        assertNull(PsbtSigner.review(Psbt.parse(fixturePsbt()), master, net, account).refusal)
    }
}
