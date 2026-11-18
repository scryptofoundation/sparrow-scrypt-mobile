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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /** Review I3: the file also keeps block hashes by height; a file written before that loads with none. */
    @Test
    fun blockHashesRoundTripAndOldFilesLoadWithout() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        val cipher = MemoryCipher()
        val store = InscriptionStore(dir, cipher)
        val checks = mapOf(check(0).outpoint to check(0))
        store.save("w", checks, mapOf(100 to "cd".repeat(32)))
        assertEquals(checks, store.load("w"))
        assertEquals(mapOf(100 to "cd".repeat(32)), store.loadBlocks("w"))
        File(dir, "old.bin").writeBytes(cipher.seal("""{"v":1,"checks":{}}""".encodeToByteArray()))
        assertEquals(emptyMap(), store.loadOrNull("old"))
        assertEquals(emptyMap(), store.loadBlocks("old"))
        assertEquals(emptyMap(), store.loadBlocks("none"))
    }

    /** The MWEB peg-out flag round-trips; a file written before it existed loads with it unknown, in the same version. */
    @Test
    fun mwebPegOutRoundTripsAndOldRowsLoadWithoutIt() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        val cipher = MemoryCipher()
        val store = InscriptionStore(dir, cipher)
        val block = "cd".repeat(32)
        val checks = mapOf(
            check(0).copy(blockHash = block, mwebPegOut = true).let { it.outpoint to it },
            check(1).copy(blockHash = block, mwebPegOut = false).let { it.outpoint to it },
            check(2).copy(blockHash = block).let { it.outpoint to it }
        )
        store.save("w", checks)
        val loaded = store.load("w")
        assertEquals(checks, loaded)
        assertEquals(true, loaded.getValue("$txid:0").mwebPegOut)
        assertEquals(false, loaded.getValue("$txid:1").mwebPegOut)
        assertNull(loaded.getValue("$txid:2").mwebPegOut)

        val old = """{"v":1,"checks":{"$txid:3":{"txid":"$txid","vout":3,"verdict":"CLEAN","source":"BUILT_IN","runes":false,""" +
            """"found":[],"reason":null,"blockHash":"$block","open":[]}}}"""
        File(dir, "old.bin").writeBytes(cipher.seal(old.encodeToByteArray()))
        val row = store.loadOrNull("old")!!.getValue("$txid:3")
        assertEquals(check(3).copy(blockHash = block), row)
        assertNull(row.mwebPegOut)
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

    /** loadOrNull tells "checked, nothing stored" (an empty map) from "no readable results" (null). */
    @Test
    fun loadOrNullTellsACheckedEmptyWalletFromNoFile() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        val cipher = MemoryCipher()
        val store = InscriptionStore(dir, cipher)
        assertNull(store.loadOrNull("missing"))
        store.save("empty", emptyMap())
        assertEquals(emptyMap(), store.loadOrNull("empty"))
        store.save("w", mapOf(check(0).outpoint to check(0)))
        assertEquals(mapOf(check(0).outpoint to check(0)), store.loadOrNull("w"))
        val f = File(dir, "w.bin")
        f.writeBytes(f.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertNull(store.loadOrNull("w"), "undecryptable")
        File(dir, "a.bin").writeBytes(cipher.seal("not json".encodeToByteArray()))
        assertNull(store.loadOrNull("a"), "unparseable")
        File(dir, "b.bin").writeBytes(cipher.seal("""{"v":2,"checks":{}}""".encodeToByteArray()))
        assertNull(store.loadOrNull("b"), "unknown version")
    }

    /** The checker carries the same distinction: a wallet whose run stored nothing still counts as checked. */
    @Test
    fun checkerTellsACheckedEmptyWalletFromAnUncheckedOne() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        val cipher = MemoryCipher()
        InscriptionStore(dir, cipher).save(walletFileName("Checked"), emptyMap())
        val checker = InscriptionChecker(InscriptionStore(dir, cipher))
        assertEquals(emptyMap(), checker.storedResults("Checked"))
        assertNull(checker.storedResults("Never"))
        assertEquals(emptyMap(), checker.results("Never"))
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

    private fun busy(temp: java.nio.file.Path, target: java.nio.file.Path) =
        java.nio.file.AccessDeniedException(temp.toString(), target.toString(), null)

    /**
     * Windows refuses to replace a file that another save is replacing or a reader holds open (an AccessDeniedException;
     * Linux, so Android, never does): the save tries the same atomic move of the same temp file again.
     */
    @Test
    fun aSaveTriesItsMoveAgainWhileTheTargetIsBusy() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        val cipher = MemoryCipher()
        store(dir, cipher).save("w", mapOf(check(0).outpoint to check(0)))
        val temps = mutableListOf<java.nio.file.Path>()
        val store = InscriptionStore(dir, cipher) { temp, target ->
            temps.add(temp)
            if(temps.size <= 3) throw busy(temp, target)
            java.nio.file.Files.move(temp, target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        store.save("w", mapOf(check(1).outpoint to check(1)))
        assertEquals(4, temps.size, "three refusals, then the move")
        assertEquals(1, temps.toSet().size, "one temp file for the whole save")
        assertEquals(mapOf(check(1).outpoint to check(1)), store.load("w"))
        assertEquals(listOf("w.bin"), dir.list()!!.toList(), "no temp file left behind")
    }

    /** The tries are bounded. A save whose target stays busy fails, and the file it would have replaced is never touched. */
    @Test
    fun aSaveWhoseTargetStaysBusyFailsAndKeepsTheOldFile() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        val cipher = MemoryCipher()
        val old = mapOf(check(0).outpoint to check(0))
        store(dir, cipher).save("w", old)
        val before = File(dir, "w.bin").readBytes()
        var moves = 0
        val store = InscriptionStore(dir, cipher) { temp, target ->
            moves++
            assertTrue(target.toFile().readBytes().contentEquals(before), "the old file is whole at try $moves")
            throw busy(temp, target)
        }
        assertFailsWith<java.nio.file.AccessDeniedException> { store.save("w", mapOf(check(1).outpoint to check(1))) }
        assertEquals(InscriptionStore.MOVE_TRIES, moves)
        assertTrue(File(dir, "w.bin").readBytes().contentEquals(before), "the old file, byte for byte")
        assertEquals(old, store.load("w"))
        assertEquals(listOf("w.bin"), dir.list()!!.toList(), "no temp file left behind")
    }

    /** Only a busy target is tried again: a move that fails for any other reason fails the save at once. */
    @Test
    fun aMoveThatFailsForAnotherReasonIsNotTriedAgain() {
        val dir = java.nio.file.Files.createTempDirectory("insc").toFile()
        var moves = 0
        val store = InscriptionStore(dir, MemoryCipher()) { _, _ ->
            moves++
            throw java.io.IOException("no space left")
        }
        assertFailsWith<java.io.IOException> { store.save("w", mapOf(check(0).outpoint to check(0))) }
        assertEquals(1, moves)
        assertEquals(emptyList(), dir.list()!!.toList(), "no temp file left behind")
    }

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
