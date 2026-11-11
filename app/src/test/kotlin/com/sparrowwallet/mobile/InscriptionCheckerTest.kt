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
        return if(blockHashNull) null else block(height)
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
    var closed = false

    override suspend fun status(): IndexerStatus = IndexerStatus("mainnet", runesAware, 1)
    override suspend fun outputs(outpoints: List<String>): Map<String, IndexerOutput> {
        asked.add(outpoints)
        return answer(outpoints)
    }
    override suspend fun inscription(id: String): IndexerInscription? {
        inscriptionsAsked.add(id)
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
            val picked = openCheckIndexer(setting, Coin.LITECOIN, Network.MAINNET, null, airGapped = false, open)
            assertNull(picked.reader)
            assertNull(picked.source)
        }
        for(setting in listOf(InscriptionSetting.Public, InscriptionSetting.YourOwn("https://ord.example"))) {
            assertNull(openCheckIndexer(setting, Coin.LITECOIN, Network.MAINNET, null, airGapped = true, open).reader)
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
        val public = openCheckIndexer(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, tor, airGapped = false, open)
        assertEquals(InscriptionCheck.Source.PUBLIC, public.source)
        val own = openCheckIndexer(InscriptionSetting.YourOwn("https://ord.example/"), Coin.LITECOIN, Network.MAINNET, null, airGapped = false, open)
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
        for(coin in listOf(Coin.DOGECOIN, Coin.LITECOIN)) {
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
            if(coin == Coin.DOGECOIN) {
                assertEquals(com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy.MAY_CARRY_RUNES, result.reason)
                assertEquals(CoinState.HELD, state)
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
}
