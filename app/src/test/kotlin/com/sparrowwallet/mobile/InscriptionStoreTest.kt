package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.Piece
import com.sparrowwallet.mobile.crypto.track.UnitRange
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An AES-256-GCM cipher over an in-memory key, laid out like [KeystoreCipher] (12-byte IV in front). */
private class MemoryCipher : StoreCipher {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }
    override fun open(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }
}

class InscriptionStoreTest {
    private val txid = "ab".repeat(32)

    private fun check(vout: Int, verdict: Verdict = Verdict.CLEAN) = InscriptionCheck(
        txid, vout, verdict, InscriptionCheck.Source.BUILT_IN, false, emptyList(), null, null, emptyList())

    private fun newStore(): Pair<InscriptionStore, File> {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        return InscriptionStore(dir, MemoryCipher()) to dir
    }

    @Test
    fun roundTripsEveryField() {
        val (store, _) = newStore()
        val full = InscriptionCheck(
            txid, 1, Verdict.INSCRIBED, InscriptionCheck.Source.PUBLIC, true,
            listOf(Found("${txid}i0", 0, 12L, txid, 100, 1700000000L), Found("${txid}i1", -1, null, txid, 100, 1700000000L)),
            "cut short", "cd".repeat(32), listOf(Piece(txid, 0, UnitRange(0, 500), 3)))
        val checks = mapOf(full.outpoint to full, check(2).outpoint to check(2))
        store.save("My Wallet", checks)
        assertEquals(checks, store.load("My Wallet"))
    }

    @Test
    fun missingAndUnreadableLoadEmpty() {
        val (store, dir) = newStore()
        assertTrue(store.load("none").isEmpty())
        store.save("w", mapOf(check(0).outpoint to check(0)))
        val f = File(dir, "w.bin")
        f.writeBytes(f.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertTrue(store.load("w").isEmpty(), "tampered file")
        f.writeBytes(ByteArray(5))
        assertTrue(store.load("w").isEmpty(), "short file")
    }

    @Test
    fun unparseableOrUnknownVersionLoadsEmpty() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        val cipher = MemoryCipher()
        val store = InscriptionStore(dir, cipher)
        File(dir, "a.bin").writeBytes(cipher.seal("not json".encodeToByteArray()))
        assertTrue(store.load("a").isEmpty())
        File(dir, "b.bin").writeBytes(cipher.seal("""{"v":2,"checks":{}}""".encodeToByteArray()))
        assertTrue(store.load("b").isEmpty())
    }

    @Test
    fun saveReplacesSoSpentCoinsDrop() {
        val (store, dir) = newStore()
        store.save("w", mapOf(check(0).outpoint to check(0), check(1).outpoint to check(1)))
        store.save("w", mapOf(check(1).outpoint to check(1)))
        assertEquals(setOf(check(1).outpoint), store.load("w").keys)
        assertEquals(listOf("w.bin"), dir.list()!!.toList(), "no temp file left behind")
    }

    @Test
    fun twoSavesInARowLeaveNoTempFileAndTheLastContent() {
        val (store, dir) = newStore()
        store.save("w", mapOf(check(0).outpoint to check(0)))
        store.save("w", mapOf(check(1).outpoint to check(1, Verdict.INSCRIBED)))
        assertEquals(mapOf(check(1).outpoint to check(1, Verdict.INSCRIBED)), store.load("w"))
        assertEquals(listOf("w.bin"), dir.list()!!.toList())
    }

    /** Home and the background worker can save one wallet at once, each through its own store: neither may fail or corrupt the file. */
    @Test
    fun overlappingSavesNeverShareATempFile() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        val cipher = MemoryCipher()
        val stores = listOf(InscriptionStore(dir, cipher), InscriptionStore(dir, cipher))
        val contents = (0 until 4).map { n -> mapOf(check(n).outpoint to check(n)) }
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 4).map { t ->
            Thread {
                start.await()
                repeat(200) {
                    try {
                        stores[t % 2].save("w", contents[t])
                    } catch(e: Throwable) {
                        failures.add(e)
                    }
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue(failures.isEmpty(), "saves failed: ${failures.firstOrNull()}")
        assertTrue(store(dir, cipher).load("w") in contents)
        assertEquals(listOf("w.bin"), dir.list()!!.toList())
    }

    private fun store(dir: File, cipher: StoreCipher) = InscriptionStore(dir, cipher)

    @Test
    fun fileIsNotPlaintext() {
        val (store, dir) = newStore()
        store.save("w", mapOf(check(0).outpoint to check(0)))
        val bytes = File(dir, "w.bin").readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse(bytes.contains(check(0).outpoint))
        assertFalse(bytes.contains(txid))
    }

    @Test
    fun deleteRemovesTheFile() {
        val (store, dir) = newStore()
        store.save("w", mapOf(check(0).outpoint to check(0)))
        store.delete("w")
        assertFalse(File(dir, "w.bin").exists())
        assertTrue(store.load("w").isEmpty())
    }

    @Test
    fun deletingAWalletRemovesItsInscriptionAndSyncCacheFiles() {
        val filesDir = java.nio.file.Files.createTempDirectory("files").toFile()
        val store = InscriptionStore(File(filesDir, "inscriptions"), MemoryCipher())
        store.save("My Wallet", mapOf(check(0).outpoint to check(0)))
        store.save("Other", mapOf(check(0).outpoint to check(0)))
        val sync = SyncStore.cacheFile(filesDir, "My Wallet").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val otherSync = SyncStore.cacheFile(filesDir, "Other").apply { writeBytes(byteArrayOf(1)) }
        deleteWalletCaches("My Wallet", store, sync)
        assertFalse(sync.exists())
        assertTrue(store.load("My Wallet").isEmpty())
        assertTrue(otherSync.exists())
        assertEquals(1, store.load("Other").size)
    }
}
