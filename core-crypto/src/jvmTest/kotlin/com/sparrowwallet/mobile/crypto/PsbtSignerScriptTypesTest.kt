package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phone's PSBT signer for every script type: Legacy and Nested Segwit sign exactly as direct
 * signing does, a Legacy coin is never signed without its previous transaction, and the review
 * reads the wallet coin's addresses.
 */
class PsbtSignerScriptTypesTest {
    private val master = PsbtFixtures.master
    private val h = PsbtFixtures.HARDENED

    private class Wallet(val params: ChainParams, val type: ScriptType, val account: HDKey, val accountPath: List<Long>)

    private fun wallet(coin: Coin, type: ScriptType): Wallet {
        val params = ChainParams.of(coin, Network.MAINNET)
        val path = listOf(type.purpose.toLong() + h, coin.coinType.toLong() + h, 0L + h)
        var account = master
        for(child in path) account = account.deriveChild(child)
        return Wallet(params, type, account, path)
    }

    private fun prevTx(script: ByteArray, value: Long, salt: String) =
        Tx(2, listOf(TxInput(salt.repeat(32), 0, 0xFFFFFFFFL, ByteArray(0))), listOf(TxOutput(value, script)))

    private fun inputs(w: Wallet): List<SpendableInput> = listOf(0L to "aa", 1L to "bb").map { (index, salt) ->
        val key = w.account.deriveChild(0).deriveChild(index)
        val prev = prevTx(Scripts.outputScript(key.pubKey, w.type), 3_000_000_000, salt)
        SpendableInput(prev.txid(), 0, 3_000_000_000, key, w.type, prev.serialize())
    }

    private fun dest(w: Wallet) = Scripts.outputScript(w.account.deriveChild(0).deriveChild(9).pubKey, w.type)
    private fun change(w: Wallet) = Scripts.outputScript(w.account.deriveChild(1).deriveChild(0).pubKey, w.type)
    private val rate = 1000L

    private fun psbt(w: Wallet, ins: List<SpendableInput> = inputs(w), amount: Long = 4_000_000_000): ByteArray {
        val unsigned = TxBuilder.buildUnsigned(ins, dest(w), amount, rate, change(w), params = w.params)
        val first = w.account.deriveChild(0).deriveChild(0).pubKey
        return Psbt.create(unsigned, master.fingerprint,
            pathFor = { input -> w.accountPath + listOf(0L, if(input.key.pubKey.contentEquals(first)) 0L else 1L) },
            changeIndex = 1, changePubKey = w.account.deriveChild(1).deriveChild(0).pubKey, changePath = w.accountPath + listOf(1L, 0L))
    }

    private fun signedTx(w: Wallet, psbt: ByteArray): Tx =
        Psbt.finalize(Psbt.parse(PsbtSigner.sign(Psbt.parse(psbt), master, w.params, w.accountPath, w.type)))

    @Test
    fun signsLegacyInputs() {
        val w = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        val direct = TxBuilder.build(inputs(w), dest(w), 4_000_000_000, rate, change(w), params = w.params)
        assertContentEquals(direct.tx.serialize(), signedTx(w, psbt(w)).serialize())
    }

    @Test
    fun signsNestedInputs() {
        val w = wallet(Coin.LITECOIN, ScriptType.P2SH_P2WPKH)
        val direct = TxBuilder.build(inputs(w), dest(w), 4_000_000_000, rate, change(w), params = w.params)
        assertContentEquals(direct.tx.serialize(), signedTx(w, psbt(w)).serialize())
    }

