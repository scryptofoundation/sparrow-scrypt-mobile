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
 * [blocks] (block hashes by height, deep enough not to change), [marks] (indexer marks of outpoints the last listing
 * left out, by outpoint), [asked] (when a holds-only indexer last answered "unknown" for a coin, by outpoint) with
 * [askedFor] (which indexer those answers came from), [lost] (the wallet's earlier file could not be read and was set
 * aside; no run has finished since) with [lostCoins] (the coins that loss still leaves unresolved), and [lastUnreachable],
 * [lastPartial] and [lastRunAt] (whether the last finished run could not reach its indexer or got only some answers, and
 * when it ended) were added in version 1 without a version bump: a file without them loads with none, and an older
 * reader (ignoreUnknownKeys) skips them.
 */
@Serializable
private data class InscriptionFile(val v: Int, val checks: Map<String, InscriptionCheck>, val blocks: Map<Int, String> = emptyMap(),
                                   val marks: Map<String, KeptMark> = emptyMap(), val asked: Map<String, Long> = emptyMap(),
                                   val lost: Boolean = false, val lastUnreachable: Boolean? = null, val lastRunAt: Long? = null,
                                   val lostCoins: Set<String> = emptySet(), val askedFor: String? = null, val lastPartial: Boolean? = null)

/** Everything a wallet's results file holds (see [InscriptionFile] for the optional parts). */
class StoredFile(
    val checks: Map<String, InscriptionCheck>,
    val blocks: Map<Int, String> = emptyMap(),
    val marks: Map<String, KeptMark> = emptyMap(),
    val asked: Map<String, Long> = emptyMap(),
    val lost: Boolean = false,
    val lastUnreachable: Boolean? = null,
    val lastRunAt: Long? = null,
    val lostCoins: Set<String> = emptySet(),
    val askedFor: String? = null,
    val lastPartial: Boolean? = null
)

/**
 * A load of a wallet's results: [file], or null when there is none; [unavailable] when the file is there but could not be
 * read just now (an IO or Keystore error): nothing was moved or written, and a later load tries again.
 */
