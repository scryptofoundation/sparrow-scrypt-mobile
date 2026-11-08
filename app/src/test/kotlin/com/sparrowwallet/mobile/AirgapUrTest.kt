package com.sparrowwallet.mobile

import kotlin.test.assertFailsWith
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.URPSBT
import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.Psbt
import com.sparrowwallet.mobile.crypto.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirgapUrTest {
    private val hardened = 0x80000000L
    private val master = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
    private val path = listOf(84L + hardened, 1L + hardened, 0L + hardened)
    private val account = master.derivePath("m/84'/1'/0'")
    private val magic = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte())

    private fun fakePsbt(size: Int, seed: Int) = magic + Random(seed).nextBytes(size)

    @Test fun accountExportDecodesToTheSameXpubDesktopWould() {
        val ur = AirgapUr.accountExport(master, account, path, ScriptType.P2WPKH)
        assertEquals("crypto-account", ur.type)
        assertEquals(master.fingerprint.toHex() to account.xpub(Network.TESTNET.xpubHeader),
            AirgapUr.desktopXpub(ur, Network.TESTNET.xpubHeader))
    }

    @Test fun accountExportCarriesOriginAndNoUseInfo() {
        val account = AirgapUr.accountExport(master, this.account, path, ScriptType.P2WPKH).decodeFromRegistry() as CryptoAccount
        assertContentEquals(master.fingerprint, account.masterFingerprint)
        val output = account.outputDescriptors.single()
        assertEquals(listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH), output.scriptExpressions)
        val key = output.hdKey
        assertNull(key.useInfo)
        assertEquals("84'/1'/0'", key.origin.path)
        assertContentEquals(master.fingerprint, key.origin.sourceFingerprint)
        assertContentEquals(this.account.parentFingerprint, key.parentFingerprint)
    }

    @Test fun psbtRoundTripsThroughManyPartsWithLossAndRepeats() {
        val psbt = fakePsbt(4_000, 7)
        val enc = AirgapUr.psbtEncoder(psbt)
        val col = AirgapUr.PsbtCollector()
        var result: ByteArray? = null
        var n = 0
        while(result == null && n++ < 500) {
            val part = enc.nextPart()
            if(n % 3 == 0) continue // dropped frame
            val progress = col.receive(part)
            if(n % 5 == 0) col.receive(part) // repeated frame
            (progress as? AirgapUr.Progress.Done)?.let { result = it.psbt }
        }
        assertContentEquals(psbt, result)
    }

    @Test fun partialProgressIsReported() {
        val enc = AirgapUr.psbtEncoder(fakePsbt(2_000, 3))
        val progress = AirgapUr.PsbtCollector().receive(enc.nextPart())
        assertIs<AirgapUr.Progress.Partial>(progress)
        assertTrue(progress.percent in 0..99)
    }

    @Test fun collectorAcceptsPlainBase64() {
        val psbt = fakePsbt(300, 1)
        val done = AirgapUr.PsbtCollector().receive(Psbt.toBase64(psbt))
        assertContentEquals(psbt, assertIs<AirgapUr.Progress.Done>(done).psbt)
    }

    @Test fun collectorAcceptsUrPsbtType() {
        val psbt = fakePsbt(100, 2)
        val single = com.sparrowwallet.hummingbird.UREncoder.encode(URPSBT(psbt).toUR())
        assertTrue(single.startsWith("ur:psbt/"))
        assertContentEquals(psbt, assertIs<AirgapUr.Progress.Done>(AirgapUr.PsbtCollector().receive(single)).psbt)
    }

    @Test fun collectorIgnoresForeignFrames() {
        val a = fakePsbt(3_000, 11)
        val b = fakePsbt(1_500, 12)
        val encA = AirgapUr.psbtEncoder(a)
        val encB = AirgapUr.psbtEncoder(b)
        val col = AirgapUr.PsbtCollector()
        assertEquals(AirgapUr.Progress.Ignored, col.receive("tltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertEquals(AirgapUr.Progress.Ignored, col.receive("hello"))
        // a scanner swinging between two screens never completes a mix of the two
        repeat(40) {
            assertTrue(col.receive(encA.nextPart()) !is AirgapUr.Progress.Done)
            assertTrue(col.receive(encB.nextPart()) !is AirgapUr.Progress.Done)
        }
        // then settles on B
        var result: ByteArray? = null
        var n = 0
        while(result == null && n++ < 500) {
            (col.receive(encB.nextPart()) as? AirgapUr.Progress.Done)?.let { result = it.psbt }
        }
        assertContentEquals(b, result)
    }

    @Test fun completedNonPsbtUrIsIgnored() {
        val single = com.sparrowwallet.hummingbird.UREncoder.encode(AirgapUr.accountExport(master, account, path, ScriptType.P2WPKH))
        val col = AirgapUr.PsbtCollector()
        assertEquals(AirgapUr.Progress.Ignored, col.receive(single))
        // and the collector still works afterwards
        val psbt = fakePsbt(50, 5)
        val next = com.sparrowwallet.hummingbird.UREncoder.encode(URPSBT(psbt).toUR())
        assertContentEquals(psbt, assertIs<AirgapUr.Progress.Done>(col.receive(next)).psbt)
    }

    @Test fun accountExportFollowsTheScriptType() {
        fun export(coin: Coin, type: ScriptType): Pair<List<ScriptExpression>, Pair<String, String>> {
            val params = ChainParams.of(coin, Network.MAINNET)
            val path = listOf(type.purpose.toLong() + hardened, coin.coinType.toLong() + hardened, hardened)
            var acct = master
            for(child in path) acct = acct.deriveChild(child)
            val ur = AirgapUr.accountExport(master, acct, path, type)
            val expressions = (ur.decodeFromRegistry() as CryptoAccount).outputDescriptors.single().scriptExpressions
            assertEquals(master.fingerprint.toHex() to acct.xpub(params.xpubHeader), AirgapUr.desktopXpub(ur, params.xpubHeader))
            return expressions to AirgapUr.desktopXpub(ur, params.xpubHeader)
        }
        assertEquals(listOf(ScriptExpression.PUBLIC_KEY_HASH), export(Coin.DOGECOIN, ScriptType.P2PKH).first)
        assertEquals(listOf(ScriptExpression.SCRIPT_HASH, ScriptExpression.WITNESS_PUBLIC_KEY_HASH),
            export(Coin.LITECOIN, ScriptType.P2SH_P2WPKH).first)
        assertEquals(listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH), export(Coin.LITECOIN, ScriptType.P2WPKH).first)
        //desktop's QR import reads tr() as a Taproot wallet
        assertEquals(listOf(ScriptExpression.TAPROOT), export(Coin.LITECOIN, ScriptType.P2TR).first)
        assertTrue(export(Coin.DOGECOIN, ScriptType.P2PKH).second.second.startsWith("dgub"))
    }

    @Test fun unsignedPsbtTooBigForOneQrIsShownAsFrames() {
        // a Legacy PSBT carries each input's previous transaction: a few of them pass one QR's limit
        val psbt = ByteArray(6000) { (it * 31 + 7).toByte() }
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        assertFailsWith<com.google.zxing.WriterException> {
            writer.encode(java.util.Base64.getEncoder().encodeToString(psbt), com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        }
        val encoder = AirgapUr.psbtEncoder(psbt)
        assertTrue(encoder.seqLen > 1)
        repeat(encoder.seqLen) { writer.encode(encoder.nextPart().uppercase(), com.google.zxing.BarcodeFormat.QR_CODE, 512, 512) }
    }
}
