package com.sparrowwallet.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials for checking a wallet's balance WITHOUT its password: the account xpub
 * (public side) or the MWEB scan key (private side) — view-only material, never a seed
 * or spend key. Background sync can see incoming funds but can never spend.
 */
@Serializable
data class SyncCredentials(
    val name: String,
    val network: String,
    val scriptType: String,               // a script type name ("P2WPKH", "P2TR", ...) or "MWEB"
    val coin: String = "litecoin",        // saved before this field: Litecoin
    val xpub: String? = null,             // public wallets
    val scanSecretHex: String? = null,    // MWEB wallets
    val birthHeight: Int = 0,
    /**
     * True once a background run has recorded the baseline below. Kept separately because an empty wallet's
     * baseline is an empty list, which on its own looks like "no baseline yet" and hid its first payment.
     */
    val baselined: Boolean = false,
    /** Baseline for "what's new" detection. */
    val knownTxids: List<String> = emptyList(),
    val knownOutputIds: List<String> = emptyList(),
    /**
     * Set when a background scan saw a transaction the baseline lacked, cleared once the worker's inscription check has
     * run: so a check that was skipped or cut off still runs next time, after the baseline has moved on.
     */
    val inscriptionCheckDue: Boolean = false
)

/** Decides what a background run reports for one wallet, given its stored baseline. */
object SyncBaseline {
    /** Files saved before [SyncCredentials.baselined] existed count as baselined when they recorded any history. */
    private fun isBaselined(creds: SyncCredentials) =
        creds.baselined || creds.knownTxids.isNotEmpty() || creds.knownOutputIds.isNotEmpty()

    /** Sats newly received on the public side: positive deltas of transactions not in the baseline. */
    fun newlyReceived(creds: SyncCredentials, history: List<Pair<String, Long>>): Long {
        if(!isBaselined(creds)) return 0
        val known = creds.knownTxids.toSet()
        return history.filter { (txid, delta) -> txid !in known && delta > 0 }.sumOf { it.second }
    }

    /**
     * Whether the worker's inscription check of a public wallet is due after a scan that saw [historyTxids]: one is
     * not in the baseline (a new coin, or a spend whose change is new), or an earlier one never ran.
     */
    fun inscriptionCheckDue(creds: SyncCredentials, historyTxids: List<String>): Boolean {
        if(creds.inscriptionCheckDue) return true
        val known = creds.knownTxids.toSet()
        return historyTxids.any { it !in known }
    }

    /** Sats newly received on the MWEB side: unspent outputs not in the baseline. */
    fun newlyReceivedPrivate(creds: SyncCredentials, unspent: List<Pair<String, Long>>): Long {
        if(!isBaselined(creds)) return 0
        val known = creds.knownOutputIds.toSet()
        return unspent.filter { (outputId, _) -> outputId !in known }.sumOf { it.second }
    }
}

/**
 * Stores [SyncCredentials] encrypted under a hardware-backed Android Keystore key
 * (non-exportable, this-device-only), plus the background-refresh settings.
 * Populated on wallet unlock while background refresh is enabled.
 */
class SyncStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Minutes between background checks; 0 = off. Android's floor is 15. */
    var intervalMinutes: Int
        get() = prefs.getInt("interval", 0)
        set(value) { prefs.edit().putInt("interval", value).apply() }

    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify", true)
        set(value) { prefs.edit().putBoolean("notify", value).apply() }

    private fun dir() = File(context.filesDir, "synccache").apply { mkdirs() }
    private fun fileFor(name: String) = cacheFile(context.filesDir, name)

    fun save(creds: SyncCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(json.encodeToString(SyncCredentials.serializer(), creds).encodeToByteArray())
        fileFor(creds.name).writeBytes(cipher.iv + sealed)
    }

    fun load(name: String): SyncCredentials? = loadFile(fileFor(name))

    fun loadAll(): List<SyncCredentials> =
        dir().listFiles { f -> f.extension == "bin" }.orEmpty().mapNotNull { loadFile(it) }

    private fun loadFile(file: File): SyncCredentials? {
        if(!file.exists()) return null
        return try {
            val blob = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            json.decodeFromString(SyncCredentials.serializer(),
                cipher.doFinal(blob.copyOfRange(12, blob.size)).decodeToString())
        } catch(e: Exception) {
            null // key rotated or file damaged — treated as absent, re-cached on next unlock
        }
    }

    fun delete(name: String) { fileFor(name).delete() }

    fun clearAll() { dir().listFiles()?.forEach { it.delete() } }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "sparrow-sync-cache"

        /** The cache file for a wallet under the app's files dir. */
        fun cacheFile(filesDir: File, name: String) = File(File(filesDir, "synccache"), safeFileName(name) + ".bin")
    }
}

/** The file-name form of a wallet name, shared by the per-wallet stores so they all name a wallet's file alike. */
internal fun safeFileName(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_")
