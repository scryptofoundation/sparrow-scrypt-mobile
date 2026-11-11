package com.sparrowwallet.mobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.sparrowwallet.mobile.electrum.indexer.RuneBalance
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens a blob. The IV (12 bytes) travels in front of the ciphertext. */
interface StoreCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(blob: ByteArray): ByteArray
}

/** AES-256-GCM under a non-exportable Android Keystore key, the same scheme as [SyncStore]. */
class KeystoreCipher(private val alias: String) : StoreCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * An indexer's mark that a coin carries something, kept for an outpoint the last listing left out (see
 * [InscriptionChecker]): [runes] true for a runes mark (held whatever its next check says), false for an Inscribed result
 * an indexer gave (held unless the coin is found Inscribed again); [runeList] the indexer's balances; [since] when the
 * coin was first missing (milliseconds) and [missingRuns] how many finished runs have missed it since (added later,
 * defaulted, so older files load): together they bound how long the mark is kept. A mark is not a coin.
 */
@Serializable
data class KeptMark(val runes: Boolean, val runeList: Map<String, RuneBalance>? = null, val since: Long, val missingRuns: Int = 0)

/**
 * [blocks] (block hashes by height, deep enough not to change) and [marks] (indexer marks of outpoints the last listing
 * left out, by outpoint) were added in version 1 without a version bump: a file without them loads with none, and an
 * older reader (ignoreUnknownKeys) skips them.
 */
@Serializable
private data class InscriptionFile(val v: Int, val checks: Map<String, InscriptionCheck>, val blocks: Map<Int, String> = emptyMap(),
                                   val marks: Map<String, KeptMark> = emptyMap())

/**
 * One encrypted file per wallet holding its inscription checks, by outpoint. Holds only check results: no keys, no xpub.
 * A wallet whose file cannot be read (missing, undecryptable, unparseable, unknown version) counts as never checked.
 */
class InscriptionStore(private val dir: File, private val cipher: StoreCipher) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(walletName: String) = File(dir, safeFileName(walletName) + ".bin")

    fun load(walletName: String): Map<String, InscriptionCheck> = loadOrNull(walletName) ?: emptyMap()

    /**
     * The wallet's stored checks, possibly empty (a run that found no coins), or null when there is no readable file:
     * missing, undecryptable, unparseable, or an unknown version.
     */
    fun loadOrNull(walletName: String): Map<String, InscriptionCheck>? = loadFile(walletName)?.checks

    /** The block hashes kept with the wallet's checks, by height; empty when none or no readable file. */
    fun loadBlocks(walletName: String): Map<Int, String> = loadFile(walletName)?.blocks ?: emptyMap()

    /** The kept indexer marks of outpoints the last listing left out; empty when none or no readable file. */
    fun loadMarks(walletName: String): Map<String, KeptMark> = loadFile(walletName)?.marks ?: emptyMap()

    /** Everything in the wallet's file in one read, or null when there is no readable file (see [loadOrNull]). */
    fun loadAll(walletName: String): Triple<Map<String, InscriptionCheck>, Map<Int, String>, Map<String, KeptMark>>? =
        loadFile(walletName)?.let { Triple(it.checks, it.blocks, it.marks) }

    /** The checks and the block hashes in one read, or null when there is no readable file (see [loadOrNull]). */
    fun loadWithBlocks(walletName: String): Pair<Map<String, InscriptionCheck>, Map<Int, String>>? =
        loadFile(walletName)?.let { it.checks to it.blocks }

    private fun loadFile(walletName: String): InscriptionFile? {
        val file = fileFor(walletName)
        if(!file.exists()) return null
        return try {
            val parsed = json.decodeFromString(InscriptionFile.serializer(), cipher.open(file.readBytes()).decodeToString())
            if(parsed.v == VERSION) parsed else null
        } catch(e: Exception) {
            null
        }
    }

    /**
     * Replaces the wallet's file. Callers pass only unspent coins, which is how spent ones drop out. Each write goes
     * through its own temp file, so two writers (even through two stores) never mix; the last one wins. [blocks] are
     * block hashes by height the checker keeps with the checks, [marks] the kept indexer marks (see [InscriptionChecker]).
     */
    @Synchronized
    fun save(walletName: String, checks: Map<String, InscriptionCheck>, blocks: Map<Int, String> = emptyMap(),
             marks: Map<String, KeptMark> = emptyMap()) {
        dir.mkdirs()
        val sealed = cipher.seal(json.encodeToString(InscriptionFile.serializer(), InscriptionFile(VERSION, checks, blocks, marks)).encodeToByteArray())
        val target = fileFor(walletName)
        val temp = File.createTempFile(target.name + ".", ".tmp", dir)
        try {
            temp.writeBytes(sealed)
            try {
                java.nio.file.Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch(e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun delete(walletName: String) { fileFor(walletName).delete() }

    companion object {
        const val VERSION = 1
        const val KEY_ALIAS = "sparrow-inscriptions"
    }
}

/** Removes everything cached for a deleted wallet: its inscription checks and its background-sync credentials. */
internal fun deleteWalletCaches(walletName: String, inscriptions: InscriptionStore, syncCacheFile: File) {
    inscriptions.delete(walletName)
    syncCacheFile.delete()
}
