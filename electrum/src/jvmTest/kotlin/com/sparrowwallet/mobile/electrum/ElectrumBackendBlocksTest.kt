package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.sha256d
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The block calls the inscription trace makes, against a fake Electrum server answering canned results. */
class ElectrumBackendBlocksTest {
    private val txid = "aa".repeat(32)
    private val blockHash = "bb".repeat(32)
    private val header80 = "01".repeat(80)
    private val headerAuxPow = header80 + "ff".repeat(120)
    private val noMethod = null to "{\"code\":1,\"message\":\"unexpected\"}"

    @AfterTest fun reset() { NetworkPolicy.blocked = false }

    /** [answer] returns the raw JSON of the result, or null and the raw JSON of an error. */
    private fun fake(server: ServerSocket, answer: (method: String, params: String) -> Pair<String?, String>) = thread(isDaemon = true) {
        server.accept().use { peer ->
            val reader = peer.getInputStream().bufferedReader()
            val writer = peer.getOutputStream().bufferedWriter()
            while(true) {
                val line = reader.readLine() ?: break
                val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(line)!!.groupValues[1]
                val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(line)!!.groupValues[1]
                val params = Regex("\"params\"\\s*:\\s*(\\[.*])").find(line)?.groupValues?.get(1) ?: "[]"
                val (result, error) = if(method == "server.version") "[\"ElectrumX 1.16.0\",\"1.4\"]" to "" else answer(method, params)
                writer.write(if(result != null) "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n"
                             else "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":$error}\n")
                writer.flush()
            }
        }
    }

    /** The waits the backend asked for (injected, so no test sleeps). */
    private val pauses = ArrayList<Long>()

    private fun withBackend(answer: (String, String) -> Pair<String?, String>, block: suspend (ElectrumBackend) -> Unit) = runBlocking {
        ServerSocket(0).use { server ->
            fake(server, answer)
            val backend = ElectrumBackend(ElectrumClient("127.0.0.1", server.localPort, useTls = false)) { pauses.add(it) }
            backend.connect()
            try { block(backend) } finally { backend.close() }
        }
    }

    private fun expectedHash(header: String) = sha256d(header.hexToBytes().copyOfRange(0, 80)).reversedArray().toHex()

    @Test fun blockHashIsTheDoubleHashOfTheFirst80BytesReversed() {
        val headers = mapOf("7" to header80, "8" to headerAuxPow)
        withBackend({ m, p -> if(m == "blockchain.block.header") "\"${headers.getValue(p.trim('[', ']'))}\"" to "" else noMethod }) { b ->
            assertEquals(expectedHash(header80), b.blockHash(7))
            assertEquals(expectedHash(headerAuxPow), b.blockHash(8))
            // the extra AuxPoW bytes do not change the hash: both headers start with the same 80 bytes
            assertEquals(b.blockHash(7), b.blockHash(8))
        }
    }

    @Test fun shortHeaderThrows() {
        withBackend({ _, _ -> "\"${"01".repeat(79)}\"" to "" }) { b ->
            assertFailsWith<IOException> { b.blockHash(7) }
        }
    }

    @Test fun txidAtIsNullPastTheEndAndWhenUnsupported() {
        withBackend({ m, p ->
            if(m != "blockchain.transaction.id_from_pos") noMethod
            else when(p.trim('[', ']').split(",")[1].trim()) {
                "0" -> "\"$txid\"" to ""
                "1" -> null to "{\"code\":1,\"message\":\"no tx at position 1 in block at height 5\"}"
                "2" -> null to "{\"code\":-32601,\"message\":\"unknown method blockchain.transaction.id_from_pos\"}"
                "3" -> "\"nothex\"" to ""
                else -> null to "{\"code\":-32603,\"message\":\"internal error\"}"
            }
        }) { b ->
            assertEquals(txid, b.txidAt(5, 0))
            assertNull(b.txidAt(5, 1))
            assertNull(b.txidAt(5, 2))
            assertFailsWith<IOException> { b.txidAt(5, 3) }
            assertFailsWith<IllegalStateException> { b.txidAt(5, 4) }
        }
    }

    /** A distinct 80-byte header per height, and the block hash it gives. */
    private fun headerAt(height: Int) = height.toString(16).padStart(2, '0').repeat(80)
    private fun hashAt(height: Int) = expectedHash(headerAt(height))

    /** Answers tip, verbose and headers; [verbose] and [tip] are read per call. */
    private fun chain(headerCalls: MutableList<Int> = ArrayList(), tip: () -> Int = { 110 }, verbose: () -> String): (String, String) -> Pair<String?, String> = { m, p ->
        when(m) {
            "blockchain.headers.subscribe" -> "{\"height\":${tip()},\"hex\":\"00\"}" to ""
            "blockchain.block.header" -> { val h = p.trim('[', ']').toInt(); headerCalls += h; "\"${headerAt(h)}\"" to "" }
            else -> verbose() to ""
        }
    }

