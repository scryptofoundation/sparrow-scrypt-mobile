package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The external-signer path must be indistinguishable from direct signing: an unsigned
 * PSBT signed via [Psbt.sign] and finalized has to produce the byte-identical network
 * transaction that [TxBuilder.build] signs directly (RFC6979 makes both deterministic),
 * and the txid must be the same before and after signing.
 */
class PsbtTest {
    private val hardened = 0x80000000L
    private val master = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
    private val account = master.derivePath("m/84'/2'/0'")
    private val key0 = account.deriveChild(0).deriveChild(0)
    private val key1 = account.deriveChild(0).deriveChild(1)
    private val changeKey = account.deriveChild(1).deriveChild(0)
    private val destKey = account.deriveChild(0).deriveChild(9)

    private val inputs = listOf(
        SpendableInput("aa".repeat(32), 0, 50_000_000, key0),
        SpendableInput("bb".repeat(32), 1, 30_000_000, key1)
    )
    private val destScript = Scripts.p2wpkhOutput(destKey.pubKey)
    private val changeScript = Scripts.p2wpkhOutput(changeKey.pubKey)

    private fun accountPath(chain: Long, index: Long) =
        listOf(84L + hardened, 2L + hardened, 0L + hardened, chain, index)

    private fun createPsbt(unsigned: UnsignedBuild): ByteArray = Psbt.create(
        unsigned,
        masterFingerprint = master.fingerprint,
        pathFor = { input ->
            when(input.key) {
                key0 -> accountPath(0, 0)
                key1 -> accountPath(0, 1)
                else -> error("unexpected input key")
            }
        },
        changeIndex = 1,
        changePubKey = changeKey.pubKey,
        changePath = accountPath(1, 0)
    )

    @Test
    fun psbtPathMatchesDirectSigningExactly() {
        val direct = TxBuilder.build(inputs, destScript, 60_000_000, 2, changeScript)
        val unsigned = TxBuilder.buildUnsigned(inputs, destScript, 60_000_000, 2, changeScript)
        val psbt = createPsbt(unsigned)

        val parsed = Psbt.parse(psbt)
        assertContentEquals(unsigned.tx.serialize(false), parsed.unsignedTxBytes)
        assertEquals(2, parsed.inputs.size)
        assertEquals(50_000_000, parsed.inputs[0].witnessUtxoValue)

        // "external signer": a fresh parse signs with keys resolved only by pubkey
        val keysByPub = mapOf(key0.pubKey.toHex() to key0, key1.pubKey.toHex() to key1)
        val signed = Psbt.sign(parsed, keyFor = { keysByPub[it] })
        val finalTx = Psbt.finalize(Psbt.parse(signed))

        assertContentEquals(direct.tx.serialize(), finalTx.serialize())
        assertEquals(direct.tx.txid(), finalTx.txid())
        // P2WPKH txid excludes witnesses: the unsigned preview txid already matches
        assertEquals(unsigned.tx.txid(), finalTx.txid())
    }

    @Test
    fun finalizeRejectsUnsignedPsbt() {
        val unsigned = TxBuilder.buildUnsigned(inputs, destScript, 60_000_000, 2, changeScript)
        val parsed = Psbt.parse(createPsbt(unsigned))
        assertFailsWith<IllegalArgumentException> { Psbt.finalize(parsed) }
    }

    @Test
    fun base64RoundTrip() {
        val unsigned = TxBuilder.buildUnsigned(inputs, destScript, 60_000_000, 2, changeScript)
        val psbt = createPsbt(unsigned)
        assertContentEquals(psbt, Psbt.fromBase64(Psbt.toBase64(psbt)))
        assertFailsWith<IllegalArgumentException> { Psbt.fromBase64("not base64 !!!") }
    }

    // ---- desktop-built PSBTs: fields this phone does not use must survive untouched ----

    private val keysByPub = mapOf(key0.pubKey.toHex() to key0, key1.pubKey.toHex() to key1)

    /** A previous transaction whose output 0 pays key0 (what input 0 spends). */
    private fun prevTx(value: Long = 50_000_000, scriptSig: ByteArray = ByteArray(0)) = Tx(
        2,
        listOf(TxInput("cc".repeat(32), 3, 0xFFFFFFFFL, scriptSig)),
        listOf(TxOutput(value, Scripts.p2wpkhOutput(key0.pubKey)), TxOutput(1_000, destScript))
    )

    private fun splitMaps(psbt: ByteArray): List<MutableList<Pair<ByteArray, ByteArray>>> {
        val r = ByteReader(psbt)
        r.bytes(5)
        val maps = ArrayList<MutableList<Pair<ByteArray, ByteArray>>>()
        while(r.remaining() > 0) {
            val map = ArrayList<Pair<ByteArray, ByteArray>>()
            while(true) {
                val keyLen = r.varInt()
                if(keyLen == 0L) break
                map += r.bytes(keyLen.toInt()) to r.varBytes()
            }
            maps += map
        }
        return maps
    }

