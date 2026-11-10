package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.Balance
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.HistoryEntry
import com.sparrowwallet.mobile.electrum.TxAt
import com.sparrowwallet.mobile.electrum.TxDetail
import com.sparrowwallet.mobile.electrum.Utxo
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AES-GCM over an in-memory key that can be told to fail, as a Keystore that will not seal would. */
private class CheckerCipher : StoreCipher {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    var failSeal = false

    override fun seal(plain: ByteArray): ByteArray {
        if(failSeal) throw IllegalStateException("Keystore unavailable")
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

/** A chain of raw transactions by txid, with block hashes by height; counts every call. */
private class FakeChain : ChainBackend {
    val raw = HashMap<String, ByteArray>()
    val heights = HashMap<String, Int>()
    var calls = 0
    var rawCalls = 0
    var blockCalls = 0
    var blockHashNull = false
    var blockHashFails = false
    var blockHashRateLimited = false
    var cancelOn: String? = null
    /** Runs before each transaction read, as something else the app does mid-run would. */
    var beforeRaw: (String) -> Unit = {}

    fun add(bytes: ByteArray, height: Int): String {
        val txid = RawTx.parse(bytes).txid
        raw[txid] = bytes
        heights[txid] = height
        return txid
    }

    override suspend fun connect() { calls++ }
    override suspend fun balance(script: ByteArray): Balance { calls++; error("not used") }
    override suspend fun history(script: ByteArray): List<HistoryEntry> { calls++; error("not used") }
    override suspend fun unspent(script: ByteArray): List<Utxo> { calls++; error("not used") }
    override suspend fun transaction(txid: String): TxDetail { calls++; error("not used") }
    override suspend fun broadcast(txHex: String): String { calls++; error("not used") }
    override suspend fun tipHeight(): Long { calls++; error("not used") }
    override suspend fun feeRate(targetBlocks: Int): Double? { calls++; error("not used") }

    override suspend fun rawTransaction(txid: String): ByteArray {
        calls++
        rawCalls++
        beforeRaw(txid)
        if(txid == cancelOn) throw CancellationException("left the screen")
        return raw[txid] ?: throw IOException("No such transaction $txid")
    }

    override suspend fun transactionAt(txid: String): TxAt {
        calls++
        val height = heights.getValue(txid)
        return if(height > 0) TxAt(height, block(height), 1_700_000_000L + height) else TxAt(0, null, 0)
    }

    override suspend fun blockHash(height: Int): String? {
        calls++
        blockCalls++
        if(blockHashFails) throw IOException("connection reset")
        if(blockHashRateLimited) throw com.sparrowwallet.mobile.electrum.RateLimitedException("HTTP 429")
        return if(blockHashNull) null else block(height)
    }

    override fun close() {}

    companion object {
        fun block(height: Int): String = (0xb10c0000L + height).toString(16).padStart(64, '0')
    }
}

class InscriptionCheckerTest {
    private val wallet = "My Wallet"

    private fun le(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { ((value shr (8 * it)) and 0xFF).toByte() }

    /** A plain transaction spending (txid, vout) and paying each value to OP_TRUE. */
    private fun tx(spends: List<Pair<String, Int>>, outputs: List<Long>): ByteArray {
        var b = le(2, 4) + byteArrayOf(spends.size.toByte())
        for((txid, vout) in spends) {
            b += txid.hexToBytes().reversedArray() + le(vout.toLong(), 4) + byteArrayOf(0) + le(0xFFFFFFFFL, 4)
        }
        b += byteArrayOf(outputs.size.toByte())
        for(value in outputs) {
            b += le(value, 8) + byteArrayOf(1, 0x51)
        }
        return b + le(0, 4)
    }

    /** A transaction mined at height 100, long before Litecoin's first inscription, so it traces Clean in one lookup. */
    private fun oldCoin(chain: FakeChain, value: Long): String = chain.add(tx(listOf("aa".repeat(32) to 0), listOf(value)), 100)

    private fun newDir(): File = java.nio.file.Files.createTempDirectory("checker").toFile()

    private fun checker(dir: File, cipher: StoreCipher) = InscriptionChecker(InscriptionStore(dir, cipher))

    private suspend fun InscriptionChecker.ltc(chain: FakeChain, utxos: List<Utxo>, deeper: Set<String> = emptySet(),
                                               timeUp: () -> Boolean = { false }) =
        check(wallet, Coin.LITECOIN, Network.MAINNET, chain, utxos, deeper, timeUp)

    /** Four coins, each Clean in one lookup, with provisional rows (no block) stored by a first run. */
    private class Four(val chain: FakeChain, val coins: List<String>, val utxos: List<Utxo>, val old: Map<String, InscriptionCheck>)

    private suspend fun four(dir: File, cipher: StoreCipher): Four {
        val chain = FakeChain()
        val coins = (1..4).map { oldCoin(chain, 50_000L + it) }
        val utxos = coins.mapIndexed { i, txid -> Utxo(txid, 0, 100, 50_001L + i) }
        chain.blockHashNull = true
        val old = checker(dir, cipher).ltc(chain, utxos)
        assertTrue(old.values.all { it.blockHash == null })
        chain.blockHashNull = false
        return Four(chain, coins, utxos, old)
    }

    /** Review I2: a run killed after the 2nd of 4 coins has saved the first two new results over the old rows. */
    @Test fun aCancelledRunKeepsItsProgress(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val f = four(dir, cipher)
        f.chain.cancelOn = f.coins[2]
        val checker = checker(dir, cipher)
        assertFailsWith<CancellationException> { checker.ltc(f.chain, f.utxos) }
        val saved = InscriptionStore(dir, cipher).load(wallet)
        assertEquals(f.coins.map { "$it:0" }.toSet(), saved.keys, "no row dropped, none invented")
        for(txid in f.coins.take(2)) {
            assertEquals(FakeChain.block(100), saved.getValue("$txid:0").blockHash, "a new result")
        }
        for(txid in f.coins.drop(2)) {
            assertEquals(f.old.getValue("$txid:0"), saved.getValue("$txid:0"), "the old row")
        }
        assertEquals(saved, checker.results(wallet), "memory follows the file")
        assertNull(checker.progress(wallet).value, "no run shown once it ends")
    }

    /** A deadline stops the run between coins; what it decided is saved, the rest says why it was not checked. */
    @Test fun aDeadlineStopsBetweenCoinsAndSaves(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val f = four(dir, cipher)
        f.chain.rawCalls = 0
        val result = checker(dir, cipher).ltc(f.chain, f.utxos, timeUp = { f.chain.rawCalls >= 2 })
        assertEquals(2, f.chain.rawCalls)
        assertEquals(result, InscriptionStore(dir, cipher).load(wallet))
        for(txid in f.coins.take(2)) {
            assertEquals(Verdict.CLEAN, result.getValue("$txid:0").verdict)
            assertEquals(FakeChain.block(100), result.getValue("$txid:0").blockHash)
        }
        for(txid in f.coins.drop(2)) {
            assertEquals(Verdict.UNCHECKED, result.getValue("$txid:0").verdict)
            assertEquals(com.sparrowwallet.mobile.electrum.inscriptions.TrackerRun.TIME_UP, result.getValue("$txid:0").reason)
        }
    }

    /** A save is skipped when a coin's result is what the file already holds. */
    @Test fun standingRowsWriteNothing(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        checker(dir, cipher).ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))
        val file = File(dir, "My Wallet.bin")
        val bytes = file.readBytes()
        checker(dir, cipher).ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))
        assertTrue(bytes.contentEquals(file.readBytes()), "not rewritten")
    }

    /** Home reads a run's progress from the checker, whoever started it. */
    @Test fun progressIsShownWhileARunGoes(): Unit = runBlocking {
        val chain = FakeChain()
        val coins = (1..2).map { oldCoin(chain, 50_000L + it) }
        val checker = checker(newDir(), CheckerCipher())
        val seen = ArrayList<Pair<Int, Int>?>()
        chain.beforeRaw = { seen.add(checker.progress(wallet).value) }
        checker.ltc(chain, coins.map { Utxo(it, 0, 100, 1) })
        assertEquals(listOf<Pair<Int, Int>?>(0 to 2, 1 to 2), seen)
        assertNull(checker.progress(wallet).value)
    }

    /** Review I2: a run Home starts lives in the checker's scope, so leaving Home does not cancel it. */
    @Test fun aRunStartedFromAScreenOutlivesIt(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val screen = CoroutineScope(Job())
        var job: Job? = null
        screen.launch { job = checker.start(wallet) { started.complete(Unit); release.await(); emptyMap() } }.join()
        started.await()
        screen.cancel()
        assertTrue(job!!.isActive, "still running after the screen's scope is gone")
        assertNull(checker.start(wallet) { error("a second run for the wallet") }, "one run per wallet")
        release.complete(Unit)
        job!!.join()
        assertFalse(job!!.isCancelled)
    }

    /** A wallet deleted while its check runs is not written back by that run, and forget cancels a started run. */
    @Test fun aDeletedWalletIsNotWrittenBack(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val coins = (1..3).map { oldCoin(chain, 50_000L + it) }
        val checker = checker(dir, cipher)
        chain.beforeRaw = { if(it == coins[1]) { checker.forget(wallet); checker.store.delete(wallet) } }
        checker.ltc(chain, coins.map { Utxo(it, 0, 100, 1) })
        assertFalse(File(dir, "My Wallet.bin").exists())

        val started = CompletableDeferred<Unit>()
        val job = checker.start(wallet) { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }!!
        started.await()
        checker.forget(wallet)
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test fun uncoveredCoinDoesNoWork(): Unit = runBlocking {
        val chain = FakeChain()
        val txid = oldCoin(chain, 50_000)
        val dir = newDir()
        val result = checker(dir, CheckerCipher()).check(wallet, Coin.LUCKYCOIN, Network.MAINNET, chain, listOf(Utxo(txid, 0, 100, 50_000)))
        assertTrue(result.isEmpty())
        assertEquals(0, chain.calls)
        assertTrue(dir.list()!!.isEmpty(), "nothing stored")
    }

    @Test fun resultsAreSavedAndReused(): Unit = runBlocking {
        val chain = FakeChain()
        val txid = oldCoin(chain, 50_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        val utxos = listOf(Utxo(txid, 0, 100, 50_000))
        val first = checker(dir, cipher).ltc(chain, utxos)
        val check = first.getValue("$txid:0")
        assertEquals(Verdict.CLEAN, check.verdict)
        assertEquals(FakeChain.block(100), check.blockHash)
        assertTrue(chain.rawCalls > 0)
        assertEquals(first, InscriptionStore(dir, cipher).load(wallet))

        //A fresh checker (an empty transaction cache, as after a restart) reads the stored result and looks nothing up
        chain.rawCalls = 0
        val again = checker(dir, cipher)
        assertEquals(first, again.results(wallet))
        assertEquals(first, again.ltc(chain, utxos))
        assertEquals(0, chain.rawCalls)
    }

    @Test fun unconfirmedCoinIsStoredProvisionalAndTracedAgain(): Unit = runBlocking {
        val chain = FakeChain()
        val parent = oldCoin(chain, 60_000)
        val pay = chain.add(tx(listOf(parent to 0), listOf(50_000)), 0)
        val dir = newDir()
        val cipher = CheckerCipher()
        val checker = checker(dir, cipher)

        val first = checker.ltc(chain, listOf(Utxo(pay, 0, 0, 50_000)))
        assertEquals(0, chain.blockCalls, "an unconfirmed coin has no block to ask for")
        val row = InscriptionStore(dir, cipher).load(wallet).getValue("$pay:0")
        assertEquals(first.getValue("$pay:0"), row)
        assertEquals(Verdict.CLEAN, row.verdict)
        assertNull(row.blockHash)
        assertFalse(row.proven, "a Clean without a block is provisional")

        //Still unconfirmed: the row does not stand, and the unconfirmed transaction was not cached, so it is read again
        chain.rawCalls = 0
        checker.ltc(chain, listOf(Utxo(pay, 0, 0, 50_000)))
        assertTrue(chain.rawCalls > 0, "traced again")

        //It confirms: traced again, and now it carries its block
        chain.heights[pay] = 150
        chain.rawCalls = 0
        val confirmed = checker.ltc(chain, listOf(Utxo(pay, 0, 150, 50_000)))
        assertTrue(chain.rawCalls > 0, "traced again")
        assertEquals(FakeChain.block(150), confirmed.getValue("$pay:0").blockHash)
        assertTrue(confirmed.getValue("$pay:0").proven)

        chain.rawCalls = 0
        checker(dir, cipher).ltc(chain, listOf(Utxo(pay, 0, 150, 50_000)))
        assertEquals(0, chain.rawCalls, "now it stands")
    }

    @Test fun spentCoinsLeaveTheStore(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 70_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        val checker = checker(dir, cipher)
        checker.ltc(chain, listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 70_000)))
        assertEquals(setOf("$a:0", "$b:0"), InscriptionStore(dir, cipher).load(wallet).keys)

        val after = checker.ltc(chain, listOf(Utxo(b, 0, 100, 70_000)))
        assertEquals(setOf("$b:0"), after.keys)
        assertEquals(setOf("$b:0"), InscriptionStore(dir, cipher).load(wallet).keys)
        assertEquals(setOf("$b:0"), checker.results(wallet).keys)
    }

    @Test fun airGappedRunsNothing(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 70_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        val stored = checker(dir, cipher).ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))
        chain.calls = 0
        try {
            NetworkPolicy.blocked = true
            val checker = checker(dir, cipher)
            val result = checker.ltc(chain, listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 70_000)))
            assertEquals(stored, result)
            assertEquals(stored, checker.results(wallet))
            assertEquals(0, chain.calls)
        } finally {
            NetworkPolicy.blocked = false
        }
        assertEquals(stored, InscriptionStore(dir, cipher).load(wallet))
    }

    @Test fun aMissingOrFailedBlockHashIsTracedAgainNextRun(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 70_000)
        val utxos = listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 70_000))
        val dir = newDir()
        val cipher = CheckerCipher()

        chain.blockHashNull = true
        val first = checker(dir, cipher).ltc(chain, utxos)
        assertEquals(1, chain.blockCalls, "one call per distinct height")
        assertTrue(first.values.all { it.blockHash == null })

        //No block: nothing stands, so the next run traces again; a block hash that fails does not fail the run
        chain.blockHashNull = false
        chain.blockHashFails = true
        chain.rawCalls = 0
        val second = checker(dir, cipher).ltc(chain, utxos)
        assertTrue(chain.rawCalls > 0, "traced again")
        assertEquals(setOf("$a:0", "$b:0"), second.keys)
        assertTrue(second.values.all { it.blockHash == null })

        chain.blockHashFails = false
        chain.rawCalls = 0
        val third = checker(dir, cipher).ltc(chain, utxos)
        assertTrue(chain.rawCalls > 0, "traced again")
        assertTrue(third.values.all { it.blockHash == FakeChain.block(100) })

        chain.rawCalls = 0
        checker(dir, cipher).ltc(chain, utxos)
        assertEquals(0, chain.rawCalls, "with its block the result stands")
    }

    /**
     * Review I3: a rate-limited block-hash call ends the run before anything is saved. Read as "no block", it would
     * drop every proven row as if reorged and trace them all again on a server that is already refusing.
     */
    @Test fun aRateLimitedBlockHashEndsTheRunBeforeAnythingIsSaved(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        checker(dir, cipher).ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))
        val bytes = File(dir, "My Wallet.bin").readBytes()
        chain.blockHashRateLimited = true
        chain.rawCalls = 0
        val checker = checker(dir, cipher)
        assertFailsWith<com.sparrowwallet.mobile.electrum.RateLimitedException> { checker.ltc(chain, listOf(Utxo(a, 0, 100, 50_000))) }
        assertEquals(0, chain.rawCalls)
        assertTrue(bytes.contentEquals(File(dir, "My Wallet.bin").readBytes()))
    }

    @Test fun aRunThatThrowsSavesNothing(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 70_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        val stored = checker(dir, cipher).ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))
        val file = File(dir, "My Wallet.bin")
        val bytes = file.readBytes()

        chain.cancelOn = b
        val checker = checker(dir, cipher)
        assertFailsWith<CancellationException> {
            checker.ltc(chain, listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 70_000)))
        }
        assertTrue(bytes.contentEquals(file.readBytes()), "the previous file is intact")
        assertEquals(stored, checker.results(wallet))
        assertEquals(listOf("My Wallet.bin"), dir.list()!!.toList())
    }

    @Test fun aFailedCheckLeavesTheStoredResults(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 70_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        val stored = checker(dir, cipher).ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))
        val bytes = File(dir, "My Wallet.bin").readBytes()

        cipher.failSeal = true
        val checker = checker(dir, cipher)
        val result = checker.guarded(wallet) { checker.ltc(chain, listOf(Utxo(b, 0, 100, 70_000))) }
        assertEquals(stored, result)
        assertEquals(stored, checker.results(wallet))
        assertTrue(bytes.contentEquals(File(dir, "My Wallet.bin").readBytes()))

        //Cancellation is not swallowed (a fresh checker, whose cache has not read b yet)
        cipher.failSeal = false
        chain.cancelOn = b
        val fresh = checker(dir, cipher)
        assertFailsWith<CancellationException> {
            fresh.guarded(wallet) { fresh.ltc(chain, listOf(Utxo(b, 0, 100, 70_000))) }
        }
        assertTrue(bytes.contentEquals(File(dir, "My Wallet.bin").readBytes()))
    }

    /**
     * The worker passes the name its credentials were cached under; Home passes the session's wallet file name
     * ([walletFileName] of the same name). Both reach the same results, file and guard.
     */
    @Test fun workerAndHomeNamesReachTheSameWallet(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        val raw = "A wallet with a name/that runs past forty characters"
        val home = walletFileName(raw)
        val stored = checker(dir, cipher).check(raw, Coin.LITECOIN, Network.MAINNET, chain, listOf(Utxo(a, 0, 100, 50_000)))
        assertEquals(1, dir.list()!!.size)

        val checker = checker(dir, cipher)
        assertEquals(stored, checker.results(home))
        chain.rawCalls = 0
        assertEquals(stored, checker.check(home, Coin.LITECOIN, Network.MAINNET, chain, listOf(Utxo(a, 0, 100, 50_000))))
        assertEquals(0, chain.rawCalls)
        assertEquals(1, dir.list()!!.size, "one file for both names")

        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val running = async { checker.guarded(raw) { started.complete(Unit); release.await(); stored } }
        started.await()
        var ran = false
        checker.guarded(home) { ran = true; stored }
        assertFalse(ran, "the same wallet under its other name is skipped")
        release.complete(Unit)
        running.await()
    }

    /** What composition reads: the copy in memory only, never a load of the file. */
    @Test fun cachedReadsMemoryOnly(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        val stored = checker(dir, cipher).ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))

        val checker = checker(dir, cipher)
        assertNull(checker.cached(wallet), "not loaded yet")
        assertEquals(stored, checker.results(wallet))
        assertEquals(stored, checker.cached(wallet))
    }

    /** The worker learns whether its check ran: null when skipped (one is going) or failed. */
    @Test fun tryRunSaysWhetherTheCheckRan(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        assertEquals(emptyMap(), checker.tryRun(wallet) { emptyMap() })
        assertNull(checker.tryRun(wallet) { throw IOException("server down") })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { checker.tryRun(wallet) { started.complete(Unit); release.await(); emptyMap() } }
        started.await()
        assertNull(checker.tryRun(wallet) { emptyMap() })
        release.complete(Unit)
        first.await()
    }

    @Test fun anOverlappingRunForTheSameWalletIsSkipped(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = mapOf("x:0" to InscriptionCheck("x", 0, Verdict.CLEAN, InscriptionCheck.Source.BUILT_IN, false, emptyList(), null, "b", emptyList()))
        val first = async {
            checker.guarded(wallet) {
                started.complete(Unit)
                release.await()
                done
            }
        }
        started.await()
        var ran = false
        val skipped = checker.guarded(wallet) { ran = true; done }
        assertTrue(!ran, "a second run while one is going is skipped")
        assertTrue(skipped.isEmpty())
        //Another wallet is not held up
        assertEquals(done, checker.guarded("Other") { done })
        release.complete(Unit)
        assertEquals(done, first.await())
        assertEquals(done, checker.guarded(wallet) { ran = true; done })
        assertTrue(ran, "the guard is released when the run ends")
    }
}
