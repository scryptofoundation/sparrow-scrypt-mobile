package com.sparrowwallet.mobile

import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.mobile.crypto.Base58
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Psbt
import com.sparrowwallet.mobile.crypto.toHex

/**
 * BC-UR (Blockchain Commons) codes for air-gapped signing, through Hummingbird, the
 * library desktop Sparrow uses: the wallet export the desktop scans to make a
 * watch-only copy, and PSBTs in both directions as animated (fountain-coded) QRs.
 * Pure JVM: no Android types, so it is unit tested directly.
 */
object AirgapUr {
    /** Max and min fragment bytes per QR frame; small enough for a phone or webcam to read. */
    private const val MAX_FRAGMENT = 250
    private const val MIN_FRAGMENT = 10
    private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte())

    /**
     * The account as `ur:crypto-account`: master fingerprint plus one wpkh output whose
     * key carries chain code, full origin and parent fingerprint, which is everything
     * desktop's QR import reads to rebuild the xpub. No coin info: desktop ignores it.
     */
    fun accountExport(master: HDKey, account: HDKey, path: List<Long>): UR {
        val components = path.map { IndexPathComponent((it and 0x7FFFFFFFL).toInt(), it >= 0x80000000L) }
        val key = CryptoHDKey(false, account.pubKey, account.chainCode, null,
            CryptoKeypath(components, master.fingerprint), null, account.parentFingerprint)
        val output = CryptoOutput(listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH), key)
        return CryptoAccount(master.fingerprint, listOf(output)).toUR()
    }

    fun psbtEncoder(psbt: ByteArray): UREncoder =
        UREncoder(CryptoPSBT(psbt).toUR(), MAX_FRAGMENT, MIN_FRAGMENT, 0)

    sealed interface Progress {
        data object Ignored : Progress
        data class Partial(val percent: Int) : Progress
        class Done(val psbt: ByteArray) : Progress
    }

    /**
     * Collects scanned frames into one PSBT. Takes `ur:crypto-psbt` and `ur:psbt` parts in
     * any order, or one static QR of base64 PSBT text. A frame from a different UR (another
     * type or part count) starts over with that one, so frames of two codes never mix.
     */
    class PsbtCollector {
        private var decoder = URDecoder()
        private var current: String? = null

        fun receive(text: String): Progress {
            val frame = text.trim()
            if(!frame.lowercase().startsWith("ur:")) {
                val bytes = runCatching { Psbt.fromBase64(frame) }.getOrNull()
                return if(bytes != null && bytes.size > PSBT_MAGIC.size &&
                    bytes.copyOfRange(0, PSBT_MAGIC.size).contentEquals(PSBT_MAGIC)) Progress.Done(bytes)
                else Progress.Ignored
            }
            val identity = identity(frame) ?: return Progress.Ignored
            if(current != null && current != identity) decoder = URDecoder()
            current = identity
            if(!decoder.receivePart(frame)) return Progress.Ignored
            val result = decoder.result
                ?: return Progress.Partial((decoder.estimatedPercentComplete * 100).toInt().coerceIn(0, 99))
            reset()
            if(result.type != ResultType.SUCCESS) return Progress.Ignored
            val item = runCatching { result.ur.decodeFromRegistry() }.getOrNull()
            return if(item is CryptoPSBT) Progress.Done(item.psbt) else Progress.Ignored
        }

        private fun reset() {
            decoder = URDecoder()
            current = null
        }

        /** "type/seqLen" from ur:type/seq-len/fragment, or "type/1" for a single-part UR. */
        private fun identity(frame: String): String? {
            val parts = frame.lowercase().removePrefix("ur:").split("/")
            return when(parts.size) {
                2 -> "${parts[0]}/1"
                3 -> parts[1].substringAfter("-", "").takeIf { it.isNotEmpty() }?.let { "${parts[0]}/$it" }
                else -> null
            }
        }
    }

    /**
     * What desktop Sparrow's QR import (QRScanDialog.getExtendedKey) rebuilds from our
     * export: depth from the origin length, child number from its last component, parent
     * fingerprint from the key (zeros if absent). Returns (master fingerprint hex, xpub).
     */
    internal fun desktopXpub(ur: UR, xpubHeader: Int): Pair<String, String> {
        val account = ur.decodeFromRegistry() as CryptoAccount
        val key = account.outputDescriptors.first().hdKey
        val components = key.origin.components
        val last = components.last() as IndexPathComponent
        val child = last.index.toLong() + if(last.isHardened) 0x80000000L else 0L
        val parent = key.parentFingerprint ?: ByteArray(4)
        val payload = ByteArray(4) { (xpubHeader ushr (24 - 8 * it)).toByte() } +
            byteArrayOf(components.size.toByte()) + parent +
            ByteArray(4) { (child ushr (24 - 8 * it)).toByte() } + key.chainCode + key.key
        return account.masterFingerprint.toHex() to Base58.encodeChecked(payload)
    }
}
