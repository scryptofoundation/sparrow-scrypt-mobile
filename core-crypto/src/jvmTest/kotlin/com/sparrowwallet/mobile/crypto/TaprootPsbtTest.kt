package com.sparrowwallet.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Taproot (BIP86 key path) PSBTs: the phone signs a desktop-made PSBT to the transaction drongo makes
 * (vectors/taproot-psbt.json, from tools/vectorgen), and its own PSBTs sign to what direct signing makes.
 */
class TaprootPsbtTest {
    private val h = PsbtFixtures.HARDENED
    private val master = PsbtFixtures.master

    private object Vector {
        private val root = Json.parseToJsonElement(java.io.File("../vectors/taproot-psbt.json").readText()).jsonObject
        private fun str(name: String) = root[name]!!.jsonPrimitive.content
        val derivationPath = str("derivationPath")
        val masterFingerprint = str("masterFingerprint")
        val fee = root["fee"]!!.jsonPrimitive.long
        val unsigned = Psbt.fromBase64(str("unsignedPsbt"))
        val signed = Psbt.fromBase64(str("signedPsbt"))
        val txHex = str("txHex")
    }

    private val testnet = ChainParams.of(Coin.LITECOIN, Network.TESTNET)
    private val testnetPath = listOf(86L + h, 1L + h, 0L + h)

    private val mainnet = ChainParams.of(Coin.LITECOIN, Network.MAINNET)
    private val mainnetPath = listOf(86L + h, 2L + h, 0L + h)
    private val account = master.derivePath("m/86'/2'/0'")
    private val keys = listOf(account.deriveChild(0).deriveChild(0), account.deriveChild(0).deriveChild(1))
    private val changeKey = account.deriveChild(1).deriveChild(0)
    private val destScript = Scripts.p2trOutput(account.deriveChild(0).deriveChild(9).pubKey)
    private val changeScript = Scripts.p2trOutput(changeKey.pubKey)

    private val inputs = listOf(
        SpendableInput("aa".repeat(32), 0, 50_000_000, keys[0], ScriptType.P2TR),
        SpendableInput("bb".repeat(32), 1, 30_000_000, keys[1], ScriptType.P2TR)
    )

    /** dest 0.6 LTC + change from both P2TR coins, witness UTXOs only, as the phone creates it. */
    private fun phonePsbt(): ByteArray = Psbt.create(
        TxBuilder.buildUnsigned(inputs, destScript, 60_000_000, 2, changeScript, params = mainnet),
        master.fingerprint,
        pathFor = { input -> mainnetPath + listOf(0L, keys.indexOfFirst { it.pubKey.contentEquals(input.key.pubKey) }.toLong()) },
        changeIndex = 1, changePubKey = changeKey.pubKey, changePath = mainnetPath + listOf(1L, 0L)
    )

    private fun sign(psbt: ByteArray): Psbt.Parsed =
        Psbt.parse(PsbtSigner.sign(Psbt.parse(psbt), master, mainnet, mainnetPath, ScriptType.P2TR, chainTip = null))

    private fun review(psbt: ByteArray): PsbtReview =
        PsbtSigner.review(Psbt.parse(psbt), master, mainnet, mainnetPath, ScriptType.P2TR)

    @Test
    fun signsDesktopTaprootPsbtToTheSameBytes() {
        assertEquals("m/86'/1'/0'", Vector.derivationPath)
        assertEquals(Vector.masterFingerprint, master.fingerprint.toHex())
        val parsed = Psbt.parse(Vector.unsigned)
        val review = PsbtSigner.review(parsed, master, testnet, testnetPath, ScriptType.P2TR)
        assertNull(review.refusal)
        assertTrue(review.inputs.all { it.ours })
        assertEquals(listOf(false, true), review.outputs.map { it.change })
        assertEquals(Vector.fee, review.fee)

        val signed = Psbt.parse(PsbtSigner.sign(parsed, master, testnet, testnetPath, ScriptType.P2TR, chainTip = null))
        val desktop = Psbt.parse(Vector.signed)
        for((i, input) in signed.inputs.withIndex()) {
            assertEquals(64, input.tapKeySig!!.size)
            assertContentEquals(desktop.inputs[i].tapKeySig, input.tapKeySig, "input $i")
        }
        assertEquals(Vector.txHex, Psbt.finalize(signed).serialize().toHex())
    }

