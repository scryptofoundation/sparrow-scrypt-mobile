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
import com.sparrowwallet.mobile.electrum.indexer.IndexerException
import com.sparrowwallet.mobile.electrum.indexer.IndexerInscription
import com.sparrowwallet.mobile.electrum.indexer.IndexerOutput
import com.sparrowwallet.mobile.electrum.indexer.IndexerStatus
import com.sparrowwallet.mobile.electrum.indexer.InscriptionIndexer
import com.sparrowwallet.mobile.electrum.indexer.RuneBalance
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AES-GCM over an in-memory key that can be told to fail, as a Keystore that will not seal would. */
private class CheckerCipher : StoreCipher {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    var failSeal = false
    /** Thrown by [open] when set, as a read error (IO, a busy Keystore) would be. */
    var failOpen: Exception? = null

    override fun seal(plain: ByteArray): ByteArray {
        if(failSeal) throw IllegalStateException("Keystore unavailable")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }

    override fun open(blob: ByteArray): ByteArray {
        failOpen?.let { throw it }
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
    /** Nonzero: the server reports another block's hash for a height, as a lying or reorged server would. */
    var blockHashShift = 0
    var blockHashFails = false
    var blockHashRateLimited = false
    var cancelOn: String? = null
    /** Runs before each transaction read, as something else the app does mid-run would. */
    var beforeRaw: (String) -> Unit = {}
    /** Runs before each block-hash call. */
    var beforeBlock: (Int) -> Unit = {}

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
    var tip = 1_000L
    var tipCalls = 0
    override suspend fun tipHeight(): Long { calls++; tipCalls++; return tip }
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
        beforeBlock(height)
        if(blockHashFails) throw IOException("connection reset")
        if(blockHashRateLimited) throw com.sparrowwallet.mobile.electrum.RateLimitedException("HTTP 429")
        return if(blockHashNull) null else if(blockHashShift != 0) block(height + blockHashShift) else block(height)
    }

    override fun close() {}

    companion object {
        fun block(height: Int): String = (0xb10c0000L + height).toString(16).padStart(64, '0')
    }
}

/** An indexer whose answers the test sets; counts every call. */
private class FakeIndexer(var answer: suspend (List<String>) -> Map<String, IndexerOutput>) : InscriptionIndexer {
    override var runesAware = true
    val asked = ArrayList<List<String>>()
    val inscriptionsAsked = ArrayList<String>()
    var numbers: (String) -> Long? = { 7L }
    var inscriptionFails = false
    /** Runs inside each inscription call, before it answers (to hold it, or to outlast a deadline). */
    var onInscription: suspend () -> Unit = {}
    @Volatile var closed = false
    override var asksOneAtATime = false
    /** Calls refused because the reader was closed, as the real readers refuse them. */
    var refused = 0

    override suspend fun status(): IndexerStatus = IndexerStatus("mainnet", runesAware, 1)
    override suspend fun outputs(outpoints: List<String>): Map<String, IndexerOutput> {
        if(closed) { refused++; throw IndexerException("closed") }
        asked.add(outpoints)
        return answer(outpoints)
    }
    override suspend fun inscription(id: String): IndexerInscription? {
        if(closed) { refused++; throw IndexerException("closed") }
        inscriptionsAsked.add(id)
        onInscription()
        if(inscriptionFails) throw IndexerException("HTTP 500")
        return IndexerInscription(id, numbers(id), "text/plain", 2)
    }
    override fun close() { closed = true }

    val calls: Int get() = asked.size + inscriptionsAsked.size
}

class InscriptionCheckerTest {
    private val wallet = "My Wallet"

    private fun nothing(outpoint: String) = IndexerOutput(outpoint, true, emptyList(), emptyMap(), false)

    /** A segwit transaction spending (txid, vout) with the given witness on each input, paying each value to OP_TRUE. */
    private fun segwitTx(spends: List<Pair<String, Int>>, witnesses: List<List<ByteArray>>, outputs: List<Long>): ByteArray {
        var b = le(2, 4) + byteArrayOf(0, 1) + byteArrayOf(spends.size.toByte())
        for((txid, vout) in spends) {
            b += txid.hexToBytes().reversedArray() + le(vout.toLong(), 4) + byteArrayOf(0) + le(0xFFFFFFFFL, 4)
        }
        b += byteArrayOf(outputs.size.toByte())
        for(value in outputs) {
            b += le(value, 8) + byteArrayOf(1, 0x51)
        }
        for(items in witnesses) {
            b += byteArrayOf(items.size.toByte())
            for(item in items) {
                b += (if(item.size < 0xFD) byteArrayOf(item.size.toByte()) else byteArrayOf(0xFD.toByte(), (item.size and 0xFF).toByte(), (item.size shr 8).toByte())) + item
            }
        }
        return b + le(0, 4)
    }

    private fun push(data: ByteArray): ByteArray = if(data.isEmpty()) byteArrayOf(0) else byteArrayOf(data.size.toByte()) + data

    /** An ord reveal script holding one text envelope. */
    private fun envelope(text: String): ByteArray =
        push(ByteArray(32) { 4 }) + byteArrayOf(0xac.toByte(), 0x00, 0x63) + push("ord".encodeToByteArray()) + push(byteArrayOf(1)) +
            push("text/plain".encodeToByteArray()) + byteArrayOf(0x00) + push(text.encodeToByteArray()) + byteArrayOf(0x68)

    /** A coin made by an ord reveal mined after Litecoin's first inscription, funded by an old coin: inscribed on the chain. */
    private fun inscribedCoin(chain: FakeChain, n: Int): Pair<String, String> {
        chain.tip = 2_600_000
        val funding = chain.add(tx(listOf("ee".repeat(32) to n), listOf(10_000L)), 100)
        val reveal = chain.add(segwitTx(listOf(funding to 0), listOf(listOf(ByteArray(64), envelope("hi $n"), ByteArray(33))), listOf(10_000L)), 2_500_000)
        return reveal to "${reveal}i0"
    }

    private suspend fun InscriptionChecker.withIndexer(chain: FakeChain, utxos: List<Utxo>, indexer: InscriptionIndexer?,
                                                      source: InscriptionCheck.Source? = InscriptionCheck.Source.PUBLIC,
                                                      positions: Map<String, Pair<Int, Int>> = emptyMap()) =
        check(wallet, Coin.LITECOIN, Network.MAINNET, chain, utxos, indexer = indexer, indexerSource = source, positions = positions)

    /** The indexer's answer goes into the run: a coin the indexer and the chain both call inscribed is Inscribed, by Public. */
    @Test fun answersReachTheRunAndTheSourceIsRecorded(): Unit = runBlocking {
        val chain = FakeChain()
        val (reveal, id) = inscribedCoin(chain, 1)
        val plain = oldCoin(chain, 50_000)
        val utxos = listOf(Utxo(reveal, 0, 2_500_000, 10_000), Utxo(plain, 0, 100, 50_000))
        val indexer = FakeIndexer { asked -> asked.associateWith { if(it.startsWith(reveal)) IndexerOutput(it, true, listOf(id), emptyMap(), false) else nothing(it) } }
        val checker = checker(newDir(), CheckerCipher())
        val result = checker.withIndexer(chain, utxos, indexer)
        assertEquals(listOf(listOf("$reveal:0", "$plain:0")), indexer.asked, "one batched call for every coin")
        val inscribed = result.getValue("$reveal:0")
        assertEquals(Verdict.INSCRIBED, inscribed.verdict)
        assertEquals(InscriptionCheck.Source.PUBLIC, inscribed.source)
        assertEquals(listOf(id), inscribed.found.map { it.inscriptionId })
        assertEquals(Verdict.CLEAN, result.getValue("$plain:0").verdict)
        assertEquals(InscriptionCheck.Source.PUBLIC, result.getValue("$plain:0").source)
        assertEquals(CheckStatus(2, 2, indexerUnreachable = false, running = false), checker.status(wallet).value)
    }

    /** An indexer that fails gives no answers at all: the rows are the built-in trace's and the run says so. */
    @Test fun indexerFailureFallsBackToBuiltIn(): Unit = runBlocking {
        for(failure in listOf(IOException("connection refused"), IndexerException("HTTP 500"), IllegalStateException("odd"))) {
            val chain = FakeChain()
            val (reveal, _) = inscribedCoin(chain, 1)
            val plain = oldCoin(chain, 50_000)
            val utxos = listOf(Utxo(reveal, 0, 2_500_000, 10_000), Utxo(plain, 0, 100, 50_000))
            val indexer = FakeIndexer { throw failure }
            val checker = checker(newDir(), CheckerCipher())
            val result = checker.withIndexer(chain, utxos, indexer)
            assertTrue(result.values.all { it.source == InscriptionCheck.Source.BUILT_IN }, "$failure")
            assertEquals(Verdict.INSCRIBED, result.getValue("$reveal:0").verdict, "the chain still finds it")
            assertTrue(result.values.all { it.runeList == null })
            assertEquals(CheckStatus(2, 2, indexerUnreachable = true, running = false), checker.status(wallet).value)
            assertTrue(indexer.inscriptionsAsked.isEmpty(), "no number fill without answers")
        }
    }

