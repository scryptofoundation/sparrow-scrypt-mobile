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

    private fun withBackend(answer: (String, String) -> Pair<String?, String>, block: suspend (ElectrumBackend) -> Unit) = runBlocking {
        ServerSocket(0).use { server ->
            fake(server, answer)
            val backend = ElectrumBackend(ElectrumClient("127.0.0.1", server.localPort, useTls = false))
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

    private fun tipAnd(verbose: String, tip: () -> Int = { 110 }): (String, String) -> Pair<String?, String> = { m, _ ->
        if(m == "blockchain.headers.subscribe") "{\"height\":${tip()},\"hex\":\"00\"}" to "" else verbose to ""
    }

    @Test fun transactionAtTakesHeightFromConfirmationsAndTip() {
        val verbose = "{\"txid\":\"$txid\",\"confirmations\":11,\"blockhash\":\"$blockHash\",\"blocktime\":1761000000,\"vin\":[],\"vout\":[]}"
        withBackend(tipAnd(verbose)) { b ->
            assertEquals(TxAt(100, blockHash, 1761000000L), b.transactionAt(txid))
        }
    }

    @Test fun unconfirmedOrUnusableConfirmationsGiveHeightZero() {
        for(verbose in listOf(
            "{\"txid\":\"$txid\",\"vin\":[],\"vout\":[]}",
            "{\"txid\":\"$txid\",\"confirmations\":0,\"vin\":[],\"vout\":[]}",
            "{\"txid\":\"$txid\",\"blockhash\":\"$blockHash\",\"vin\":[],\"vout\":[]}"
        )) {
            withBackend(tipAnd(verbose)) { b ->
                assertEquals(TxAt(0, null, 0), b.transactionAt(txid))
            }
        }
    }

    @Test fun confirmationsBeyondTheTipThrow() {
        withBackend(tipAnd("{\"txid\":\"$txid\",\"confirmations\":500,\"vin\":[],\"vout\":[]}")) { b ->
            assertFailsWith<IOException> { b.transactionAt(txid) }
        }
    }

    @Test fun movingTipThrowsRatherThanGuessing() {
        var tip = 110
        withBackend(tipAnd("{\"txid\":\"$txid\",\"confirmations\":11,\"vin\":[],\"vout\":[]}") { tip++ }) { b ->
            assertFailsWith<IOException> { b.transactionAt(txid) }
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