    private fun verboseOf(confirmations: Int?, hash: String?) = "{\"txid\":\"$txid\"" +
        (if(confirmations != null) ",\"confirmations\":$confirmations" else "") +
        (if(hash != null) ",\"blockhash\":\"$hash\",\"blocktime\":1761000000" else "") + ",\"vin\":[],\"vout\":[]}"

    @Test fun transactionAtTakesHeightFromConfirmationsAndTip() {
        withBackend(chain { verboseOf(11, hashAt(100)) }) { b ->
            assertEquals(TxAt(100, hashAt(100), 1761000000L), b.transactionAt(txid))
        }
    }

    @Test fun unconfirmedGivesHeightZero() {
        for(verbose in listOf(verboseOf(null, null), verboseOf(0, null), verboseOf(null, blockHash))) {
            withBackend(chain { verbose }) { b ->
                assertEquals(TxAt(0, null, 0), b.transactionAt(txid))
            }
        }
    }

    @Test fun confirmationsWithoutABlockHashThrow() {
        withBackend(chain { verboseOf(11, null) }) { b ->
            assertFailsWith<IOException> { b.transactionAt(txid) }
        }
    }

    @Test fun confirmationsBeyondTheTipThrow() {
        withBackend(chain { verboseOf(500, hashAt(100)) }) { b ->
            assertFailsWith<IOException> { b.transactionAt(txid) }
        }
    }

    @Test fun blockHashThatDisagreesWithTheHeightThrows() {
        // the daemon is one block ahead of the indexer: confirmations count a block the tip lacks
        withBackend(chain { verboseOf(11, hashAt(101)) }) { b ->
            assertFailsWith<IOException> { b.transactionAt(txid) }
        }
    }

    @Test fun tipThatMovesOnceThenSettlesGivesTheRightHeight() {
        var tips = 0
        // first read: tip 110, 11 confirmations -> 100, but the block is at 101 (the tip had not caught up); then tip 111
        withBackend(chain(tip = { if(tips++ == 0) 110 else 111 }) { verboseOf(11, hashAt(101)) }) { b ->
            assertEquals(TxAt(101, hashAt(101), 1761000000L), b.transactionAt(txid))
        }
    }

    @Test fun sameHeightTransactionsCostOneHeaderCall() {
        val calls = ArrayList<Int>()
        withBackend(chain(calls) { verboseOf(11, hashAt(100)) }) { b ->
            b.transactionAt(txid)
            b.transactionAt(txid)
            assertEquals(listOf(100), calls)
        }
    }

    /** Review I3: placing transactions reads the tip once per backend (one run), not once per transaction. */
    @Test fun theTipIsReadOncePerRun() {
        var tips = 0
        withBackend(chain(tip = { tips++; 110 }) { verboseOf(11, hashAt(100)) }) { b ->
            repeat(3) { assertEquals(TxAt(100, hashAt(100), 1761000000L), b.transactionAt(txid)) }
            assertEquals(1, tips)
        }
    }

    /** A block found after the tip was read: one re-read, kept for the transactions after it. */
    @Test fun aTipThatMovesIsReadAgainOnceAndKept() {
        var tip = 110
        var tips = 0
        var confirmations = 11
        withBackend(chain(tip = { tips++; tip }) { verboseOf(confirmations, hashAt(100)) }) { b ->
            assertEquals(100, b.transactionAt(txid).height)
            tip = 111
            confirmations = 12
            assertEquals(100, b.transactionAt(txid).height)
            assertEquals(2, tips)
            assertEquals(100, b.transactionAt(txid).height)
            assertEquals(2, tips, "the new tip is kept")
        }
    }

    /**
     * Residual R2: the server's tip trails its daemon for a few seconds after a block. The verbose confirmations already
     * count the new block; an instant re-read gives the same tip, so the backend waits, reads again and places it.
     */
    @Test fun aTipThatLagsItsDaemonIsWaitedFor() {
        var tips = 0
        withBackend(chain(tip = { if(tips++ < 2) 110 else 111 }) { verboseOf(1, hashAt(111)) }) { b ->
            assertEquals(TxAt(111, hashAt(111), 1761000000L), b.transactionAt(txid))
            assertEquals(1, pauses.size)
            assertEquals(3, tips)
        }
    }