    @Test
    fun phoneMadeTaprootPsbtRoundTrips() {
        val psbt = phonePsbt()
        val maps = PsbtFixtures.splitMaps(psbt)
        for(i in 1..2) assertEquals(listOf(0x01, 0x16, 0x17), maps[i].map { it.first[0].toInt() })
        assertEquals(listOf(0x07, 0x05), maps[4].map { it.first[0].toInt() })
        assertContentEquals(byteArrayOf(0x16) + Taproot.xOnly(keys[0].pubKey), maps[1][1].first)
        assertContentEquals(byteArrayOf(0) + PsbtFixtures.derivationValue(master.fingerprint, mainnetPath + listOf(0L, 0L)), maps[1][1].second)
        assertContentEquals(Taproot.xOnly(keys[0].pubKey), maps[1][2].second)
        assertEquals(listOf(false, true), review(psbt).outputs.map { it.change })

        val direct = TxBuilder.build(inputs, destScript, 60_000_000, 2, changeScript, params = mainnet, chainTip = null).tx
        assertContentEquals(direct.serialize(), Psbt.finalize(sign(psbt)).serialize())
    }

    @Test
    fun taprootWalletSkipsPrevTxRule() {
        val psbt = phonePsbt()
        val parsed = Psbt.parse(psbt)
        assertEquals(2, parsed.inputs.size)
        assertTrue(parsed.inputs.all { it.nonWitnessUtxo == null && it.witnessUtxoScript != null })
        assertNull(review(psbt).refusal)
        assertTrue(sign(psbt).inputs.all { it.tapKeySig?.size == 64 })
    }

    @Test
    fun taprootSigningNeedsEveryInputsSpentOutput() {
        //input 2 belongs to another signer and says nothing about the coin it spends
        val psbt = PsbtFixtures.edit(phonePsbt()) { maps -> maps[2].clear() }
        val e = assertFailsWith<IllegalArgumentException> { sign(psbt) }
        assertEquals("Input 2 has no witness UTXO or previous transaction", e.message)
        assertEquals("Input 2 has no witness UTXO or previous transaction", review(psbt).refusal)
    }

    /** A witness UTXO value for [value] paid to [script]. */
    private fun witnessUtxo(value: Long, script: ByteArray) = ByteWriter().u64le(value).varBytes(script).toByteArray()

    @Test
    fun taprootWalletSignsBesideForeignV0InputWithoutPrevTx() {
        //input 2 is another signer's Native Segwit coin, with its witness UTXO and no previous transaction
        val foreign = Scripts.p2wpkhOutput(master.derivePath("m/84'/2'/5'/0/0").pubKey)
        val foreignMap = listOf(byteArrayOf(0x01) to witnessUtxo(30_000_000, foreign))
        val psbt = PsbtFixtures.edit(phonePsbt()) { maps -> maps[2].clear(); maps[2] += foreignMap }
        val review = review(psbt)
        assertNull(review.refusal)
        assertEquals(listOf(true, false), review.inputs.map { it.ours })

        val signed = sign(psbt)
        assertEquals(64, signed.inputs[0].tapKeySig!!.size)
        assertNull(signed.inputs[1].tapKeySig)
        assertTrue(signed.inputs[1].partialSigs.isEmpty())
        //the foreign input's map passes through as it was
        val maps = PsbtFixtures.splitMaps(Psbt.serialize(signed))
        assertEquals(foreignMap.map { it.first.toHex() to it.second.toHex() }, maps[2].map { it.first.toHex() to it.second.toHex() })
    }

