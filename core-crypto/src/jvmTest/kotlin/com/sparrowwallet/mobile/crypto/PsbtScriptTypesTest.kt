package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PSBTs for Legacy, Nested Segwit and Native Segwit inputs: what Psbt.create writes per type, and
 * Psbt.finalize turning each type's signatures into the same transaction direct signing makes.
 */
class PsbtScriptTypesTest {
    private val master = PsbtFixtures.master
    private val ltc = Network.MAINNET.litecoin

    private class Wallet(val type: ScriptType, val purpose: Long, val account: HDKey)

    private fun wallet(type: ScriptType): Wallet {
        val purpose = type.purpose.toLong()
        return Wallet(type, purpose, master.derivePath("m/$purpose'/2'/0'"))
    }

    private fun prevTx(script: ByteArray, value: Long, salt: String) =
        Tx(2, listOf(TxInput(salt.repeat(32), 0, 0xFFFFFFFFL, ByteArray(0))), listOf(TxOutput(value, script)))

    /** Two coins of [w]'s type, each with the transaction that made it. */
    private fun inputs(w: Wallet, withPrevTx: Boolean = true): List<SpendableInput> {
        val k0 = w.account.deriveChild(0).deriveChild(0)
        val k1 = w.account.deriveChild(0).deriveChild(1)
        val p0 = prevTx(Scripts.outputScript(k0.pubKey, w.type), 50_000_000, "aa")
        val p1 = prevTx(Scripts.outputScript(k1.pubKey, w.type), 30_000_000, "bb")
        return listOf(
            SpendableInput(p0.txid(), 0, 50_000_000, k0, w.type, if(withPrevTx) p0.serialize() else null),
            SpendableInput(p1.txid(), 0, 30_000_000, k1, w.type, if(withPrevTx) p1.serialize() else null)
        )
    }

    private fun dest(w: Wallet) = Scripts.outputScript(w.account.deriveChild(0).deriveChild(9).pubKey, w.type)
    private fun change(w: Wallet) = Scripts.outputScript(w.account.deriveChild(1).deriveChild(0).pubKey, w.type)

    private fun path(w: Wallet, input: SpendableInput): List<Long> {
        val h = PsbtFixtures.HARDENED
        val index = if(input.key == w.account.deriveChild(0).deriveChild(0)) 0L else 1L
        return listOf(w.purpose + h, 2L + h, 0L + h, 0L, index)
    }

    private fun create(w: Wallet, inputs: List<SpendableInput>): ByteArray =
        Psbt.create(TxBuilder.buildUnsigned(inputs, dest(w), 60_000_000, 2, change(w), params = ltc),
            master.fingerprint, pathFor = { path(w, it) })

    /** The signature direct signing put on input [i], for injecting as a PSBT partial signature. */
    private fun signature(signed: Tx, i: Int, type: ScriptType): ByteArray = when(type) {
        ScriptType.P2PKH -> signed.inputs[i].scriptSig.let { it.copyOfRange(1, 1 + (it[0].toInt() and 0xFF)) }
        else -> signed.inputs[i].witness[0]
    }

    @Test
    fun legacyInputNeedsItsPreviousTransaction() {
        val w = wallet(ScriptType.P2PKH)
        val e = assertFailsWith<IllegalArgumentException> { create(w, inputs(w, withPrevTx = false)) }
        assertEquals("A Legacy input needs its previous transaction", e.message)
    }

    @Test
    fun legacyPsbtCarriesThePreviousTransaction() {
        val w = wallet(ScriptType.P2PKH)
        val ins = inputs(w)
        val parsed = Psbt.parse(create(w, ins))
        for((i, input) in parsed.inputs.withIndex()) {
            assertContentEquals(ins[i].prevTx, input.nonWitnessUtxo)
            assertNull(input.witnessUtxoScript)
        }
    }

    @Test
    fun nestedInputCarriesItsRedeemScript() {
        val w = wallet(ScriptType.P2SH_P2WPKH)
        val ins = inputs(w)
        val parsed = Psbt.parse(create(w, ins))
        for((i, input) in parsed.inputs.withIndex()) {
            assertContentEquals(Scripts.p2wpkhOutput(ins[i].key.pubKey), input.redeemScript)
            assertContentEquals(Scripts.outputScript(ins[i].key.pubKey, ScriptType.P2SH_P2WPKH), input.witnessUtxoScript)
            assertEquals(ins[i].value, input.witnessUtxoValue)
        }
    }

    @Test
    fun finalizeBuildsTheScriptSigPerType() {
        for(type in ScriptType.entries.filter { it != ScriptType.P2TR }) {
            val w = wallet(type)
            val ins = inputs(w)
            val direct = TxBuilder.build(ins, dest(w), 60_000_000, 2, change(w), params = ltc).tx
            val psbt = PsbtFixtures.edit(create(w, ins)) { maps ->
                for(i in ins.indices) maps[1 + i] += (byteArrayOf(0x02) + ins[i].key.pubKey) to signature(direct, i, type)
            }
            val final = Psbt.finalize(Psbt.parse(psbt))
            assertContentEquals(direct.serialize(), final.serialize(), "$type")
            when(type) {
                ScriptType.P2PKH -> assertTrue(final.inputs.all { it.witness.isEmpty() })
                ScriptType.P2SH_P2WPKH -> assertTrue(final.inputs.all { it.scriptSig.size == 23 && it.witness.size == 2 })
                ScriptType.P2WPKH -> assertTrue(final.inputs.all { it.scriptSig.isEmpty() && it.witness.size == 2 })
                ScriptType.P2TR -> throw UnsupportedOperationException("Taproot: Task 6")
            }
        }
    }

    @Test
    fun prevTxWithTheWrongTxidIsRefusedAtCreate() {
        val w = wallet(ScriptType.P2PKH)
        val ins = inputs(w)
        val other = prevTx(Scripts.outputScript(ins[0].key.pubKey, w.type), 50_000_000, "cc").serialize()
        val swapped = listOf(SpendableInput(ins[0].txid, 0, ins[0].value, ins[0].key, w.type, other), ins[1])
        assertFailsWith<IllegalArgumentException> { create(w, swapped) }
    }

    @Test
    fun alreadyFinalizedInputIsKept() {
        for(type in ScriptType.entries.filter { it != ScriptType.P2TR }) {
            val w = wallet(type)
            val ins = inputs(w)
            val direct = TxBuilder.build(ins, dest(w), 60_000_000, 2, change(w), params = ltc).tx
            //another signer already finalized every input: its final scriptSig and witness are used as they are
            val psbt = PsbtFixtures.edit(create(w, ins)) { maps ->
                for(i in ins.indices) {
                    val input = direct.inputs[i]
                    if(input.scriptSig.isNotEmpty()) maps[1 + i] += byteArrayOf(0x07) to input.scriptSig
                    if(input.witness.isNotEmpty()) {
                        val wv = ByteWriter().varInt(input.witness.size.toLong())
                        for(item in input.witness) wv.varBytes(item)
                        maps[1 + i] += byteArrayOf(0x08) to wv.toByteArray()
                    }
                }
            }
            assertContentEquals(direct.serialize(), Psbt.finalize(Psbt.parse(psbt)).serialize(), "$type")
        }
    }
}