    /** Half an answer set is no answer: one outpoint missing, or an entry for another outpoint, and none are used. */
    @Test fun aPartialAnswerIsNoAnswer(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 60_000)
        val utxos = listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 60_000))
        for(indexer in listOf(
            FakeIndexer { mapOf("$a:0" to IndexerOutput("$a:0", true, emptyList(), mapOf("A" to RuneBalance("1", 0, null)), false)) },
            FakeIndexer { asked -> asked.associateWith { nothing("$a:0") } }
        )) {
            val checker = checker(newDir(), CheckerCipher())
            val result = checker.withIndexer(chain, utxos, indexer)
            assertTrue(result.values.all { it.source == InscriptionCheck.Source.BUILT_IN && !it.runes && it.runeList == null })
            assertTrue(checker.status(wallet).value!!.indexerUnreachable)
        }
    }

    /** A 429 from the indexer is no answer for this run; it does not stop the trace on the wallet's own server. */
    @Test fun rateLimitedIndexerFallsBack(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val indexer = FakeIndexer { throw com.sparrowwallet.mobile.electrum.RateLimitedException("HTTP 429") }
        val checker = checker(newDir(), CheckerCipher())
        val result = checker.withIndexer(chain, listOf(Utxo(a, 0, 100, 50_000)), indexer)
        assertEquals(1, indexer.asked.size, "asked once, never waited out")
        assertTrue(chain.rawCalls > 0, "the chain trace ran")
        assertEquals(Verdict.CLEAN, result.getValue("$a:0").verdict)
        assertEquals(InscriptionCheck.Source.BUILT_IN, result.getValue("$a:0").source)
        assertTrue(checker.status(wallet).value!!.indexerUnreachable)
    }

    /** Cancellation while the indexer is asked is not swallowed as "unreachable". */
    @Test fun cancellationAtTheIndexerIsRethrown(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val indexer = FakeIndexer { throw CancellationException("left") }
        val dir = newDir()
        assertFailsWith<CancellationException> { checker(dir, CheckerCipher()).withIndexer(chain, listOf(Utxo(a, 0, 100, 50_000)), indexer) }
        assertEquals(0, chain.rawCalls)
        assertFalse(File(dir, "My Wallet.bin").exists())
    }

    /** A Public or Your own setting whose reader could not be opened: an unreachable indexer, the rows Built-in. */
    @Test fun aSourceWithoutAReaderIsUnreachable(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val checker = checker(newDir(), CheckerCipher())
        val result = checker.withIndexer(chain, listOf(Utxo(a, 0, 100, 50_000)), null, InscriptionCheck.Source.YOUR_OWN)
        assertEquals(InscriptionCheck.Source.BUILT_IN, result.getValue("$a:0").source)
        assertTrue(checker.status(wallet).value!!.indexerUnreachable)
    }

    /**
     * Built-in, air-gapped and a setting the notice has not cleared make no indexer call: the setting gives no reader to
     * open, the opener is never called, and an air-gapped run asks nothing even when handed a reader.
     */
    @Test fun builtInMakesNoIndexerCalls(): Unit = runBlocking {
        var opened = 0
        val open: suspend (String, Coin, Network, com.sparrowwallet.mobile.electrum.SocksProxy?) -> InscriptionIndexer =
            { _, _, _, _ -> opened++; FakeIndexer { error("asked") } }
        val unanswered = InscriptionSetting.parse(null, hasPublic = true, defaultChoice = null)
        assertEquals(InscriptionSetting.BuiltIn, unanswered)
        for(setting in listOf(InscriptionSetting.BuiltIn, unanswered)) {
            val picked = openCheckIndexer(setting, Coin.LITECOIN, Network.MAINNET, null, airGapped = false, open = open)
            assertNull(picked.reader)
            assertNull(picked.source)
        }
        for(setting in listOf(InscriptionSetting.Public, InscriptionSetting.YourOwn("https://ord.example"))) {
            assertNull(openCheckIndexer(setting, Coin.LITECOIN, Network.MAINNET, null, airGapped = true, open = open).reader)
        }
        assertEquals(0, opened)

        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val checker = checker(newDir(), CheckerCipher())
        val result = checker.withIndexer(chain, listOf(Utxo(a, 0, 100, 50_000)), null, null)
        assertEquals(InscriptionCheck.Source.BUILT_IN, result.getValue("$a:0").source)
        assertFalse(checker.status(wallet).value!!.indexerUnreachable)

        val indexer = FakeIndexer { error("asked") }
        try {
            NetworkPolicy.blocked = true
            checker.withIndexer(chain, listOf(Utxo(a, 0, 100, 50_000)), indexer)
        } finally {
            NetworkPolicy.blocked = false
        }
        assertEquals(0, indexer.calls)
    }

    /** Public and Your own open their reader with the coin, network and proxy, and say which source answered. */
    @Test fun publicAndYourOwnOpenTheirReader(): Unit = runBlocking {
        val tor = com.sparrowwallet.mobile.electrum.SocksProxy("127.0.0.1", 9050)
        val seen = ArrayList<String>()
        val open: suspend (String, Coin, Network, com.sparrowwallet.mobile.electrum.SocksProxy?) -> InscriptionIndexer =
            { url, coin, network, proxy -> seen.add("$url $coin $network $proxy"); FakeIndexer { emptyMap() } }
        val public = openCheckIndexer(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, tor, airGapped = false, open = open)
        assertEquals(InscriptionCheck.Source.PUBLIC, public.source)
        val own = openCheckIndexer(InscriptionSetting.YourOwn("https://ord.example/"), Coin.LITECOIN, Network.MAINNET, null, airGapped = false, open = open)
        assertEquals(InscriptionCheck.Source.YOUR_OWN, own.source)
        assertEquals(listOf("https://ordinalslite.com LITECOIN MAINNET $tor", "https://ord.example LITECOIN MAINNET null"), seen)
        //a failed open (wrong chain, unsupported kind, plain http through the proxy) is an unreachable indexer, not an error
        for(failure in listOf(IndexerException("Inscriptions server is for another chain"), IOException("refused"), IllegalArgumentException("bad"))) {
            val failed = openCheckIndexer(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, airGapped = false) { _, _, _, _ -> throw failure }
            assertNull(failed.reader)
            assertEquals(InscriptionCheck.Source.PUBLIC, failed.source)
        }
        assertFailsWith<CancellationException> {
            openCheckIndexer(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, airGapped = false) { _, _, _, _ -> throw CancellationException("gone") }
        }
    }

    /** Stored Inscribed rows (no trace needed) for n coins at height 100, each with one inscription and no number. */
    private fun storedInscribed(dir: File, cipher: StoreCipher, chain: FakeChain, n: Int): List<String> {
        val coins = (1..n).map { oldCoin(chain, 40_000L + it) }
        val rows = coins.associate { txid ->
            "$txid:0" to InscriptionCheck(txid, 0, Verdict.INSCRIBED, InscriptionCheck.Source.PUBLIC, false,
                listOf(com.sparrowwallet.mobile.crypto.track.Found("${txid}i0", 0, null, txid, 100, 1L)), null, FakeChain.block(100), emptyList())
        }
        InscriptionStore(dir, cipher).save(wallet, rows, mapOf(100 to FakeChain.block(100)))
        return coins
    }

    /** Rows Inscribed without a number get it from the indexer, at most 20 calls a run; a failure leaves it unset. */
    @Test fun numbersAreFilledForInscribedRows(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val coins = storedInscribed(dir, cipher, chain, 25)
        val utxos = coins.map { Utxo(it, 0, 100, 1_000) }
        val indexer = FakeIndexer { asked -> asked.associateWith { IndexerOutput(it, true, listOf(it.substringBefore(':') + "i0"), emptyMap(), false) } }
        indexer.numbers = { 1_000L + it.take(4).toLong(16) }
        val first = checker(dir, cipher).withIndexer(chain, utxos, indexer)
        assertEquals(20, indexer.inscriptionsAsked.size)
        assertEquals(20, first.values.count { it.found.single().number != null })
        assertTrue(first.values.all { it.verdict == Verdict.INSCRIBED })
        assertEquals(first, InscriptionStore(dir, cipher).load(wallet), "saved")
        //the next run fills the rest
        val second = checker(dir, cipher).withIndexer(chain, utxos, indexer)
        assertEquals(25, indexer.inscriptionsAsked.size)
        assertTrue(second.values.all { it.found.single().number != null })

        //a failure leaves the number unset and asks no more this run
        val dir2 = newDir()
        val coins2 = storedInscribed(dir2, cipher, chain, 3)
        val failing = FakeIndexer { asked -> asked.associateWith { IndexerOutput(it, true, listOf(it.substringBefore(':') + "i0"), emptyMap(), false) } }
        failing.inscriptionFails = true
        val third = checker(dir2, cipher).withIndexer(chain, coins2.map { Utxo(it, 0, 100, 1_000) }, failing)
        assertEquals(1, failing.inscriptionsAsked.size)
        assertTrue(third.values.all { it.found.single().number == null && it.verdict == Verdict.INSCRIBED })

        //no answers this run (the indexer failed): no number calls
        val down = FakeIndexer { throw IOException("down") }
        checker(newDir().also { storedInscribed(it, cipher, chain, 2) }, cipher).withIndexer(chain, coins.take(2).map { Utxo(it, 0, 100, 1_000) }, down)
        assertTrue(down.inscriptionsAsked.isEmpty())
    }

    /** An indexer's rune balances for a coin are stored with its row; the row is marked as carrying runes. */
    @Test fun runeListIsStoredForACoin(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 60_000)
        val runes = mapOf("UNCOMMON•GOODS" to RuneBalance("1250", 2, "x"))
        val indexer = FakeIndexer { asked -> asked.associateWith { if(it == "$a:0") IndexerOutput(it, true, emptyList(), runes, false) else nothing(it) } }
        val dir = newDir()
        val cipher = CheckerCipher()
        val result = checker(dir, cipher).withIndexer(chain, listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 60_000)), indexer)
        assertEquals(runes, result.getValue("$a:0").runeList)
        assertTrue(result.getValue("$a:0").runes)
        assertEquals(emptyMap(), result.getValue("$b:0").runeList)
        assertFalse(result.getValue("$b:0").runes)
        assertEquals(runes, InscriptionStore(dir, cipher).load(wallet).getValue("$a:0").runeList, "survives the file")
    }

    /** An indexer answer naming more inscriptions than the cap leaves the coin carrying, never Clean. */
    @Test fun tooManyInscriptionIdsAreCappedAndCarry(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        //150 ids naming 150 different transactions the chain has, with no envelopes: each reveal check is one read
        val ids = (0 until 150).map { "${chain.add(tx(listOf("dd".repeat(32) to it), listOf(1_000L)), 100)}i0" }
        val indexer = FakeIndexer { asked -> asked.associateWith { IndexerOutput(it, true, ids, emptyMap(), false) } }
        chain.rawCalls = 0
        val row = checker(newDir(), CheckerCipher()).withIndexer(chain, listOf(Utxo(a, 0, 100, 50_000)), indexer).getValue("$a:0")
        assertTrue(row.verdict != Verdict.CLEAN, row.toString())
        assertTrue(row.runes, "carries: $row")
        assertEquals(CoinState.HELD, coinState(row, null, localMayCarry = false, hasAnyResults = true))
        //the trace's own read, then one read per id up to the cap, never 150
        assertTrue(chain.rawCalls in InscriptionChecker.MAX_INDEXER_IDS..InscriptionChecker.MAX_INDEXER_IDS + 3, "${chain.rawCalls} reads")

        //a coin whose reveals cannot be read (an outage) is still marked as carrying once over the cap
        val b = oldCoin(chain, 60_000)
        val unreadable = (0 until 150).map { "${"cd".repeat(32)}i$it" }
        val outage = FakeIndexer { asked -> asked.associateWith { IndexerOutput(it, true, unreadable, emptyMap(), false) } }
        val held = checker(newDir(), CheckerCipher()).withIndexer(chain, listOf(Utxo(b, 0, 100, 60_000)), outage).getValue("$b:0")
        assertTrue(held.verdict != Verdict.CLEAN && held.runes, held.toString())
    }

    /**
     * Rule 2 in the check run: ord's rune index never sees dunes, so off Litecoin an ord "nothing here" is no answer. A
     * stored "may carry runes" row on Dogecoin stays as it was (held in Send), where on Litecoin a runes-aware clean
     * answer settles it.
     */
    @Test fun aCleanOffLitecoinDoesNotClearMayCarryRunes(): Unit = runBlocking {
        for(coin in listOf(Coin.DOGECOIN, Coin.PEPECOIN, Coin.JUNKCOIN, Coin.BELLSCOIN, Coin.LITECOIN)) {
            val dir = newDir()
            val cipher = CheckerCipher()
            val chain = FakeChain()
            val a = oldCoin(chain, 50_000)
            val row = InscriptionCheck(a, 0, Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, false, emptyList(),
                com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy.MAY_CARRY_RUNES, FakeChain.block(100), emptyList())
            InscriptionStore(dir, cipher).save(wallet, mapOf("$a:0" to row))
            val ord = FakeIndexer { asked -> asked.associateWith { nothing(it) } }
            val result = checker(dir, cipher).check(wallet, coin, Network.MAINNET, chain, listOf(Utxo(a, 0, 100, 50_000)),
                indexer = ord, indexerSource = InscriptionCheck.Source.YOUR_OWN).getValue("$a:0")
            val state = coinState(result, null, localMayCarry = false, hasAnyResults = true, runeLike = true, runesIndexed = false)
            if(coin != Coin.LITECOIN) {
                assertEquals(com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy.MAY_CARRY_RUNES, result.reason, "$coin")
                assertEquals(CoinState.HELD, state, "$coin")
                assertEquals(CoinState.HELD, coinState(result, null, localMayCarry = false, hasAnyResults = true,
                    runeLike = com.sparrowwallet.mobile.crypto.track.ChainRules.hasRuneLikeTokens(coin), runesIndexed = false), "$coin in Send")
                assertNull(result.runeList, "an answer that is not used keeps no rune list")
            } else {
                assertEquals(Verdict.CLEAN, result.verdict)
                assertEquals(InscriptionCheck.Source.YOUR_OWN, result.source)
            }
        }
    }

    /** Each row keeps the listed coin's value and address position, from the coin list. */
    @Test fun coinValueAndPositionAreStored(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 60_000)
        val dir = newDir()
        val cipher = CheckerCipher()
        val result = checker(dir, cipher).withIndexer(chain, listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 60_000)), null, null,
            positions = mapOf("$a:0" to (0 to 4), "$b:0" to (1 to 2)))
        assertEquals(50_000L, result.getValue("$a:0").value)
        assertEquals(0, result.getValue("$a:0").chain)
        assertEquals(4, result.getValue("$a:0").index)
        assertEquals(60_000L, result.getValue("$b:0").value)
        assertEquals(1, result.getValue("$b:0").chain)
        assertEquals(2, result.getValue("$b:0").index)
        assertEquals(result, InscriptionStore(dir, cipher).load(wallet))
    }

    /** A results file written by the app before these fields existed still loads, and its rows decide as before. */
    @Test fun oldFilesWithoutTheNewFieldsLoad() {
        val dir = newDir()
        val cipher = CheckerCipher()
        val txid = "11".repeat(32)
        val old = """{"v":1,"checks":{"$txid:0":{"txid":"$txid","vout":0,"verdict":"CLEAN","source":"BUILT_IN","runes":false,""" +
            """"found":[],"reason":null,"blockHash":"${"ab".repeat(32)}","open":[]}},"blocks":{"100":"${"ab".repeat(32)}"}}"""
        File(dir, "My Wallet.bin").writeBytes(cipher.seal(old.encodeToByteArray()))
        val row = InscriptionStore(dir, cipher).load(wallet).getValue("$txid:0")
        assertEquals(InscriptionCheck(txid, 0, Verdict.CLEAN, InscriptionCheck.Source.BUILT_IN, false, emptyList(), null, "ab".repeat(32), emptyList()), row)
        assertNull(row.runeList)
        assertNull(row.value)
        assertNull(row.chain)
        assertNull(row.index)
        assertTrue(InscriptionStore(dir, cipher).loadMarks(wallet).isEmpty(), "no kept marks in an old file")
    }

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

    /** Residual R3: a wallet deleted while the worker lists its coins (before the check begins) is not written back. */
    @Test fun aWalletForgottenWhileItsCoinsAreListedIsNotWrittenBack(): Unit = runBlocking {
        val dir = newDir()
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val checker = checker(dir, CheckerCipher())
        val ran = checker.tryRun(wallet) {
            //the address walk, during which the user deletes the wallet
            checker.forget(wallet)
            checker.store.delete(wallet)
            checker.ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))
        }
        assertTrue(ran != null)
        assertFalse(File(dir, "My Wallet.bin").exists())
    }

    /** Residual R3: the same during the block-hash phase, before the trace. */
    @Test fun aWalletForgottenWhileItsBlocksAreAskedIsNotWrittenBack(): Unit = runBlocking {
        val dir = newDir()
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val checker = checker(dir, CheckerCipher())
        chain.beforeBlock = { checker.forget(wallet); checker.store.delete(wallet) }
        checker.ltc(chain, listOf(Utxo(a, 0, 100, 50_000)))
        assertFalse(File(dir, "My Wallet.bin").exists())
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
        //near the tip, so its block is asked every run (a deep one is kept with the results)
        val a = chain.add(tx(listOf("aa".repeat(32) to 0), listOf(50_000)), 950)
        val utxos = listOf(Utxo(a, 0, 950, 50_000))
        val dir = newDir()
        val cipher = CheckerCipher()
        checker(dir, cipher).ltc(chain, utxos)
        val bytes = File(dir, "My Wallet.bin").readBytes()
        chain.blockHashRateLimited = true
        chain.rawCalls = 0
        val checker = checker(dir, cipher)
        assertFailsWith<com.sparrowwallet.mobile.electrum.RateLimitedException> { checker.ltc(chain, utxos) }
        assertEquals(0, chain.rawCalls)
        assertTrue(bytes.contentEquals(File(dir, "My Wallet.bin").readBytes()))
    }

    /**
     * Review I3: a block more than 100 below the tip is kept with the results, so a later run whose rows all stand asks
     * the server nothing for it. A shallower one is asked again each run.
     */
    @Test fun deepBlockHashesAreKeptWithTheResults(): Unit = runBlocking {
        val chain = FakeChain()
        val deep = oldCoin(chain, 50_000)
        val shallow = chain.add(tx(listOf("bb".repeat(32) to 0), listOf(60_000)), 950)
        chain.heights[shallow] = 950
        val utxos = listOf(Utxo(deep, 0, 100, 50_000), Utxo(shallow, 0, 950, 60_000))
        val dir = newDir()
        val cipher = CheckerCipher()
        checker(dir, cipher).ltc(chain, utxos)
        assertEquals(2, chain.blockCalls)
        assertEquals(mapOf(100 to FakeChain.block(100)), InscriptionStore(dir, cipher).loadBlocks(wallet))

        chain.blockCalls = 0
        chain.rawCalls = 0
        checker(dir, cipher).ltc(chain, utxos)
        assertEquals(1, chain.blockCalls, "only the shallow block")
        assertEquals(0, chain.rawCalls)

        //only the deep coin left: no block call at all, and no tip read
        chain.blockCalls = 0
        chain.tipCalls = 0
        checker(dir, cipher).ltc(chain, utxos.take(1))
        assertEquals(0, chain.blockCalls)
        assertEquals(0, chain.tipCalls)
        assertEquals(0, chain.rawCalls)
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

    private fun runesOn(outpoint: String) =
        IndexerOutput(outpoint, true, emptyList(), mapOf("UNCOMMON•GOODS" to RuneBalance("5", 0, null)), false)

    /** Send's verdict for a coin from its row alone: no indexer answer at Send, the value rule not flagging it. */
    private suspend fun sendState(row: InscriptionCheck, coin: Coin = Coin.LITECOIN): CoinState =
        classifyCoins(listOf(row.outpoint), mapOf(row.outpoint to row), covered = true,
            com.sparrowwallet.mobile.crypto.track.ChainRules.hasRuneLikeTokens(coin), false, { null }, { false }).state(row.outpoint)

    private val timeUpReason = com.sparrowwallet.mobile.electrum.inscriptions.TrackerRun.TIME_UP

    /**
     * Review finding 1: an indexer's runes on a coin are kept when a later run has no indexed answer for it (indexer
     * down, Built-in, a run cut short), so Send keeps holding it; only a runes-aware indexer's "nothing" on Litecoin
     * clears the mark.
     */
    @Test fun anIndexersRunesMarkStaysUntilAnIndexerClearsIt(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val op = "$a:0"
        val utxos = listOf(Utxo(a, 0, 100, 100_000))
        val runes = runesOn(op).runes
        val first = checker(dir, cipher).withIndexer(chain, utxos, FakeIndexer { asked -> asked.associateWith { runesOn(it) } }).getValue(op)
        assertEquals(Verdict.CLEAN, first.verdict)
        assertTrue(first.runes)
        assertEquals(CoinState.HELD, sendState(first))

        val down = checker(dir, cipher).withIndexer(chain, utxos, FakeIndexer { throw IOException("down") }).getValue(op)
        assertTrue(down.runes, "indexer down: $down")
        assertEquals(runes, down.runeList)
        assertEquals(CoinState.HELD, sendState(down))

        val builtIn = checker(dir, cipher).withIndexer(chain, utxos, null, null).getValue(op)
        assertTrue(builtIn.runes, "switched to Built-in: $builtIn")
        assertEquals(CoinState.HELD, sendState(builtIn))

        //the branch that makes no server call (time or budget gone) writes a fresh held row: the mark stays on it
        val cut = checker(dir, cipher).check(wallet, Coin.LITECOIN, Network.MAINNET, chain, utxos, timeUp = { true }).getValue(op)
        assertEquals(timeUpReason, cut.reason)
        assertTrue(cut.runes, "cut short: $cut")
        assertEquals(CoinState.HELD, sendState(cut))

        //an indexer that cannot vouch for "nothing" (no rune index) does not clear it
        val noRuneIndex = FakeIndexer { asked -> asked.associateWith { nothing(it) } }.apply { runesAware = false }
        assertTrue(checker(dir, cipher).withIndexer(chain, utxos, noRuneIndex).getValue(op).runes)

        //Litecoin's runes-aware ord saying nothing clears it
        val cleared = checker(dir, cipher).withIndexer(chain, utxos, FakeIndexer { asked -> asked.associateWith { nothing(it) } }).getValue(op)
        assertFalse(cleared.runes, cleared.toString())
        assertEquals(CoinState.CLEAN, sendState(cleared))
    }

    /** Off Litecoin the mark stays until the coin is spent: no indexer there can vouch for "nothing". */
    @Test fun aMarkOffLitecoinStaysUntilTheCoinIsSpent(): Unit = runBlocking {
        for(coin in listOf(Coin.DOGECOIN, Coin.BELLSCOIN)) {
            val dir = newDir()
            val cipher = CheckerCipher()
            val chain = FakeChain()
            val a = oldCoin(chain, 100_000)
            val utxos = listOf(Utxo(a, 0, 100, 100_000))
            suspend fun run(indexer: InscriptionIndexer?) = checker(dir, cipher).check(wallet, coin, Network.MAINNET, chain, utxos,
                indexer = indexer, indexerSource = indexer?.let { InscriptionCheck.Source.YOUR_OWN }).getValue("$a:0")
            assertTrue(run(FakeIndexer { asked -> asked.associateWith { runesOn(it) } }).runes, "$coin")
            val after = run(FakeIndexer { asked -> asked.associateWith { nothing(it) } })
            assertTrue(after.runes, "$coin: $after")
            assertEquals(CoinState.HELD, sendState(after, coin))
            assertTrue(run(null).runes)
        }
    }

    /** The over-cap mark stays on the next run without an answer. */
    @Test fun theOverCapMarkStays(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val b = oldCoin(chain, 60_000)
        val utxos = listOf(Utxo(b, 0, 100, 60_000))
        val unreadable = (0 until 150).map { "${"cd".repeat(32)}i$it" }
        val outage = FakeIndexer { asked -> asked.associateWith { IndexerOutput(it, true, unreadable, emptyMap(), false) } }
        assertTrue(checker(dir, cipher).withIndexer(chain, utxos, outage).getValue("$b:0").runes)
        val next = checker(dir, cipher).withIndexer(chain, utxos, null, null).getValue("$b:0")
        assertTrue(next.runes, next.toString())
        assertEquals(CoinState.HELD, sendState(next))
    }

    /**
     * An indexer naming an inscription the chain does not show (inscriptions only, no runes): the coin is held as
     * carrying, in this run and in the next one without an answer.
     */
    @Test fun anInscriptionsOnlyDisagreementHoldsAndStays(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val utxos = listOf(Utxo(a, 0, 100, 100_000))
        val names = FakeIndexer { asked -> asked.associateWith { IndexerOutput(it, true, listOf("${a}i0"), emptyMap(), false) } }
        val first = checker(dir, cipher).withIndexer(chain, utxos, names).getValue("$a:0")
        assertEquals(Verdict.UNCHECKED, first.verdict)
        assertTrue(first.reason!!.startsWith("Indexer and chain disagree"))
        assertEquals(CoinState.HELD, sendState(first))
        val next = checker(dir, cipher).withIndexer(chain, utxos, null, null).getValue("$a:0")
        assertEquals(CoinState.HELD, sendState(next), next.toString())
    }

    /** Review finding 7: a row says an indexer answered only when an answer was used for it. */
    @Test fun theSourceIsAnIndexerOnlyWhenItsAnswerWasUsed(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        //Dogecoin: an ord "nothing" is not used; a run cut short then writes a held row that no indexer answered for
        val ord = FakeIndexer { asked -> asked.associateWith { nothing(it) } }
        val cut = checker(newDir(), CheckerCipher()).check(wallet, Coin.DOGECOIN, Network.MAINNET, chain, listOf(Utxo(a, 0, 100, 50_000)),
            timeUp = { true }, indexer = ord, indexerSource = InscriptionCheck.Source.YOUR_OWN).getValue("$a:0")
        assertEquals(InscriptionCheck.Source.BUILT_IN, cut.source, cut.toString())
        //a stored row an indexer decided keeps its source while it stands
        val dir = newDir()
        val cipher = CheckerCipher()
        val coins = storedInscribed(dir, cipher, chain, 1)
        val kept = checker(dir, cipher).withIndexer(chain, listOf(Utxo(coins[0], 0, 100, 1_000)), null, null).getValue("${coins[0]}:0")
        assertEquals(InscriptionCheck.Source.PUBLIC, kept.source)
    }

    /** Review finding 3: a settings change mid-run closes the run's reader; the calls after it are refused, never Clean. */
    @Test fun aSettingsChangeClosesTheRunsReader(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val coins = storedInscribed(dir, cipher, chain, 3)
        val readers = IndexerReaders()
        val indexer = FakeIndexer { asked ->
            readers.clear()
            asked.associateWith { IndexerOutput(it, true, listOf(it.substringBefore(':') + "i0"), emptyMap(), false) }
        }
        val listed = coins.mapIndexed { i, txid -> com.sparrowwallet.mobile.electrum.WalletScanner.ChainUtxo(Utxo(txid, 0, 100, 1_000), 0, i) }
        val result = checkWithIndexer(checker(dir, cipher), wallet, Coin.LITECOIN, Network.MAINNET, chain, listed, InscriptionSetting.Public,
            null, airGapped = false, readers = readers, open = { _, _, _, _ -> indexer })
        assertTrue(indexer.closed)
        assertTrue(indexer.inscriptionsAsked.isEmpty(), "no number asked of a closed reader")
        assertEquals(1, indexer.refused)
        assertTrue(result.values.all { it.verdict == Verdict.INSCRIBED && it.found.single().number == null })
    }

    /** Review finding 8: the check run opens only the setting's indexer, with the coin's proxy, and closes it after. */
    @Test fun theCheckRunOpensOnlyTheSettingsIndexer(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val listed = listOf(com.sparrowwallet.mobile.electrum.WalletScanner.ChainUtxo(Utxo(a, 0, 100, 50_000), 1, 7))
        val tor = com.sparrowwallet.mobile.electrum.SocksProxy("127.0.0.1", 9050)
        val opened = ArrayList<String>()
        val made = ArrayList<FakeIndexer>()
        val open: suspend (String, Coin, Network, com.sparrowwallet.mobile.electrum.SocksProxy?) -> InscriptionIndexer =
            { url, _, _, proxy -> opened.add("$url $proxy"); FakeIndexer { asked -> asked.associateWith { nothing(it) } }.also { made.add(it) } }
        val readers = IndexerReaders()
        val unanswered = InscriptionSetting.parse(null, hasPublic = true, defaultChoice = null)
        for((setting, airGapped) in listOf(InscriptionSetting.BuiltIn to false, unanswered to false, InscriptionSetting.Public to true)) {
            val rows = checkWithIndexer(checker(newDir(), CheckerCipher()), wallet, Coin.LITECOIN, Network.MAINNET, chain, listed, setting,
                tor, airGapped, readers, open = open)
            assertEquals(InscriptionCheck.Source.BUILT_IN, rows.getValue("$a:0").source)
        }
        assertTrue(opened.isEmpty())
        val rows = checkWithIndexer(checker(newDir(), CheckerCipher()), wallet, Coin.LITECOIN, Network.MAINNET, chain, listed,
            InscriptionSetting.Public, tor, airGapped = false, readers = readers, open = open)
        assertEquals(listOf("https://ordinalslite.com $tor"), opened)
        assertEquals(InscriptionCheck.Source.PUBLIC, rows.getValue("$a:0").source)
        assertEquals(1 to 7, rows.getValue("$a:0").let { it.chain to it.index })
        assertTrue(made.single().closed, "closed after the run")
    }

    /** Review finding 4: the indexer's open and its outputs call stop when the run's time is up (answers: none). */
    @Test fun theIndexerStopsWhenTimeIsUp(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val stuck = FakeIndexer { kotlinx.coroutines.awaitCancellation() }
        val checker = checker(newDir(), CheckerCipher())
        val result = kotlinx.coroutines.withTimeout(10_000) {
            checker.check(wallet, Coin.LITECOIN, Network.MAINNET, chain, listOf(Utxo(a, 0, 100, 50_000)),
                timeUp = { stuck.asked.isNotEmpty() }, indexer = stuck, indexerSource = InscriptionCheck.Source.PUBLIC)
        }
        assertEquals(InscriptionCheck.Source.BUILT_IN, result.getValue("$a:0").source)
        assertTrue(checker.status(wallet).value!!.indexerUnreachable)

        var called = false
        val picked = kotlinx.coroutines.withTimeout(10_000) {
            openCheckIndexer(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, airGapped = false, timeUp = { called }) { _, _, _, _ ->
                called = true
                kotlinx.coroutines.awaitCancellation()
            }
        }
        assertNull(picked.reader)
        var opens = 0
        assertNull(openCheckIndexer(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, airGapped = false, timeUp = { true }) { _, _, _, _ ->
            opens++; FakeIndexer { emptyMap() }
        }.reader)
        assertEquals(0, opens, "no open once time is up")
    }

    /** Review finding 5: number lookups run outside the chain's lock (another wallet's check is not held up) and have a deadline. */
    @Test fun numbersAreFilledOutsideTheChainLockAndHaveADeadline(): Unit = runBlocking {
        val dirA = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val coinsA = storedInscribed(dirA, cipher, chain, 2)
        val checker = InscriptionChecker(InscriptionStore(dirA, cipher))
        val release = CompletableDeferred<Unit>()
        val slow = FakeIndexer { asked -> asked.associateWith { IndexerOutput(it, true, listOf(it.substringBefore(':') + "i0"), emptyMap(), false) } }
        slow.onInscription = { release.await() }
        val first = async(kotlinx.coroutines.Dispatchers.Default) { checker.withIndexer(chain, coinsA.map { Utxo(it, 0, 100, 1_000) }, slow) }
        kotlinx.coroutines.withTimeout(10_000) { while(slow.inscriptionsAsked.isEmpty()) kotlinx.coroutines.delay(10) }
        //another wallet on the same chain finishes while the first one waits on its numbers
        val b = oldCoin(chain, 70_000)
        val other = kotlinx.coroutines.withTimeout(10_000) {
            checker.check("Other", Coin.LITECOIN, Network.MAINNET, chain, listOf(Utxo(b, 0, 100, 70_000)))
        }
        assertEquals(Verdict.CLEAN, other.getValue("$b:0").verdict)
        release.complete(Unit)
        assertTrue(first.await().values.all { it.found.single().number != null })

        //a lookup that never answers is given up at the deadline, and the number stays unset
        val dir = newDir()
        val coins = storedInscribed(dir, cipher, chain, 2)
        val stuck = FakeIndexer { asked -> asked.associateWith { IndexerOutput(it, true, listOf(it.substringBefore(':') + "i0"), emptyMap(), false) } }
        stuck.onInscription = { kotlinx.coroutines.awaitCancellation() }
        val quick = InscriptionChecker(InscriptionStore(dir, cipher), numberFillDeadlineMs = 200)
        val rows = kotlinx.coroutines.withTimeout(10_000) { quick.withIndexer(chain, coins.map { Utxo(it, 0, 100, 1_000) }, stuck) }
        assertEquals(1, stuck.inscriptionsAsked.size)
        assertTrue(rows.values.all { it.found.single().number == null && it.verdict == Verdict.INSCRIBED })
    }

    /**
     * Re-review I1: the mark does not depend on the block. The same transaction re-mined in another block (a reorg), or a
     * server reporting another hash for the height, keeps the coin held with no indexer: the txid fixes what it carries.
     */
    @Test fun theMarkSurvivesAReorgAndAServerReportingAnotherHash(): Unit = runBlocking {
        for(lying in listOf(false, true)) {
            val dir = newDir()
            val cipher = CheckerCipher()
            val chain = FakeChain()
            //near the tip, so its block is asked every run
            val a = chain.add(tx(listOf("aa".repeat(32) to 0), listOf(100_000L)), 950)
            val op = "$a:0"
            val first = checker(dir, cipher).withIndexer(chain, listOf(Utxo(a, 0, 950, 100_000)),
                FakeIndexer { asked -> asked.associateWith { runesOn(it) } }).getValue(op)
            assertTrue(first.runes)
            val height = if(lying) {
                chain.blockHashShift = 3
                950
            } else {
                chain.heights[a] = 951
                951
            }
            val next = checker(dir, cipher).withIndexer(chain, listOf(Utxo(a, 0, height, 100_000)), null, null).getValue(op)
            assertTrue(next.blockHash != first.blockHash, "the block changed: $next")
            assertTrue(next.runes, "lying=$lying: $next")
            assertEquals(CoinState.HELD, sendState(next), "lying=$lying")
        }
    }

    /**
     * Re-review I2: a marked coin left out of one listing (an Electrum server hides outputs spent in its mempool) keeps its
     * mark, kept apart from the coins, and gets it back when it is listed again. The kept mark is not a coin anywhere.
     */
    @Test fun aMarkOutlivesTheCoinsAbsenceFromAListing(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val b = oldCoin(chain, 60_000)
        val both = listOf(Utxo(a, 0, 100, 100_000), Utxo(b, 0, 100, 60_000))
        checker(dir, cipher).withIndexer(chain, both, FakeIndexer { asked -> asked.associateWith { if(it.startsWith(a)) runesOn(it) else nothing(it) } })

        val checker = checker(dir, cipher)
        val seen = ArrayList<Pair<Int, Int>>()
        val hidden = checker.check(wallet, Coin.LITECOIN, Network.MAINNET, chain, both.drop(1), progress = { done, total -> seen.add(done to total) })
        assertEquals(setOf("$b:0"), hidden.keys, "a kept mark is not a coin")
        assertEquals(setOf("$b:0"), checker.results(wallet).keys)
        assertEquals(setOf("$b:0"), checker.storedResults(wallet)!!.keys)
        assertEquals(setOf("$b:0"), InscriptionStore(dir, cipher).load(wallet).keys)
        assertTrue(seen.all { it.second == 1 }, "progress counts listed coins only: $seen")
        assertEquals(1, checker.status(wallet).value!!.total)
        assertEquals(setOf("$a:0"), InscriptionStore(dir, cipher).loadMarks(wallet).keys)

        //it comes back: no indexer this run, still held
        val back = checker(dir, cipher).withIndexer(chain, both, null, null)
        assertTrue(back.getValue("$a:0").runes, back.toString())
        assertEquals(CoinState.HELD, sendState(back.getValue("$a:0")))
        assertTrue(InscriptionStore(dir, cipher).loadMarks(wallet).isEmpty(), "the mark is on its row again")
    }

    /** Re-review I2: kept marks are dropped only by their bound: unlisted for more than 30 days, or past the newest 1,000. */
    @Test fun keptMarksAreBounded(): Unit = runBlocking {
        val day = 24L * 60 * 60 * 1000
        var clock = 1_000_000_000_000L
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val b = oldCoin(chain, 60_000)
        fun at() = InscriptionChecker(InscriptionStore(dir, cipher), now = { clock })
        at().withIndexer(chain, listOf(Utxo(a, 0, 100, 100_000)), FakeIndexer { asked -> asked.associateWith { runesOn(it) } })
        val onlyB = listOf(Utxo(b, 0, 100, 60_000))
        fun marks() = InscriptionStore(dir, cipher).loadMarks(wallet)
        //runs while it is missing: 1
        at().withIndexer(chain, onlyB, null, null)
        //a clock jump alone (far past 30 days, after 2 runs) drops nothing
        clock += 400 * day
        at().withIndexer(chain, onlyB, null, null)
        assertEquals(setOf("$a:0"), marks().keys, "a clock jump alone")
        clock -= 400 * day
        //runs alone (10 and more, within 30 days) drop nothing
        repeat(10) { at().withIndexer(chain, onlyB, null, null) }
        assertEquals(setOf("$a:0"), marks().keys, "runs alone")
        assertTrue(marks().getValue("$a:0").missingRuns >= InscriptionChecker.MIN_MISSING_RUNS)
        //both: dropped
        clock += 31 * day
        at().withIndexer(chain, onlyB, null, null)
        assertTrue(marks().isEmpty(), "31 days and 10 runs: dropped")

        //at most the newest 1,000
        val many = (1..1_005).associate { i -> "%064x:0".format(i) to KeptMark(true, null, clock - i * 1_000L) }
        val store = InscriptionStore(dir, cipher)
        store.save(wallet, store.load(wallet), store.loadBlocks(wallet), many)
        at().withIndexer(chain, onlyB, null, null)
        val kept = InscriptionStore(dir, cipher).loadMarks(wallet)
        assertEquals(1_000, kept.size)
        assertEquals((1..1_000).map { "%064x:0".format(it) }.toSet(), kept.keys, "the newest")

        //ties (the same time) are cut in outpoint order, so the cap keeps the same marks every time
        val tied = (1..1_003).associate { i -> "%064x:0".format(i) to KeptMark(true, null, clock) }
        store.save(wallet, store.load(wallet), store.loadBlocks(wallet), tied.entries.reversed().associate { it.key to it.value })
        at().withIndexer(chain, onlyB, null, null)
        assertEquals((1..1_000).map { "%064x:0".format(it) }.toSet(), InscriptionStore(dir, cipher).loadMarks(wallet).keys)
    }

    /**
     * Re-review M2: the number lookups' deadline is real. A reader whose inscription request is answered by nothing (the
     * server accepts and never replies) is given up at the deadline, not at the socket timeout.
     */
    @Test fun aHungNumberLookupEndsAtTheDeadline(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val coins = storedInscribed(dir, cipher, chain, 1)
        val server = TinyHttp.ord { _, path, body ->
            if(path == "/outputs") {
                kotlinx.serialization.json.Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonArray }
                    .joinToString(",", "[", "]") { o ->
                        val txid = (o as kotlinx.serialization.json.JsonPrimitive).content.take(64)
                        """{"indexed":true,"inscriptions":["${txid}i0"],"runes":{},"spent":false,"transaction":"$txid"}"""
                    }
            } else null
        }
        try {
            val reader = com.sparrowwallet.mobile.electrum.indexer.Indexers.open(server.url, Coin.LITECOIN, Network.MAINNET, null)
            val quick = InscriptionChecker(InscriptionStore(dir, cipher), numberFillDeadlineMs = 1_000)
            val started = System.nanoTime()
            val rows = quick.withIndexer(chain, coins.map { Utxo(it, 0, 100, 1_000) }, reader)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < 6_000, "took $tookMs ms")
            assertTrue(rows.values.all { it.verdict == Verdict.INSCRIBED && it.found.single().number == null })
            reader.close()
        } finally {
            server.close()
        }
    }

    /**
     * Re-check item 1: a coin whose mark was kept while it was missing from the check run's listing, and that is listed
     * again at Send before the next run, is held by that mark (not Unchecked for want of a row).
     */
    @Test fun sendHoldsACoinByItsKeptMark(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val b = oldCoin(chain, 60_000)
        val both = listOf(Utxo(a, 0, 100, 100_000), Utxo(b, 0, 100, 60_000))
        checker(dir, cipher).withIndexer(chain, both, FakeIndexer { asked -> asked.associateWith { if(it.startsWith(a)) runesOn(it) else nothing(it) } })
        val checker = checker(dir, cipher)
        checker.withIndexer(chain, both.drop(1), null, null)
        assertTrue("$a:0" !in checker.storedResults(wallet)!!)
        val ready = both.associateBy { "${it.txHash}:${it.txPos}" }
        //Final review M1: rows and marks come from one read
        val snapshot = checker.sendSnapshot(wallet)!!
        assertEquals(checker.storedResults(wallet), snapshot.rows)
        assertEquals(checker.keptMarks(wallet), snapshot.marks)
        val states = classifySend(ready, snapshot.rows, Coin.LITECOIN, Network.MAINNET, InscriptionSetting.BuiltIn, null,
            IndexerReaders(), false, { false }, { false }, marks = snapshot.marks)
        assertEquals(CoinState.HELD, states.state("$a:0"))
        assertEquals(CoinState.CLEAN, states.state("$b:0"))
    }

    /**
     * Re-check item 4: a run whose setting and proxy were read before a settings change (the generation it was given is
     * stale) opens nothing, and counts the indexer as unreachable for that run.
     */
    @Test fun aRunGivenAStaleGenerationOpensNothing(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val listed = listOf(com.sparrowwallet.mobile.electrum.WalletScanner.ChainUtxo(Utxo(a, 0, 100, 50_000), 0, 0))
        val readers = IndexerReaders()
        val stale = readers.generation
        readers.clear()
        var opens = 0
        val checker = checker(newDir(), CheckerCipher())
        val rows = checkWithIndexer(checker, wallet, Coin.LITECOIN, Network.MAINNET, chain, listed, InscriptionSetting.Public, null,
            airGapped = false, readers = readers, generation = stale, open = { _, _, _, _ -> opens++; FakeIndexer { emptyMap() } })
        assertEquals(0, opens)
        assertEquals(InscriptionCheck.Source.BUILT_IN, rows.getValue("$a:0").source)
        assertTrue(checker.status(wallet).value!!.indexerUnreachable)
    }

    // ---- Check deeper ----

    /** A coin 600 steps above Litecoin's first inscription: the default budget leaves it Unchecked, the deeper one traces it. */
    private fun longCoin(chain: FakeChain, n: Int, depth: Int = 600): String {
        chain.tip = 2_700_000
        var previous = chain.add(tx(listOf("cc".repeat(32) to n), listOf(10_000L)), 2_000_000)
        for(i in 0 until depth) {
            previous = chain.add(tx(listOf(previous to 0), listOf(10_000L)), 2_500_000 + i)
        }
        return previous
    }

    private class Deep(val chain: FakeChain, val long: String, val plain: String, val utxos: List<Utxo>) {
        val outpoint = "$long:0"
    }

    /** A wallet whose first run left its long coin Unchecked for the budget and its plain coin Clean. */
    private suspend fun deep(checker: InscriptionChecker): Deep {
        val chain = FakeChain()
        val long = longCoin(chain, 1)
        val plain = oldCoin(chain, 50_000)
        val d = Deep(chain, long, plain, listOf(Utxo(long, 0, 2_500_599, 10_000), Utxo(plain, 0, 100, 50_000)))
        val first = checker.ltc(chain, d.utxos)
        assertEquals(com.sparrowwallet.mobile.crypto.track.InscriptionTracer.BUDGET_USED, first.getValue(d.outpoint).reason)
        assertEquals(Verdict.CLEAN, first.getValue("$plain:0").verdict)
        return d
    }

    private suspend fun InscriptionChecker.queueEmpties() =
        kotlinx.coroutines.withTimeout(30_000) { deeperQueued(wallet).first { it.isEmpty() } }

    /** Holds the wallet's guard with a run that waits for [release], as Home's check would while it runs. */
    private suspend fun InscriptionChecker.busy(release: CompletableDeferred<Unit>, then: suspend () -> Map<String, InscriptionCheck> = { emptyMap() }): Job {
        val started = CompletableDeferred<Unit>()
        val job = assertNotNull(start(wallet) { started.complete(Unit); release.await(); then() })
        started.await()
        return job
    }

    @Test fun deeperRequestStartsARunWhenIdle(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val asked = java.util.Collections.synchronizedList(ArrayList<Set<String>>())
        val shown = java.util.Collections.synchronizedList(ArrayList<Set<String>>())
        val job = checker.requestDeeper(wallet, d.outpoint) { deeper ->
            asked.add(deeper)
            shown.add(checker.deeperQueued(wallet).value)
            checker.ltc(d.chain, d.utxos, deeper)
        }
        assertNotNull(job, "a run starts at once").join()
        assertEquals(listOf(setOf(d.outpoint)), asked)
        assertEquals(listOf(setOf(d.outpoint)), shown, "the coin shows as asked for while its run goes")
        assertEquals(Verdict.CLEAN, checker.results(wallet).getValue(d.outpoint).verdict, "traced with the deeper budget")
        checker.queueEmpties()
    }

    /**
     * A request while a check runs waits for it, then a follow-up run traces the coin with the deeper budget; the first
     * run's results stand, and the follow-up starts only once the guard is free.
     */
    @Test fun deeperRequestIsQueuedBehindARunningCheck(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val checker = checker(dir, cipher)
        val d = deep(checker)
        val release = CompletableDeferred<Unit>()
        var current: Map<String, InscriptionCheck>? = null
        val running = checker.busy(release) { checker.ltc(d.chain, d.utxos).also { current = it } }
        val asked = java.util.Collections.synchronizedList(ArrayList<Set<String>>())
        val followed = CompletableDeferred<Map<String, InscriptionCheck>>()
        val queued = checker.requestDeeper(wallet, d.outpoint) { deeper ->
            asked.add(deeper)
            checker.ltc(d.chain, d.utxos, deeper).also { followed.complete(it) }
        }
        assertNull(queued, "queued behind the running check, not started")
        assertEquals(setOf(d.outpoint), checker.deeperQueued(wallet).value)
        assertTrue(asked.isEmpty())
        release.complete(Unit)
        running.join()
        val after = kotlinx.coroutines.withTimeout(30_000) { followed.await() }
        assertEquals(listOf(setOf(d.outpoint)), asked, "one follow-up run, with the queued coin as deeper")
        assertEquals(Verdict.UNCHECKED, current!!.getValue(d.outpoint).verdict, "the running check did not trace it deeper")
        assertEquals(Verdict.CLEAN, after.getValue(d.outpoint).verdict)
        assertEquals(current!!.getValue("${d.plain}:0"), after.getValue("${d.plain}:0"), "the first run's result stands")
        assertEquals(after, InscriptionStore(dir, cipher).load(wallet))
        checker.queueEmpties()
    }

    @Test fun aQueuedCoinThatWasSpentIsDropped(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val release = CompletableDeferred<Unit>()
        //The running check lists the coins again: the long coin has been spent since
        val running = checker.busy(release) { checker.ltc(d.chain, d.utxos.filter { it.txHash != d.long }) }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        assertNull(checker.requestDeeper(wallet, d.outpoint) { calls.incrementAndGet(); emptyMap() })
        release.complete(Unit)
        running.join()
        checker.queueEmpties()
        assertEquals(0, calls.get(), "no run for a coin no longer unspent")
        assertFalse(d.outpoint in checker.results(wallet))
    }

    /** The check a request waits behind may fail, be cancelled or run out of time: the follow-up still runs, once. */
    @Test fun theFollowUpRunsHoweverTheRunningCheckEnds(): Unit = runBlocking {
        for(ending in listOf("fails", "cancelled", "time up")) {
            val checker = checker(newDir(), CheckerCipher())
            val d = deep(checker)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            //The background worker's run, which holds the guard through tryRun
            val worker = async(kotlinx.coroutines.Dispatchers.IO) {
                checker.tryRun(wallet) {
                    started.complete(Unit)
                    release.await()
                    when(ending) {
                        "fails" -> throw IOException("server down")
                        "cancelled" -> kotlinx.coroutines.awaitCancellation()
                        else -> checker.ltc(d.chain, d.utxos, timeUp = { true })
                    }
                }
            }
            started.await()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val followed = CompletableDeferred<Set<String>>()
            assertNull(checker.requestDeeper(wallet, d.outpoint) { deeper -> calls.incrementAndGet(); followed.complete(deeper); emptyMap() }, ending)
            if(ending == "cancelled") worker.cancel() else release.complete(Unit)
            runCatching { worker.await() }
            assertEquals(setOf(d.outpoint), kotlinx.coroutines.withTimeout(30_000) { followed.await() }, ending)
            checker.queueEmpties()
            assertEquals(1, calls.get(), ending)
        }
    }

    /** One attempt per request: a deeper run that fails is not run again by itself, and asking again is a new attempt. */
    @Test fun aDeeperRunThatFailsIsNotRetried(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        checker.requestDeeper(wallet, d.outpoint) { calls.incrementAndGet(); throw IOException("server down") }!!.join()
        checker.queueEmpties()
        //Another run ends: nothing is waiting, so nothing follows it
        checker.start(wallet) { emptyMap() }!!.join()
        assertEquals(1, calls.get())
        assertTrue(checker.deeperQueued(wallet).value.isEmpty())
        checker.requestDeeper(wallet, d.outpoint) { calls.incrementAndGet(); emptyMap() }!!.join()
        assertEquals(2, calls.get())
    }

    @Test fun aDoubleTapGivesOneRun(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val release = CompletableDeferred<Unit>()
        val running = checker.busy(release)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val followed = CompletableDeferred<Unit>()
        repeat(2) { assertNull(checker.requestDeeper(wallet, d.outpoint) { calls.incrementAndGet(); followed.complete(Unit); emptyMap() }) }
        release.complete(Unit)
        running.join()
        kotlinx.coroutines.withTimeout(30_000) { followed.await() }
        checker.queueEmpties()
        assertEquals(1, calls.get())
    }

    /** A deleted wallet's queue goes with it: no follow-up runs for it. */
    @Test fun forgetEmptiesTheQueue(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val running = checker.busy(CompletableDeferred())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        assertNull(checker.requestDeeper(wallet, d.outpoint) { calls.incrementAndGet(); emptyMap() })
        checker.forget(wallet)
        assertTrue(checker.deeperQueued(wallet).value.isEmpty())
        running.join()
        assertTrue(running.isCancelled)
        assertNotNull(checker.start(wallet) { emptyMap() }?.also { it.join() }, "no follow-up holds the guard")
        assertEquals(0, calls.get())
    }

    /** A deeper run goes through the same path as every run: on Built-in it opens no indexer, and the coin is traced deeper. */
    @Test fun aDeeperCheckOnBuiltInOpensNoIndexer(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val listed = d.utxos.mapIndexed { i, u -> com.sparrowwallet.mobile.electrum.WalletScanner.ChainUtxo(u, 0, i) }
        var opens = 0
        val job = checker.requestDeeper(wallet, d.outpoint) { deeper ->
            checkWithIndexer(checker, wallet, Coin.LITECOIN, Network.MAINNET, d.chain, listed, InscriptionSetting.BuiltIn, null,
                airGapped = false, readers = IndexerReaders(), deeper = deeper, open = { _, _, _, _ -> opens++; FakeIndexer { emptyMap() } })
        }
        job!!.join()
        assertEquals(0, opens)
        assertEquals(Verdict.CLEAN, checker.results(wallet).getValue(d.outpoint).verdict)
    }

    /** Holds the guard with a deeper run that waits for [release]. */
    private suspend fun InscriptionChecker.busyDeeper(d: Deep, release: CompletableDeferred<Unit>,
                                                      asked: MutableList<Set<String>>): Job {
        val started = CompletableDeferred<Unit>()
        val job = assertNotNull(requestDeeper(wallet, d.outpoint) { deeper -> asked.add(deeper); started.complete(Unit); release.await(); emptyMap() })
        started.await()
        return job
    }

    /** Home's check skipped because a deeper run holds the guard runs once that run ends; skipped twice, it runs once. */
    @Test fun aCheckSkippedByADeeperRunRunsAfterIt(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val release = CompletableDeferred<Unit>()
        val deeper = checker.busyDeeper(d, release, java.util.Collections.synchronizedList(ArrayList()))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val ran = CompletableDeferred<Unit>()
        repeat(2) { assertNull(checker.start(wallet) { calls.incrementAndGet(); ran.complete(Unit); emptyMap() }) }
        release.complete(Unit)
        deeper.join()
        kotlinx.coroutines.withTimeout(30_000) { ran.await() }
        //Let any further run start and end: the guard is free again, and nothing more ran
        kotlinx.coroutines.withTimeout(30_000) { while(checker.start(wallet) { emptyMap() }?.also { it.join() } == null) kotlinx.coroutines.yield() }
        assertEquals(1, calls.get())
    }

    /** A check skipped because another ordinary check runs is not run again: that check covers the coins. */
    @Test fun aCheckSkippedByAnOrdinaryRunIsNotRerun(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val release = CompletableDeferred<Unit>()
        val running = checker.busy(release)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        assertNull(checker.start(wallet) { calls.incrementAndGet(); emptyMap() })
        release.complete(Unit)
        running.join()
        assertNotNull(checker.start(wallet) { emptyMap() }).join()
        assertEquals(0, calls.get())
    }

    /** Review M9: a wallet forgotten after its waiting coins were taken but before their run is launched: the run does nothing. */
    @Test fun aWalletForgottenAsItsDeeperRunIsLaunchedIsNotChecked(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        checker.beforeDeeperLaunch = { checker.forget(wallet) }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        checker.requestDeeper(wallet, d.outpoint) { calls.incrementAndGet(); emptyMap() }?.join()
        assertEquals(0, calls.get())
        assertTrue(checker.deeperQueued(wallet).value.isEmpty())
    }

    /** Review M8: once nothing waits or runs, the queue keeps no runner (nor what it holds). */
    @Test fun aDrainedQueueLetsGoOfItsRunner(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val release = CompletableDeferred<Unit>()
        val running = checker.busy(release)
        assertNull(checker.requestDeeper(wallet, d.outpoint) { emptyMap() })
        assertTrue(checker.holdsDeeperRunner(wallet))
        release.complete(Unit)
        running.join()
        checker.queueEmpties()
        kotlinx.coroutines.withTimeout(30_000) { while(checker.holdsDeeperRunner(wallet)) kotlinx.coroutines.yield() }
        assertFalse(checker.holdsDeeperRunner(wallet))
    }

    /** An indexer's mark on a coin outlives a deeper run that traces it Clean: Send still holds it. */
    @Test fun anIndexerMarkSurvivesADeeperRun(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val chain = FakeChain()
        val long = longCoin(chain, 1)
        val plain = oldCoin(chain, 50_000)
        val utxos = listOf(Utxo(long, 0, 2_500_599, 10_000), Utxo(plain, 0, 100, 50_000))
        val op = "$long:0"
        val indexer = FakeIndexer { asked -> asked.associateWith { if(it == op) runesOn(it) else nothing(it) } }
        val first = checker.check(wallet, Coin.LITECOIN, Network.MAINNET, chain, utxos, indexer = indexer, indexerSource = InscriptionCheck.Source.PUBLIC)
        assertTrue(first.getValue(op).runes)
        //Checked deeper on Built-in: the trace finds nothing, the mark stays
        assertNotNull(checker.requestDeeper(wallet, op) { deeper -> checker.ltc(chain, utxos, deeper) }).join()
        val row = checker.results(wallet).getValue(op)
        assertTrue(row.runes, row.toString())
        assertEquals(CoinState.HELD, sendState(row))
    }

    /** A request that arrives while a deeper run goes gets one further run when it ends. */
    @Test fun aRequestDuringADeeperRunGetsOneFurtherRun(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val release = CompletableDeferred<Unit>()
        val asked = java.util.Collections.synchronizedList(ArrayList<Set<String>>())
        val first = checker.busyDeeper(d, release, asked)
        val plainOutpoint = "${d.plain}:0"
        val followed = CompletableDeferred<Unit>()
        assertNull(checker.requestDeeper(wallet, plainOutpoint) { deeper -> asked.add(deeper); followed.complete(Unit); emptyMap() })
        assertEquals(setOf(d.outpoint, plainOutpoint), checker.deeperQueued(wallet).value)
        release.complete(Unit)
        first.join()
        kotlinx.coroutines.withTimeout(30_000) { followed.await() }
        checker.queueEmpties()
        assertEquals(listOf(setOf(d.outpoint), setOf(plainOutpoint)), asked.toList())
    }

    @Test fun twoQueuedCoinsShareOneRun(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val release = CompletableDeferred<Unit>()
        val running = checker.busy(release)
        val asked = java.util.Collections.synchronizedList(ArrayList<Set<String>>())
        val followed = CompletableDeferred<Unit>()
        for(outpoint in listOf(d.outpoint, "${d.plain}:0")) {
            assertNull(checker.requestDeeper(wallet, outpoint) { deeper -> asked.add(deeper); followed.complete(Unit); emptyMap() })
        }
        release.complete(Unit)
        running.join()
        kotlinx.coroutines.withTimeout(30_000) { followed.await() }
        checker.queueEmpties()
        assertEquals(listOf(setOf(d.outpoint, "${d.plain}:0")), asked.toList())
    }

    @Test fun twoWalletsQueuesAreIndependent(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        checker.check("Other", Coin.LITECOIN, Network.MAINNET, d.chain, d.utxos)
        val release = CompletableDeferred<Unit>()
        val running = checker.busy(release)
        assertNull(checker.requestDeeper(wallet, d.outpoint) { emptyMap() })
        val other = checker.requestDeeper("Other", d.outpoint) { deeper -> checker.check("Other", Coin.LITECOIN, Network.MAINNET, d.chain, d.utxos, deeper) }
        assertNotNull(other, "another wallet's run starts at once").join()
        assertEquals(Verdict.CLEAN, checker.results("Other").getValue(d.outpoint).verdict)
        kotlinx.coroutines.withTimeout(30_000) { checker.deeperQueued("Other").first { it.isEmpty() } }
        assertEquals(setOf(d.outpoint), checker.deeperQueued(wallet).value, "this wallet's request still waits")
        release.complete(Unit)
        running.join()
        checker.queueEmpties()
    }

    /** The repository's gate: air-gapped, an MWEB wallet or an uncovered coin queue nothing (no row stuck on Queued). */
    @Test fun aDeeperRequestIsRefusedWhenAirGappedOrNotCovered(): Unit = runBlocking {
        val checker = checker(newDir(), CheckerCipher())
        val d = deep(checker)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        for((coin, scriptType, airGapped) in listOf(Triple(Coin.LITECOIN, "P2WPKH", true), Triple(Coin.LITECOIN, "MWEB", false),
                Triple(Coin.LUCKYCOIN, "P2PKH", false))) {
            assertFalse(requestDeeperIfAllowed(checker, wallet, coin, scriptType, airGapped, d.outpoint) { calls.incrementAndGet(); emptyMap() })
            assertTrue(checker.deeperQueued(wallet).value.isEmpty())
        }
        assertEquals(0, calls.get())
        assertTrue(requestDeeperIfAllowed(checker, wallet, Coin.LITECOIN, "P2WPKH", false, d.outpoint) { calls.incrementAndGet(); emptyMap() })
        checker.queueEmpties()
        assertEquals(1, calls.get())
    }

    /**
     * Final review I1: an indexer that can only add holds (Nintondo, one coin at a time) is asked only about coins the
     * stored results leave undecided: not a coin already held by a mark or found Inscribed, nor one it answered "unknown"
     * for within [InscriptionChecker.ASKED_FRESH_MS], unless it is new or checked deeper.
     */
    @Test fun aHoldsOnlyIndexerIsAskedOnlyAboutUndecidedStaleCoins(): Unit = runBlocking {
        val hour = 60L * 60 * 1000
        var clock = 1_000_000_000_000L
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val coins = (1..150).map { oldCoin(chain, 10_000L + it) }
        val utxos = coins.map { Utxo(it, 0, 100, 10_000) }
        val marked = coins.take(10).map { "$it:0" }.toSet()
        val nintondo = FakeIndexer { asked -> asked.associateWith { if(it in marked) IndexerOutput(it, true, emptyList(), emptyMap(), true) else IndexerOutput.unknown(it) } }
            .apply { runesAware = false; asksOneAtATime = true }
        suspend fun run(list: List<Utxo>, deeper: Set<String> = emptySet()): List<String> {
            val before = nintondo.asked.size
            InscriptionChecker(InscriptionStore(dir, cipher), now = { clock }).check(wallet, Coin.DOGECOIN, Network.MAINNET, chain, list, deeper,
                indexer = nintondo, indexerSource = InscriptionCheck.Source.PUBLIC)
            return nintondo.asked.drop(before).flatten()
        }
        assertEquals(utxos.map { "${it.txHash}:0" }, run(utxos))
        assertTrue(nintondo.asked.all { it.size == 1 }, "one coin a request")

        clock += hour
        val x = oldCoin(chain, 99_999)
        val withX = utxos + Utxo(x, 0, 100, 99_999)
        assertEquals(listOf("$x:0"), run(withX), "only the new coin")
        val twenty = "${coins[20]}:0"
        assertEquals(listOf(twenty), run(withX, setOf(twenty)), "and a coin checked deeper")

        clock += 5 * hour + 30 * 60 * 1000
        val again = run(withX)
        assertEquals((coins.drop(10).map { "$it:0" } - twenty).toSet(), again.toSet(), "the stale unknown coins only")
        assertEquals(139, again.size)
        val rows = InscriptionChecker(InscriptionStore(dir, cipher)).results(wallet)
        for(op in marked) assertEquals(CoinState.HELD, sendState(rows.getValue(op), Coin.DOGECOIN), op)
    }

    /**
     * Final review I1 (b, d): from a holds-only indexer cut short by its deadline, the carrying answers received before it
     * stand (a hold is always safe) and the run is not "unreachable"; the rest have no answer. A runes-aware reader stays
     * whole-pass: cut short, nothing it said is used.
     */
    @Test fun carryingAnswersBeforeTheDeadlineAreKept(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 60_000)
        val c = oldCoin(chain, 70_000)
        val utxos = listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 60_000), Utxo(c, 0, 100, 70_000))
        val slow = FakeIndexer { asked ->
            asked.associateWith { op -> if(op.startsWith(a)) IndexerOutput(op, true, emptyList(), emptyMap(), true) else kotlinx.coroutines.awaitCancellation() }
        }.apply { runesAware = false; asksOneAtATime = true }
        val checker = InscriptionChecker(InscriptionStore(newDir(), CheckerCipher()), indexerDeadlineMs = 400)
        val rows = kotlinx.coroutines.withTimeout(10_000) {
            checker.check(wallet, Coin.DOGECOIN, Network.MAINNET, chain, utxos, indexer = slow, indexerSource = InscriptionCheck.Source.PUBLIC)
        }
        assertTrue(rows.getValue("$a:0").runes)
        assertEquals(CoinState.HELD, sendState(rows.getValue("$a:0"), Coin.DOGECOIN))
        assertFalse(rows.getValue("$b:0").runes)
        assertFalse(rows.getValue("$c:0").runes)
        assertFalse(checker.status(wallet).value!!.indexerUnreachable, "some answers came")

        val whole = FakeIndexer { asked ->
            asked.associateWith { op -> if(op.startsWith(a)) IndexerOutput(op, true, emptyList(), emptyMap(), true) else kotlinx.coroutines.awaitCancellation() }
        }.apply { asksOneAtATime = true }
        val ltc = InscriptionChecker(InscriptionStore(newDir(), CheckerCipher()), indexerDeadlineMs = 400)
        val ltcRows = kotlinx.coroutines.withTimeout(10_000) {
            ltc.check(wallet, Coin.LITECOIN, Network.MAINNET, chain, utxos, indexer = whole, indexerSource = InscriptionCheck.Source.PUBLIC)
        }
        assertFalse(ltcRows.getValue("$a:0").runes, "whole-pass: a cut pass gives nothing")
        assertTrue(ltc.status(wallet).value!!.indexerUnreachable)
    }

    /**
     * Final review I2: a results file that exists but cannot be read is set aside (one kept copy) and the wallet counts
     * as checked with no rows, so Send treats every coin as Unchecked (rule 5), never by the old local rule alone.
     */
    @Test fun anUnreadableResultsFileIsSetAsideAndTheWalletCountsAsChecked(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        File(dir, "My Wallet.bin").writeBytes(byteArrayOf(1, 2, 3))
        val checker = checker(dir, cipher)
        assertEquals(emptyMap(), checker.storedResults(wallet))
        assertTrue(File(dir, "My Wallet.bin.unreadable").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(emptyMap(), InscriptionStore(dir, cipher).loadOrNull(wallet), "a readable file with no rows")
        val op = "11".repeat(32) + ":0"
        assertEquals(CoinState.UNCHECKED, classifyCoins(listOf(op), checker.storedResults(wallet), true, true, false, { null }, { false }).state(op))
        File(dir, "My Wallet.bin").writeBytes(byteArrayOf(4, 5))
        checker(dir, cipher).storedResults(wallet)
        assertTrue(File(dir, "My Wallet.bin.unreadable").readBytes().contentEquals(byteArrayOf(1, 2, 3)), "one kept copy, the first")
        assertEquals(setOf("My Wallet.bin", "My Wallet.bin.unreadable"), dir.list()!!.toSet())
    }

    /**
     * Final review I2: after lost results, a coin with no indexer answer is not written Clean while the setting names an
     * indexer (its earlier marks are gone): it stays Unchecked with the plain reason, run after run, until an indexer
     * answers for it or it is checked deeper. On Built-in the trace stands as usual.
     */
    @Test fun afterLostResultsNoCleanIsWrittenWithoutAnIndexerAnswer(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val op = "$a:0"
        val utxos = listOf(Utxo(a, 0, 100, 100_000))
        fun lostDir() = newDir().also { File(it, "My Wallet.bin").writeBytes(byteArrayOf(9)) }
        val cipher = CheckerCipher()
        val down = FakeIndexer { throw IOException("down") }
        val dir = lostDir()
        val first = checker(dir, cipher).withIndexer(chain, utxos, down).getValue(op)
        assertEquals(Verdict.UNCHECKED, first.verdict, first.toString())
        assertEquals(InscriptionChecker.LOST_REASON, first.reason)
        assertEquals(CoinState.UNCHECKED, sendState(first))
        val second = checker(dir, cipher).withIndexer(chain, utxos, down).getValue(op)
        assertEquals(InscriptionChecker.LOST_REASON, second.reason, "still lost while the indexer is down")
        val deeper = checker(dir, cipher).check(wallet, Coin.LITECOIN, Network.MAINNET, chain, utxos, setOf(op), indexer = down,
            indexerSource = InscriptionCheck.Source.PUBLIC).getValue(op)
        assertEquals(InscriptionChecker.LOST_REASON, deeper.reason, "Litecoin, checked deeper: a trace cannot see runes")

        val answered = checker(lostDir(), cipher).withIndexer(chain, utxos, FakeIndexer { asked -> asked.associateWith { nothing(it) } }).getValue(op)
        assertEquals(Verdict.CLEAN, answered.verdict, "an indexer answer settles it")
        val builtIn = checker(lostDir(), cipher).withIndexer(chain, utxos, null, null).getValue(op)
        assertEquals(InscriptionChecker.LOST_REASON, builtIn.reason, "Litecoin on Built-in: a trace cannot see runes")
    }

    /** Final review M3: the last run's "indexer unreachable" survives a restart, so the header does not say Up to date. */
    @Test fun theLastRunsReachSurvivesARestart(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val utxos = listOf(Utxo(a, 0, 100, 50_000))
        checker(dir, cipher).withIndexer(chain, utxos, FakeIndexer { throw IOException("down") })
        val fresh = checker(dir, cipher)
        fresh.storedResults(wallet)
        val status = fresh.status(wallet).value!!
        assertTrue(status.indexerUnreachable)
        assertFalse(status.running)
        checker(dir, cipher).withIndexer(chain, utxos, FakeIndexer { asked -> asked.associateWith { nothing(it) } })
        val after = checker(dir, cipher)
        after.storedResults(wallet)
        assertFalse(after.status(wallet).value!!.indexerUnreachable)
    }

    /**
     * Scoped check I1: the lost state survives a run cut short. Run 1 after the loss stops for time (a time-up row, not
     * Clean); run 2, the indexer still down, must not write Clean either. A coin listed while the loss is unresolved joins
     * it; an indexer answer resolves it; a coin first listed after that is an ordinary coin.
     */
    @Test fun lostResultsStayUnresolvedAcrossACutRun(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val op = "$a:0"
        File(dir, "My Wallet.bin").writeBytes(byteArrayOf(9))
        val down = FakeIndexer { throw IOException("down") }
        val cut = checker(dir, cipher).check(wallet, Coin.LITECOIN, Network.MAINNET, chain, listOf(Utxo(a, 0, 100, 100_000)),
            timeUp = { true }, indexer = down, indexerSource = InscriptionCheck.Source.PUBLIC).getValue(op)
        assertEquals(Verdict.UNCHECKED, cut.verdict)
        val z = oldCoin(chain, 60_000)
        val both = listOf(Utxo(a, 0, 100, 100_000), Utxo(z, 0, 100, 60_000))
        val second = checker(dir, cipher).withIndexer(chain, both, down)
        assertEquals(InscriptionChecker.LOST_REASON, second.getValue(op).reason, second.toString())
        assertEquals(InscriptionChecker.LOST_REASON, second.getValue("$z:0").reason, "listed while the loss is unresolved")
        for(row in second.values) assertEquals(CoinState.UNCHECKED, sendState(row))
        //an indexer answer resolves both
        val answered = checker(dir, cipher).withIndexer(chain, both, FakeIndexer { asked -> asked.associateWith { nothing(it) } })
        assertTrue(answered.values.all { it.verdict == Verdict.CLEAN }, answered.toString())
        //resolved: a new coin is ordinary, and the old ones stay resolved, with the indexer down again
        val y = oldCoin(chain, 70_000)
        val later = checker(dir, cipher).withIndexer(chain, both + Utxo(y, 0, 100, 70_000), down)
        assertTrue(later.values.all { it.verdict == Verdict.CLEAN }, later.toString())
    }

    /**
     * Scoped check m4: after a loss, a coin on a chain with rune-like tokens (Litecoin here) keeps the lost reason until an
     * indexer answers for it, on Built-in and after Check deeper too (a trace cannot see runes). On Bellscoin the trace is
     * enough: an ordinary Built-in run or Check deeper resolves it.
     */
    @Test fun afterALossOnlyAnIndexerResolvesARuneChainCoin(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val op = "$a:0"
        val utxos = listOf(Utxo(a, 0, 100, 100_000))
        val cipher = CheckerCipher()
        fun lostDir() = newDir().also { File(it, "My Wallet.bin").writeBytes(byteArrayOf(9)) }
        suspend fun run(dir: File, coin: Coin, deeper: Set<String> = emptySet(), indexer: InscriptionIndexer? = null) =
            checker(dir, cipher).check(wallet, coin, Network.MAINNET, chain, utxos, deeper, indexer = indexer,
                indexerSource = indexer?.let { InscriptionCheck.Source.PUBLIC }).getValue(op)
        val ltc = lostDir()
        assertEquals(InscriptionChecker.LOST_REASON, run(ltc, Coin.LITECOIN).reason, "Built-in")
        assertEquals(InscriptionChecker.LOST_REASON, run(ltc, Coin.LITECOIN, setOf(op)).reason, "Check deeper")
        assertEquals(Verdict.CLEAN, run(ltc, Coin.LITECOIN, indexer = FakeIndexer { asked -> asked.associateWith { nothing(it) } }).verdict)

        val bells = lostDir()
        assertEquals(Verdict.CLEAN, run(bells, Coin.BELLSCOIN).verdict, "Built-in on Bellscoin")
        val bellsPublic = lostDir()
        val down = FakeIndexer { throw IOException("down") }
        assertEquals(InscriptionChecker.LOST_REASON, run(bellsPublic, Coin.BELLSCOIN, indexer = down).reason)
        assertEquals(Verdict.CLEAN, run(bellsPublic, Coin.BELLSCOIN, setOf(op), indexer = down).verdict, "Check deeper on Bellscoin")
    }

    /**
     * Scoped check I2: a read error (an IO or Keystore error, not a failed authentication) leaves the file where it is:
     * nothing is set aside or written, the check run is skipped, Send sees a checked wallet with no rows (every coin
     * Unchecked, never the old local rule), and the next read tries again.
     */
    @Test fun aReadErrorLeavesTheFileInPlaceAndHoldsNothingClean(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val a = oldCoin(chain, 100_000)
        val utxos = listOf(Utxo(a, 0, 100, 100_000))
        val stored = checker(dir, cipher).withIndexer(chain, utxos, FakeIndexer { asked -> asked.associateWith { runesOn(it) } })
        val file = File(dir, "My Wallet.bin")
        val bytes = file.readBytes()
        for(error in listOf(IOException("I/O error"), java.security.KeyStoreException("Keystore busy"), IllegalStateException("not ready"))) {
            cipher.failOpen = error
            val checker = checker(dir, cipher)
            assertEquals(emptyMap(), checker.storedResults(wallet), "$error")
            assertTrue(bytes.contentEquals(file.readBytes()), "$error")
            assertFalse(File(dir, "My Wallet.bin.unreadable").exists())
            chain.rawCalls = 0
            assertFailsWith<IOException> { checker.withIndexer(chain, utxos, null, null) }
            assertEquals(0, chain.rawCalls, "the check run is skipped")
            assertTrue(bytes.contentEquals(file.readBytes()))
            val op = "$a:0"
            assertEquals(CoinState.UNCHECKED, classifyCoins(listOf(op), checker.sendSnapshot(wallet).rows, true, true, false, { null }, { false }).state(op))
            cipher.failOpen = null
            assertEquals(stored, checker.storedResults(wallet), "the next read tries again")
        }
    }

    /** Scoped check I2: a second unreadable file never overwrites the first set-aside copy (the newer one is dropped). */
    @Test fun aSecondLossKeepsTheFirstAsideCopy(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        File(dir, "My Wallet.bin").writeBytes(ByteArray(40) { 1 })
        checker(dir, cipher).storedResults(wallet)
        File(dir, "My Wallet.bin").writeBytes(ByteArray(40) { 2 })
        assertEquals(emptyMap(), checker(dir, cipher).storedResults(wallet))
        assertTrue(File(dir, "My Wallet.bin.unreadable").readBytes().contentEquals(ByteArray(40) { 1 }), "the first copy stays")
        assertEquals(setOf("My Wallet.bin", "My Wallet.bin.unreadable"), dir.list()!!.toSet())
    }

    /**
     * Scoped check I2: when the replacement cannot be written (the Keystore key exists but cannot seal), nothing is moved:
     * the unreadable file stays and the wallet reads as checked with no rows; the next read with a working key sets it
     * aside properly.
     */
    @Test fun aFailingReplacementSaveLeavesAReadableState(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val garbage = ByteArray(40) { 3 }
        File(dir, "My Wallet.bin").writeBytes(garbage)
        cipher.failSeal = true
        val checker = checker(dir, cipher)
        assertEquals(emptyMap(), checker.storedResults(wallet))
        assertTrue(File(dir, "My Wallet.bin").readBytes().contentEquals(garbage), "never neither file")
        assertFalse(File(dir, "My Wallet.bin.unreadable").exists())
        cipher.failSeal = false
        assertEquals(emptyMap(), checker(dir, cipher).storedResults(wallet))
        assertTrue(File(dir, "My Wallet.bin.unreadable").readBytes().contentEquals(garbage))
        assertEquals(emptyMap(), InscriptionStore(dir, cipher).loadOrNull(wallet))
    }

    /** Scoped check m1: the "unknown" times belong to the indexer that gave them: another indexer is asked at once. */
    @Test fun askedTimesDoNotCarryToAnotherIndexer(): Unit = runBlocking {
        val dir = newDir()
        val cipher = CheckerCipher()
        val chain = FakeChain()
        val coins = (1..3).map { oldCoin(chain, 10_000L + it) }
        val utxos = coins.map { Utxo(it, 0, 100, 10_000) }
        val nintondo = FakeIndexer { asked -> asked.associateWith { IndexerOutput.unknown(it) } }.apply { runesAware = false; asksOneAtATime = true }
        suspend fun run(id: String): Int {
            val before = nintondo.asked.size
            checker(dir, cipher).check(wallet, Coin.DOGECOIN, Network.MAINNET, chain, utxos, indexer = nintondo,
                indexerSource = InscriptionCheck.Source.YOUR_OWN, indexerId = id)
            return nintondo.asked.drop(before).flatten().size
        }
        assertEquals(3, run("dogecoin:mainnet:https://a.example"))
        assertEquals(0, run("dogecoin:mainnet:https://a.example"))
        assertEquals(3, run("dogecoin:mainnet:https://b.example"), "another indexer")
        assertEquals(0, run("dogecoin:mainnet:https://b.example"))
    }

    /**
     * Scoped check m2: an error in a later piece (a failed request, a reader closed by a settings change) is treated like
     * the deadline: the carrying answers already in stand, the rest have none, never a "nothing".
     */
    @Test fun anErrorMidPassKeepsTheHoldsAlreadyIn(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 60_000)
        val c = oldCoin(chain, 70_000)
        val utxos = listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 60_000), Utxo(c, 0, 100, 70_000))
        val failing = FakeIndexer { asked ->
            asked.associateWith { op -> if(op.startsWith(a)) IndexerOutput(op, true, emptyList(), emptyMap(), true) else throw IndexerException("closed") }
        }.apply { runesAware = false; asksOneAtATime = true }
        val checker = checker(newDir(), CheckerCipher())
        val rows = checker.check(wallet, Coin.DOGECOIN, Network.MAINNET, chain, utxos, indexer = failing, indexerSource = InscriptionCheck.Source.PUBLIC)
        assertTrue(rows.getValue("$a:0").runes)
        assertFalse(rows.getValue("$b:0").runes)
        assertFalse(checker.status(wallet).value!!.indexerUnreachable)
    }

    /**
     * Scoped check m3: a pass cut short with only some answers says so (partial, not unreachable), and the coins it never
     * reached keep their own rows, exactly as a run with no indexer writes them.
     */
    @Test fun aPartialPassIsReportedAndUnaskedCoinsKeepTheirOwnRows(): Unit = runBlocking {
        val chain = FakeChain()
        val a = oldCoin(chain, 50_000)
        val b = oldCoin(chain, 60_000)
        val c = oldCoin(chain, 70_000)
        val utxos = listOf(Utxo(a, 0, 100, 50_000), Utxo(b, 0, 100, 60_000), Utxo(c, 0, 100, 70_000))
        val slow = FakeIndexer { asked ->
            asked.associateWith { op -> if(op.startsWith(a)) IndexerOutput(op, true, emptyList(), emptyMap(), true) else kotlinx.coroutines.awaitCancellation() }
        }.apply { runesAware = false; asksOneAtATime = true }
        val dir = newDir()
        val cipher = CheckerCipher()
        val checker = InscriptionChecker(InscriptionStore(dir, cipher), indexerDeadlineMs = 400)
        val rows = kotlinx.coroutines.withTimeout(10_000) {
            checker.check(wallet, Coin.DOGECOIN, Network.MAINNET, chain, utxos, indexer = slow, indexerSource = InscriptionCheck.Source.PUBLIC)
        }
        val status = checker.status(wallet).value!!
        assertTrue(status.indexerPartial)
        assertFalse(status.indexerUnreachable)
        val plain = checker(newDir(), CheckerCipher()).check(wallet, Coin.DOGECOIN, Network.MAINNET, chain, utxos)
        for(op in listOf("$b:0", "$c:0")) {
            assertEquals(plain.getValue(op).verdict, rows.getValue(op).verdict, op)
            assertEquals(plain.getValue(op).reason, rows.getValue(op).reason, op)
        }
        val restarted = checker(dir, cipher)
        restarted.storedResults(wallet)
        assertTrue(restarted.status(wallet).value!!.indexerPartial, "kept across a restart")
    }
}
