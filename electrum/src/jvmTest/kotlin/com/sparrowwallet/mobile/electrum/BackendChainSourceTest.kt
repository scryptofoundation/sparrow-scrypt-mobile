package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.track.BlockFee
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendChainSourceTest {
    private val raw = "0200000001" + "11".repeat(32) + "00000000" + "00" + "fdffffff" + "01" + "e803000000000000" + "0151" + "00000000"
    private val txid = RawTx.parse(raw.hexToBytes()).txid
    private val hash = "cd".repeat(32)

    private open class Bare(val raw: ByteArray = ByteArray(0)) : ChainBackend {
        override suspend fun connect() {}
        override suspend fun balance(script: ByteArray) = error("unused")
        override suspend fun history(script: ByteArray) = error("unused")
        override suspend fun unspent(script: ByteArray) = error("unused")
        override suspend fun transaction(txid: String) = error("unused")
        override suspend fun rawTransaction(txid: String): ByteArray = raw
        override suspend fun broadcast(txHex: String) = error("unused")
        override suspend fun tipHeight() = error("unused")
        override suspend fun feeRate(targetBlocks: Int): Double? = null
        override fun close() {}
    }

    private class Fake(raw: ByteArray, val at: TxAt) : Bare(raw) {
        override suspend fun transactionAt(txid: String): TxAt = at
        override suspend fun blockHash(height: Int): String? = "h$height"
        override suspend fun txidAt(height: Int, position: Int): String? = if(position == 0) "t$height" else null
        override suspend fun blockFees(blockHash: String): List<BlockFee>? = listOf(BlockFee(blockHash, 5))
    }

    @Test fun transactionCarriesHeightHashAndInputs(): Unit = runBlocking {
        val source = BackendChainSource(Fake(raw.hexToBytes(), TxAt(101, hash, 1761000000L)))
        val tx = source.transaction(txid)
        assertEquals(txid, tx.tx.txid)
        assertEquals(1, tx.tx.inputs.size)
        assertEquals("11".repeat(32), tx.tx.inputs[0].prevTxid)
        assertEquals(101, tx.height)
        assertEquals(hash, tx.blockHash)
        assertEquals(1761000000L, tx.blockTime)
        assertTrue(tx.fromServer)
    }

    /** The source reads a transaction and its place through placedTransaction, not as two separate calls. */
    @Test fun transactionUsesPlacedTransaction(): Unit = runBlocking {
        val calls = ArrayList<String>()
        val backend = object : Bare(raw.hexToBytes()) {
            override suspend fun rawTransaction(txid: String): ByteArray { calls.add("raw"); return raw }
            override suspend fun transactionAt(txid: String): TxAt { calls.add("at"); return TxAt(0, null, 0) }
        }
        val plain = BackendChainSource(backend).transaction(txid)
        assertEquals(listOf("raw", "at"), calls, "the default placedTransaction reads the raw bytes and then the place")
        assertEquals(txid, plain.tx.txid)
        val rawBytes = raw.hexToBytes()
        val placed = object : Bare() {
            override suspend fun placedTransaction(txid: String) = PlacedTx(rawBytes, TxAt(101, hash, 1761000000L))
            override suspend fun rawTransaction(txid: String): ByteArray = error("the source must use placedTransaction")
            override suspend fun transactionAt(txid: String): TxAt = error("the source must use placedTransaction")
        }
        val tx = BackendChainSource(placed).transaction(txid)
        assertEquals(txid, tx.tx.txid)
        assertEquals(101, tx.height)
        assertEquals(hash, tx.blockHash)
        assertEquals(1761000000L, tx.blockTime)
        assertTrue(tx.fromServer)
    }

    @Test fun otherCallsDelegate(): Unit = runBlocking {
        val source = BackendChainSource(Fake(raw.hexToBytes(), TxAt(0, null, 0)))
        assertEquals("h7", source.blockHash(7))
        assertEquals("t7", source.txidAt(7, 0))
        assertNull(source.txidAt(7, 1))
        assertEquals(listOf(BlockFee(hash, 5)), source.blockFees(7, hash))
    }

    /** Review I3: every call the trace makes runs without waiting out a rate limit; the backend sees that in its context. */
    @Test fun traceCallsDoNotWaitOutARateLimit(): Unit = runBlocking {
        val seen = ArrayList<Boolean>()
        val backend = object : Bare(raw.hexToBytes()) {
            suspend fun mark() { seen.add(currentCoroutineContext()[NoRateLimitWait] != null) }
            override suspend fun rawTransaction(txid: String): ByteArray { mark(); return raw }
            override suspend fun transactionAt(txid: String): TxAt { mark(); return TxAt(0, null, 0) }
            override suspend fun blockHash(height: Int): String? { mark(); return null }
            override suspend fun txidAt(height: Int, position: Int): String? { mark(); return null }
            override suspend fun blockFees(blockHash: String, extraRequest: () -> Unit): List<BlockFee>? { mark(); return null }
        }
        val source = BackendChainSource(backend)
        source.transaction(txid)
        source.blockHash(1)
        source.txidAt(1, 0)
        source.blockFees(1, hash) {}
        assertEquals(List(5) { true }, seen)
        //and outside the source, nothing is marked
        backend.blockHash(1)
        assertEquals(false, seen.last())
    }

    /** Each listing request past the first is counted through the hook the tracer passes. */
    @Test fun blockFeePagesReachTheTracersCounter(): Unit = runBlocking {
        val backend = object : Bare() {
            override suspend fun blockFees(blockHash: String, extraRequest: () -> Unit): List<BlockFee>? {
                extraRequest(); extraRequest()
                return emptyList()
            }
        }
        var counted = 0
        assertEquals(emptyList(), BackendChainSource(backend).blockFees(1, hash) { counted++ })
        assertEquals(2, counted)
    }

    @Test fun backendWithoutTheCallsAnswersNull(): Unit = runBlocking {
        val source = BackendChainSource(Bare())
        assertNull(source.blockHash(1))
        assertNull(source.txidAt(1, 0))
        assertNull(source.blockFees(1, hash))
    }
}