    @Test fun aTipThatNeverCatchesUpThrowsAfterThreeRetries() {
        var tips = 0
        withBackend(chain(tip = { tips++; 110 }) { verboseOf(1, hashAt(111)) }) { b ->
            assertFailsWith<IOException> { b.transactionAt(txid) }
            assertEquals(3, pauses.size)
            assertEquals(1 + 3 * 2, tips, "the first read, then two per retry (before and after the wait)")
        }
    }

    /** A block hash that matches no height the tip gives is never placed, however often the tip is read again. */
    @Test fun aMismatchingHashNeverReturnsAHeight() {
        var tip = 105
        withBackend(chain(tip = { tip++ }) { verboseOf(3, "ee".repeat(32)) }) { b ->
            assertFailsWith<IOException> { b.transactionAt(txid) }
            assertTrue(pauses.isEmpty(), "a tip that moves needs no wait")
        }
    }

    private val rawHex = "0200000001" + "11".repeat(32) + "00000000" + "00" + "fdffffff" + "01" + "e803000000000000" + "0151" + "00000000"
    private val rawId = com.sparrowwallet.mobile.crypto.RawTx.parse(rawHex.hexToBytes()).txid
    private val otherHex = "0200000001" + "22".repeat(32) + "00000000" + "00" + "fdffffff" + "01" + "e803000000000000" + "0151" + "00000000"

    /** A chain whose verbose answer for [rawId] carries [hex] (when not null); non-verbose calls answer [rawAnswer]. */
    private fun placedChain(methods: MutableList<String>, hex: String?, rawAnswer: String = rawHex): (String, String) -> Pair<String?, String> = { m, p ->
        when(m) {
            "blockchain.headers.subscribe" -> "{\"height\":110,\"hex\":\"00\"}" to ""
            "blockchain.block.header" -> "\"${headerAt(p.trim('[', ']').toInt())}\"" to ""
            else -> {
                val verbose = p.contains("true")
                methods += if(verbose) "verbose" else "raw"
                if(verbose) ("{\"txid\":\"$rawId\",\"confirmations\":11,\"blockhash\":\"${hashAt(100)}\",\"blocktime\":1761000000" +
                    (if(hex != null) ",\"hex\":\"$hex\"" else "") + ",\"vin\":[],\"vout\":[]}") to ""
                else "\"$rawAnswer\"" to ""
            }
        }
    }

    @Test fun placedTransactionUsesOneCallWhenVerboseCarriesHex() {
        val methods = ArrayList<String>()
        withBackend(placedChain(methods, rawHex)) { b ->
            val placed = b.placedTransaction(rawId)
            assertEquals(listOf("verbose"), methods, "one transaction.get, and no raw call")
            assertEquals(rawId, com.sparrowwallet.mobile.crypto.RawTx.parse(placed.raw).txid)
            assertEquals(TxAt(100, hashAt(100), 1761000000L), placed.at)
        }
    }

    @Test fun placedTransactionFallsBackWithoutHex() {
        val methods = ArrayList<String>()
        withBackend(placedChain(methods, null)) { b ->
            val placed = b.placedTransaction(rawId)
            assertEquals(listOf("verbose", "raw"), methods)
            assertEquals(rawId, com.sparrowwallet.mobile.crypto.RawTx.parse(placed.raw).txid)
            assertEquals(TxAt(100, hashAt(100), 1761000000L), placed.at)
        }
    }

    @Test fun placedTransactionIgnoresHexThatHashesToAnotherTxid() {
        val methods = ArrayList<String>()
        withBackend(placedChain(methods, otherHex)) { b ->
            val placed = b.placedTransaction(rawId)
            assertEquals(listOf("verbose", "raw"), methods, "the wrong hex is not used; the raw call answers")
            assertEquals(rawId, com.sparrowwallet.mobile.crypto.RawTx.parse(placed.raw).txid)
        }
        //and the raw answer is itself checked: another transaction there is an error, never returned
        withBackend(placedChain(ArrayList(), otherHex, rawAnswer = otherHex)) { b ->
            assertFailsWith<IOException> { b.placedTransaction(rawId) }
        }
    }

    @Test fun placedTransactionKeepsTheBlockHashCheck() {
        val inner = placedChain(ArrayList(), rawHex)
        withBackend({ m, p -> if(m == "blockchain.block.header") "\"${headerAt(99)}\"" to "" else inner(m, p) }) { b ->
            assertFailsWith<IOException> { b.placedTransaction(rawId) }
        }
    }

    @Test fun blockCallsRefuseWhenAirGapped() {
        withBackend({ _, _ -> "\"00\"" to "" }) { b ->
            NetworkPolicy.blocked = true
            assertFailsWith<AirGappedException> { b.blockHash(1) }
            assertFailsWith<AirGappedException> { b.txidAt(1, 0) }
            assertFailsWith<AirGappedException> { b.transactionAt(txid) }
        }
    }
}