    private fun joinMaps(maps: List<List<Pair<ByteArray, ByteArray>>>): ByteArray {
        val w = ByteWriter().bytes(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte()))
        for(map in maps) {
            for((k, v) in map) w.varBytes(k).varBytes(v)
            w.u8(0)
        }
        return w.toByteArray()
    }

    /**
     * What desktop Sparrow sends: a global xpub, a proprietary global pair, input 0's
     * full previous transaction, and a proprietary output pair, on top of our fields.
     */
    private fun desktopStylePsbt(prevRaw: ByteArray = prevTx().serialize(), prevTxid: String = prevTx().txid()): ByteArray {
        val spend = listOf(
            SpendableInput(prevTxid, 0, 50_000_000, key0),
            SpendableInput("bb".repeat(32), 1, 30_000_000, key1)
        )
        val maps = splitMaps(createPsbt(TxBuilder.buildUnsigned(spend, destScript, 60_000_000, 2, changeScript)))
        maps[0] += (byteArrayOf(0x01) + ByteArray(78) { 7 }) to (master.fingerprint + ByteArray(12))
        maps[0] += (byteArrayOf(0xFC.toByte(), 0x04) + "test".encodeToByteArray() + byteArrayOf(0x01)) to byteArrayOf(9, 9)
        maps[1].add(0, byteArrayOf(0x00) to prevRaw)
        maps[4] += (byteArrayOf(0xFC.toByte(), 0x04) + "test".encodeToByteArray() + byteArrayOf(0x02)) to byteArrayOf(5)
        return joinMaps(maps)
    }

    @Test
    fun unknownPairsRoundTripVerbatim() {
        val p = desktopStylePsbt()
        assertContentEquals(p, Psbt.serialize(Psbt.parse(p)))
        val parsed = Psbt.parse(p)
        assertEquals(2, parsed.globalUnknown.size)
        assertEquals(1, parsed.outputs[1].unknown.size)
        assertContentEquals(prevTx().serialize(), parsed.inputs[0].nonWitnessUtxo)
    }

    @Test
    fun outputDerivationsSurviveSigning() {
        val signed = Psbt.parse(Psbt.sign(Psbt.parse(desktopStylePsbt())) { keysByPub[it] })
        assertEquals(accountPath(1, 0), signed.outputs[1].derivations[changeKey.pubKey.toHex()]!!.second)
    }

    @Test
    fun signingOnlyAddsPartialSigs() {
        val unsigned = desktopStylePsbt()
        val signed = Psbt.sign(Psbt.parse(unsigned)) { keysByPub[it] }
        val before = splitMaps(unsigned)
        val after = splitMaps(signed)
        assertEquals(before.size, after.size)
        for(m in before.indices) {
            val isInput = m in 1..2
            val kept = after[m].filter { !(isInput && it.first[0].toInt() == 0x02) }
            assertEquals(before[m].map { it.first.toHex() + ":" + it.second.toHex() },
                kept.map { it.first.toHex() + ":" + it.second.toHex() }, "map $m")
            assertEquals(if(isInput) 1 else 0, after[m].size - kept.size, "map $m signatures")
        }
    }

    @Test
    fun mismatchedNonWitnessUtxoIsRefused() {
        val tampered = prevTx(value = 49_000_000)
        val e = assertFailsWith<IllegalArgumentException> {
            Psbt.parse(desktopStylePsbt(tampered.serialize(), tampered.txid()))
        }
        assertEquals("Input 1: non-witness UTXO does not match the input", e.message)
    }

    @Test
    fun nonWitnessUtxoWithWrongTxidIsRefused() {
        val e = assertFailsWith<IllegalArgumentException> {
            Psbt.parse(desktopStylePsbt(prevTx().serialize(), "dd".repeat(32)))
        }
        assertEquals("Input 1: non-witness UTXO does not match the input", e.message)
    }

    @Test
    fun nonWitnessUtxoWithLegacyScriptSigIsAccepted() {
        val legacy = prevTx(scriptSig = ByteArray(107) { (it * 3).toByte() })
        val parsed = Psbt.parse(desktopStylePsbt(legacy.serialize(), legacy.txid()))
        assertContentEquals(legacy.serialize(), parsed.inputs[0].nonWitnessUtxo)
    }

    @Test
    fun nonWitnessUtxoWithMwebFlagIsAccepted() {
        val prev = prevTx()
        prev.inputs[0].witness = listOf(ByteArray(71) { 1 }, key1.pubKey)
        prev.mwebExtension = ByteArray(40) { 4 }
        val raw = prev.serialize()
        assertEquals(0x09, raw[5].toInt()) // segwit marker, then flags witness | MWEB
        val parsedPrev = RawTx.parse(raw)
        assertEquals(prev.txid(), parsedPrev.txid)
        assertEquals(50_000_000, parsedPrev.outputs[0].value)
        Psbt.parse(desktopStylePsbt(raw, prev.txid()))
    }

    @Test
    fun parseRejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { Psbt.parse(ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { Psbt.parse("70736274aa".hexToBytes()) }
    }
}