    @Test
    fun legacyInputWithoutPreviousTxIsRefused() {
        val w = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        val one = psbt(w, inputs(w).take(1), amount = 1_000_000_000)
        val stripped = PsbtFixtures.edit(one) { maps -> maps[1].removeAll { it.first[0].toInt() == 0x00 } }
        val parsed = Psbt.parse(stripped)
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(parsed, master, w.params, w.accountPath, w.type) }
        assertTrue("previous transaction" in e.message!!, e.message)
        assertNotNull(PsbtSigner.review(parsed, master, w.params, w.accountPath, w.type).refusal)
    }

    @Test
    fun reviewUsesTheWalletsCoin() {
        val w = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        val r = PsbtSigner.review(Psbt.parse(psbt(w)), master, w.params, w.accountPath, w.type)
        assertTrue(r.outputs.all { it.address!!.startsWith("D") }, r.outputs.toString())
        assertEquals(listOf(false, true), r.outputs.map { it.change })
        assertEquals(6_000_000_000, r.totalIn)
        val feeRate = assertNotNull(r.feeRatePerVb)
        assertTrue(feeRate in 990.0..1010.0, "fee rate $feeRate")
        assertEquals(null, r.refusal)
    }

    @Test
    fun wrongScriptTypeIsRefused() {
        //a Native Segwit coin offered to a Legacy wallet on the same account path is not this wallet's
        val segwit = wallet(Coin.LITECOIN, ScriptType.P2WPKH)
        val legacy = Wallet(segwit.params, ScriptType.P2PKH, segwit.account, segwit.accountPath)
        val e = assertFailsWith<IllegalArgumentException> {
            PsbtSigner.sign(Psbt.parse(psbt(segwit)), master, legacy.params, legacy.accountPath, legacy.type)
        }
        assertTrue("does not match" in e.message!!, e.message)
    }

    @Test
    fun reviewWarnsWhenTheWalletSignsOnBitcoinsKeyPath() {
        val lky = wallet(Coin.LUCKYCOIN, ScriptType.P2PKH)
        val warning = PsbtSigner.review(Psbt.parse(psbt(lky)), master, lky.params, lky.accountPath, lky.type).warning
        assertTrue(warning != null && "Bitcoin" in warning, warning)
        val doge = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        assertEquals(null, PsbtSigner.review(Psbt.parse(psbt(doge)), master, doge.params, doge.accountPath, doge.type).warning)
    }

    @Test
    fun reviewRefusesOurInputWhoseScriptIsNotTheWallets() {
        val nested = wallet(Coin.LITECOIN, ScriptType.P2SH_P2WPKH)
        val r = PsbtSigner.review(Psbt.parse(psbt(nested)), master, nested.params, nested.accountPath, ScriptType.P2WPKH)
        val refusal = assertNotNull(r.refusal)
        assertTrue("does not match this wallet's key" in refusal, refusal)
    }

    @Test
    fun feeRateFollowsTheScriptsTheInputsSpend() {
        val nested = wallet(Coin.LITECOIN, ScriptType.P2SH_P2WPKH)
        val parsed = Psbt.parse(psbt(nested))
        val asNested = PsbtSigner.review(parsed, master, nested.params, nested.accountPath, ScriptType.P2SH_P2WPKH).feeRatePerVb
        val asNative = PsbtSigner.review(parsed, master, nested.params, nested.accountPath, ScriptType.P2WPKH).feeRatePerVb
        assertEquals(asNested, asNative)
    }

    @Test
    fun finalizeUsesTheSignatureOfTheKeyThatOwnsTheCoin() {
        val w = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        val direct = TxBuilder.build(inputs(w), dest(w), 4_000_000_000, rate, change(w), params = w.params).tx.serialize()
        val signed = PsbtSigner.sign(Psbt.parse(psbt(w)), master, w.params, w.accountPath, w.type)
        //signatures by other keys alongside ours: whatever order they come back in, ours is the one placed
        val strays = (2L..40L).map { index ->
            val stray = w.account.deriveChild(0).deriveChild(index)
            (byteArrayOf(0x02) + stray.pubKey) to Ecdsa.signForInput(ByteArray(32) { 7 }, stray.privKey)
        }
        val withStrays = PsbtFixtures.edit(signed) { maps -> maps[1].addAll(0, strays); maps[2].addAll(0, strays) }
        assertContentEquals(direct, Psbt.finalize(Psbt.parse(withStrays)).serialize())
    }
}