    @Test
    fun v0WalletKeepsPrevTxRuleBesideForeignTaprootInput() {
        //our Native Segwit input carries its previous transaction; input 2 is another signer's Taproot coin with a witness UTXO only
        val foreign = Scripts.p2trOutput(master.derivePath("m/86'/2'/5'/0/0").pubKey)
        val psbt = PsbtFixtures.edit(PsbtFixtures.fixturePsbt()) { maps ->
            maps[2].clear()
            maps[2] += byteArrayOf(0x01) to witnessUtxo(30_000_000, foreign)
        }
        val parsed = Psbt.parse(psbt)
        val accountPath = listOf(84L + h, 2L + h, 0L + h)
        val expected = "Input 2 is missing the full previous transaction. When a PSBT has more than one input, the " +
            "phone needs each one to check the fee. On the PC, import this wallet with the Sparrow Mobile option."
        val e = assertFailsWith<IllegalArgumentException> { PsbtSigner.sign(parsed, master, mainnet, accountPath, ScriptType.P2WPKH, chainTip = null) }
        assertEquals(expected, e.message)
        assertEquals(expected, PsbtSigner.review(parsed, master, mainnet, accountPath, ScriptType.P2WPKH).refusal)
    }

    @Test
    fun otherSighashTypeIsRefused() {
        val psbt = PsbtFixtures.edit(phonePsbt()) { maps -> maps[1] += byteArrayOf(0x03) to byteArrayOf(0x83.toByte(), 0, 0, 0) }
        assertEquals(0x83, Psbt.parse(psbt).inputs[0].sighashType)
        val e = assertFailsWith<IllegalArgumentException> { sign(psbt) }
        assertTrue(e.message!!.startsWith("Input 1 "), e.message)
        assertEquals(e.message, review(psbt).refusal)
    }

    @Test
    fun sighashAllSignsWithTheTypeByte() {
        val psbt = PsbtFixtures.edit(phonePsbt()) { maps -> maps[1] += byteArrayOf(0x03) to byteArrayOf(0x01, 0, 0, 0) }
        val signed = sign(psbt)
        val sig = signed.inputs[0].tapKeySig!!
        assertEquals(65, sig.size)
        assertEquals(0x01, sig[64].toInt())
        val scripts = signed.inputs.map { it.witnessUtxoScript!! }
        val amounts = signed.inputs.map { it.witnessUtxoValue!! }
        val hash = signed.tx.taprootKeyPathSighash(0, amounts, scripts, 1)
        assertTrue(secpVerifySchnorr(sig.copyOfRange(0, 64), hash, Taproot.outputKey(keys[0].pubKey)))
        assertEquals(64, signed.inputs[1].tapKeySig!!.size)
        assertEquals(listOf(sig), Psbt.finalize(signed).inputs[0].witness)
    }

    @Test
    fun scriptPathInputIsLeftUnsigned() {
        //input 2 is a script-path spend of another signer's: a leaf script and no key-path derivation of ours
        val leafKey = byteArrayOf(0x15, 0xC0.toByte()) + ByteArray(32) { 5 }
        val leafValue = byteArrayOf(0x20) + ByteArray(32) { 6 } + byteArrayOf(0xAC.toByte(), 0xC0.toByte())
        val psbt = PsbtFixtures.edit(phonePsbt()) { maps ->
            maps[2].removeAll { it.first[0].toInt() == 0x16 || it.first[0].toInt() == 0x17 }
            maps[2] += leafKey to leafValue
        }
        val parsed = Psbt.parse(psbt)
        assertTrue(parsed.inputs[1].hasLeafScripts)
        val review = review(psbt)
        assertNull(review.refusal)
        assertEquals(listOf(true, false), review.inputs.map { it.ours })
        assertNotNull(review.warning)
        assertTrue("Script-path Taproot spends are not supported on the phone yet" in review.warning!!, review.warning)

        val signed = sign(psbt)
        assertEquals(64, signed.inputs[0].tapKeySig!!.size)
        assertNull(signed.inputs[1].tapKeySig)
    }

    @Test
    fun desktopFinalizedTaprootInputIsKept() {
        //the desktop already finalized input 1: its witness is used as it is, input 2 is finalized from its key signature
        val desktop = Psbt.parse(Vector.signed)
        val witness = ByteWriter().varInt(1).varBytes(desktop.inputs[0].tapKeySig!!).toByteArray()
        val psbt = PsbtFixtures.edit(Vector.signed) { maps ->
            maps[1].removeAll { it.first[0].toInt() == 0x13 }
            maps[1] += byteArrayOf(0x08) to witness
        }
        val parsed = Psbt.parse(psbt)
        assertNull(parsed.inputs[0].tapKeySig)
        assertEquals(Vector.txHex, Psbt.finalize(parsed).serialize().toHex())
    }
}
