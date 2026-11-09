package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.track.BlockFee
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

    @Test fun otherCallsDelegate(): Unit = runBlocking {
        val source = BackendChainSource(Fake(raw.hexToBytes(), TxAt(0, null, 0)))
        assertEquals("h7", source.blockHash(7))
        assertEquals("t7", source.txidAt(7, 0))
        assertNull(source.txidAt(7, 1))
        assertEquals(listOf(BlockFee(hash, 5)), source.blockFees(7, hash))
    }

    @Test fun backendWithoutTheCallsAnswersNull(): Unit = runBlocking {
        val source = BackendChainSource(Bare())
        assertNull(source.blockHash(1))
        assertNull(source.txidAt(1, 0))
        assertNull(source.blockFees(1, hash))
    }
}
