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
        Psbt.finalize(Psbt.parse(PsbtSigner.sign(Psbt.parse(psbt), master, w.params, w.accountPath, w.type, chainTip = null)))

    @Test
    fun signsLegacyInputs() {
        val w = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        val direct = TxBuilder.build(inputs(w), dest(w), 4_000_000_000, rate, change(w), params = w.params, chainTip = null)
        assertContentEquals(direct.tx.serialize(), signedTx(w, psbt(w)).serialize())
    }

    @Test
    fun signsNestedInputs() {
        val w = wallet(Coin.LITECOIN, ScriptType.P2SH_P2WPKH)
        val direct = TxBuilder.build(inputs(w), dest(w), 4_000_000_000, rate, change(w), params = w.params, chainTip = null)
        assertContentEquals(direct.tx.serialize(), signedTx(w, psbt(w)).serialize())
    }

    @Test
    fun legacyInputWithoutPreviousTxIsRefused() {
        val w = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        val one = psbt(w, inputs(w).take(1), amount = 1_000_000_000)
        val stripped = PsbtFixtures.edit(one) { maps -> maps[1].removeAll { it.first[0].toInt() == 0x00 } }
        val parsed = Psbt.parse(stripped)
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(parsed, master, w.params, w.accountPath, w.type, chainTip = null) }
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
            PsbtSigner.sign(Psbt.parse(psbt(segwit)), master, legacy.params, legacy.accountPath, legacy.type, chainTip = null)
        }
        assertTrue("does not match" in e.message!!, e.message)
    }

    /** The coin mark's key, the same bytes desktop drongo writes (PSBT.COIN_MARK_KEY): fc, "sparrow-scrypt", subtype 0. */
    private val markKey = "fc0e" + "sparrow-scrypt".encodeToByteArray().toHex() + "00"

    private fun withMark(psbt: ByteArray, coinId: String?): ByteArray = PsbtFixtures.edit(psbt) { maps ->
        maps[0].removeAll { it.first.toHex() == markKey }
        if(coinId != null) maps[0].add(markKey.hexToBytes() to coinId.encodeToByteArray())
    }

    private fun review(w: Wallet, psbt: ByteArray) = PsbtSigner.review(Psbt.parse(psbt), master, w.params, w.accountPath, w.type)

    @Test
    fun phonePsbtsCarryTheirCoin() {
        val parsed = Psbt.parse(psbt(wallet(Coin.LUCKYCOIN, ScriptType.P2PKH)))
        assertEquals("luckycoin", parsed.coinMark)
        assertTrue(parsed.globalEntries.any { it.first.toHex() == markKey && it.second.decodeToString() == "luckycoin" })
    }

    /** Luckycoin and Junkcoin keys sit on Bitcoin's path: only the mark tells their PSBTs from Bitcoin's, so none means no. */
    @Test
    fun bitcoinPathWalletRefusesAnUnmarkedPsbt() {
        val lky = wallet(Coin.LUCKYCOIN, ScriptType.P2PKH)
        val unmarked = withMark(psbt(lky), null)
        val refusal = assertNotNull(review(lky, unmarked).refusal)
        assertTrue("which coin" in refusal, refusal)
        assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(unmarked), master, lky.params, lky.accountPath, lky.type, chainTip = null) }
    }

    @Test
    fun psbtMarkedForAnotherCoinIsRefused() {
        val jkc = wallet(Coin.JUNKCOIN, ScriptType.P2PKH)
        val bitcoin = withMark(psbt(jkc), "bitcoin")
        assertTrue("is for bitcoin" in assertNotNull(review(jkc, bitcoin).refusal))
        assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(Psbt.parse(bitcoin), master, jkc.params, jkc.accountPath, jkc.type, chainTip = null) }
        val doge = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        assertTrue("is for Pepecoin" in assertNotNull(review(doge, withMark(psbt(doge), "pepecoin")).refusal))
    }

    @Test
    fun markedPsbtSignsOnBitcoinPathWallet() {
        val lky = wallet(Coin.LUCKYCOIN, ScriptType.P2PKH)
        val r = review(lky, psbt(lky))
        assertEquals(null, r.refusal)
        assertEquals(null, r.warning)
        val direct = TxBuilder.build(inputs(lky), dest(lky), 4_000_000_000, rate, change(lky), params = lky.params, chainTip = null)
        assertContentEquals(direct.tx.serialize(), signedTx(lky, psbt(lky)).serialize())
    }

    /** A coin with its own key path can still sign PSBTs from desktop builds and software that write no mark. */
    @Test
    fun ownPathWalletSignsAnUnmarkedPsbt() {
        val doge = wallet(Coin.DOGECOIN, ScriptType.P2PKH)
        val unmarked = withMark(psbt(doge), null)
        assertEquals(null, review(doge, unmarked).refusal)
        assertEquals(null, Psbt.parse(unmarked).coinMark)
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
        val direct = TxBuilder.build(inputs(w), dest(w), 4_000_000_000, rate, change(w), params = w.params, chainTip = null).tx.serialize()
        val signed = PsbtSigner.sign(Psbt.parse(psbt(w)), master, w.params, w.accountPath, w.type, chainTip = null)
        //signatures by other keys alongside ours: whatever order they come back in, ours is the one placed
        val strays = (2L..40L).map { index ->
            val stray = w.account.deriveChild(0).deriveChild(index)
            (byteArrayOf(0x02) + stray.pubKey) to Ecdsa.signForInput(ByteArray(32) { 7 }, stray.privKey)
        }
        val withStrays = PsbtFixtures.edit(signed) { maps -> maps[1].addAll(0, strays); maps[2].addAll(0, strays) }
        assertContentEquals(direct, Psbt.finalize(Psbt.parse(withStrays)).serialize())
    }
}