class LoadResult(val file: StoredFile?, val unavailable: Boolean = false)

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
    @androidx.annotation.VisibleForTesting
    internal fun loadMarks(walletName: String): Map<String, KeptMark> = loadFile(walletName)?.marks ?: emptyMap()

    /** Everything in the wallet's file in one read, or null when there is no readable file (see [loadOrNull]). */
    fun loadAll(walletName: String): StoredFile? = loadFile(walletName)?.stored()

    /**
     * Everything in the wallet's file in one read (see [LoadResult]). A file that is there but corrupt (it fails its
     * authentication, which a key permanently gone also gives; it does not parse; or its version is unknown) is not taken
     * for "never checked", which would let Send fall back to the old local rule and lose the indexer marks it held: it is
     * set aside as `<name>.bin.unreadable` and a readable file with no rows and [StoredFile.lost] set takes its place, so
     * the wallet counts as checked and every coin is Unchecked until resolved. The first set-aside copy is kept: a later
     * corrupt file is dropped rather than written over it (the marks went with the first loss). The replacement is sealed
     * before anything is touched and put in place atomically, so the wallet always has one file or the other. A read
     * error (IO, a Keystore that cannot be used just now) touches nothing: the result is unavailable.
     */
    @Synchronized
    fun loadOrSetAside(walletName: String): LoadResult {
        val read = readFile(walletName)
        return when(read) {
            Read.Missing -> LoadResult(null)
            Read.Error -> LoadResult(null, unavailable = true)
            is Read.Ok -> LoadResult(read.file.stored())
            Read.Corrupt -> {
                val replacement = StoredFile(emptyMap(), lost = true)
                val sealed = try {
                    seal(replacement)
                } catch(e: Exception) {
                    //The key cannot seal: nothing is moved, the file stays as it is, and a later load tries again
                    return LoadResult(null, unavailable = true)
                }
                val file = fileFor(walletName)
                val aside = File(dir, file.name + UNREADABLE_SUFFIX)
                try {
                    if(!aside.exists()) file.copyTo(aside)
                    writeSealed(file, sealed)
                } catch(e: Exception) {
                    return LoadResult(null, unavailable = true)
                }
                LoadResult(replacement)
            }
        }
    }

    private fun InscriptionFile.stored() =
        StoredFile(checks, blocks, marks, asked, lost, lastUnreachable, lastRunAt, lostCoins, askedFor, lastPartial)

    /** What a read of the wallet's file found. */
    private sealed interface Read {
        object Missing : Read
        object Corrupt : Read
        object Error : Read
        class Ok(val file: InscriptionFile) : Read
    }

    private fun loadFile(walletName: String): InscriptionFile? = (readFile(walletName) as? Read.Ok)?.file

    /**
     * Reads the wallet's file. Corrupt: shorter than an IV and a tag, failing its authentication (GCM tag; a key that is
     * permanently gone reads so too), not parsing, or of an unknown version. An error: the file could not be read, or the
     * Keystore failed in any other way (it may work on the next try).
     */
    private fun readFile(walletName: String): Read {
        val file = fileFor(walletName)
        if(!file.exists()) return Read.Missing
        val bytes = try {
            file.readBytes()
        } catch(e: Exception) {
            return Read.Error
        }
        if(bytes.size < MIN_SEALED_BYTES) return Read.Corrupt
        val plain = try {
            cipher.open(bytes)
        } catch(e: javax.crypto.AEADBadTagException) {
            return Read.Corrupt
        } catch(e: Exception) {
            return if(e.javaClass.name == KEY_PERMANENTLY_INVALIDATED) Read.Corrupt else Read.Error
        }
        val parsed = try {
            json.decodeFromString(InscriptionFile.serializer(), plain.decodeToString())
        } catch(e: Exception) {
            return Read.Corrupt
        }
        return if(parsed.v == VERSION) Read.Ok(parsed) else Read.Corrupt
    }

    /**
     * Replaces the wallet's file. Callers pass only unspent coins, which is how spent ones drop out. Each write goes
     * through its own temp file, so two writers (even through two stores) never mix; the last one wins. [blocks] are
     * block hashes by height the checker keeps with the checks, [marks] the kept indexer marks (see [InscriptionChecker]).
     */
    fun save(walletName: String, checks: Map<String, InscriptionCheck>, blocks: Map<Int, String> = emptyMap(),
             marks: Map<String, KeptMark> = emptyMap()) = save(walletName, StoredFile(checks, blocks, marks))

    /** Replaces the wallet's file with everything in [file] (see the other [save]). */
    @Synchronized
    fun save(walletName: String, file: StoredFile) {
        writeSealed(fileFor(walletName), seal(file))
    }

    private fun seal(file: StoredFile): ByteArray {
        val content = InscriptionFile(VERSION, file.checks, file.blocks, file.marks, file.asked, file.lost, file.lastUnreachable,
            file.lastRunAt, file.lostCoins, file.askedFor, file.lastPartial)
        return cipher.seal(json.encodeToString(InscriptionFile.serializer(), content).encodeToByteArray())
    }

    /** Puts [sealed] in place of [target] through a temp file and an atomic move, so a reader sees the old or the new. */
    private fun writeSealed(target: File, sealed: ByteArray) {
        dir.mkdirs()
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
    fun delete(walletName: String) {
        fileFor(walletName).let { File(dir, it.name + UNREADABLE_SUFFIX).delete(); it.delete() }
    }

    companion object {
        const val VERSION = 1
        const val KEY_ALIAS = "sparrow-inscriptions"
        /** The name ending of a results file set aside because it could not be read. */
        const val UNREADABLE_SUFFIX = ".unreadable"
        /** A sealed file holds at least its 12-byte IV and 16-byte tag. */
        private const val MIN_SEALED_BYTES = 28
        private const val KEY_PERMANENTLY_INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"
    }
}

/** Removes everything cached for a deleted wallet: its inscription checks and its background-sync credentials. */
internal fun deleteWalletCaches(walletName: String, inscriptions: InscriptionStore, syncCacheFile: File) {
    inscriptions.delete(walletName)
    syncCacheFile.delete()
}
