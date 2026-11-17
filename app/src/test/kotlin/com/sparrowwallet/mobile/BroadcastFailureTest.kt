package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.electrum.Balance
import com.sparrowwallet.mobile.electrum.BroadcastRefusedException
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.ElectrumRpcException
import com.sparrowwallet.mobile.electrum.HistoryEntry
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.TxDetail
import com.sparrowwallet.mobile.electrum.Utxo
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the ordinary Send says when a broadcast fails: one of its own sentences, chosen by the kind of failure. */
class BroadcastFailureTest {
    /** Text only a server could have written; none of it may reach a screen. */
    private val serverText = "sendrawtransaction RPC error: {\"code\":-26,\"message\":\"<b>call 555-0100</b> https://evil.example/claim\"}"

    private fun electrum(said: String) = ElectrumRpcException("Electrum error: {\"code\":1,\"message\":\"$said\"}", 1, said)

    private fun failed(asked: Boolean = true, failure: () -> Throwable): BroadcastFailedException = assertFailsWith<BroadcastFailedException> {
        runBlocking { fixedBroadcastFailure<String> { reached -> if(asked) reached(); throw failure() } }
    }

    @Test
    fun everySentenceIsFixedPlainAndItsOwn() {
        val sentences = BroadcastFailure.entries.map { it.sentence }
        assertEquals(sentences.size, sentences.toSet().size, "each kind has its own sentence")
        for(sentence in sentences) {
            assertTrue(sentence.isNotBlank() && sentence.endsWith("."), sentence)
            assertFalse(sentence.contains(0x2014.toChar()) || sentence.contains('\n') || sentence.contains("http"), sentence)
        }
    }

    @Test
    fun anEsploraServersBodyNeverReachesTheScreen() {
        for(status in listOf(400, 401, 403, 404, 405, 408, 429, 500, 502, 503, 200, 302, -1)) {
            val e = failed { BroadcastRefusedException(status, serverText) }
            assertTrue(e.message in BroadcastFailure.entries.map { it.sentence }, "status $status: ${e.message}")
            assertEquals(e.failure.sentence, e.message)
            assertNull(e.cause, "the server's exception does not ride along")
            for(part in listOf("555-0100", "evil.example", "<b>", "sendrawtransaction", "-26")) {
                assertFalse(e.message!!.contains(part), "status $status shows $part")
            }
        }
    }

    @Test
    fun anElectrumServersErrorNeverReachesTheScreen() {
        val e = failed { electrum("the transaction was rejected by network rules.\n\n<b>call 555-0100</b> https://evil.example/claim") }
        assertEquals(BroadcastFailure.REFUSED, e.failure)
        assertEquals(BroadcastFailure.REFUSED.sentence, e.message)
        assertNull(e.cause)
        val bare = failed { ElectrumRpcException("Electrum error: \"555-0100\"", null, null) }
        assertEquals(BroadcastFailure.REFUSED.sentence, bare.message)
    }

    @Test
    fun theSentenceIsChosenByTheKindOfFailure() {
        assertEquals(BroadcastFailure.AIR_GAPPED, failed(asked = false) { AirGappedException("Electrum") }.failure)
        assertEquals(BroadcastFailure.AIR_GAPPED, failed { AirGappedException("Esplora") }.failure)
        assertEquals(BroadcastFailure.NOT_CONNECTED, failed(asked = false) { IOException("Not an Esplora server: https://evil.example") }.failure,
            "before the transaction was handed to a server")
        assertEquals(BroadcastFailure.NOT_CONNECTED, failed(asked = false) { IllegalStateException("Dogecoin Mainnet needs your own server") }.failure)
        assertEquals(BroadcastFailure.NO_ANSWER, failed { java.net.SocketTimeoutException("Read timed out") }.failure,
            "after it was handed over, a lost connection says nothing of where it went")
        assertEquals(BroadcastFailure.NO_ANSWER, failed { IllegalStateException("Connection closed by server") }.failure)
        assertEquals(BroadcastFailure.BUSY, failed { RateLimitedException("https://evil.example answered HTTP 429") }.failure)
        assertEquals(BroadcastFailure.BUSY, failed { BroadcastRefusedException(429, serverText) }.failure)
        assertEquals(BroadcastFailure.BUSY, failed { BroadcastRefusedException(408, serverText) }.failure)
        assertEquals(BroadcastFailure.NOT_OFFERED, failed { BroadcastRefusedException(404, serverText) }.failure)
        assertEquals(BroadcastFailure.NOT_OFFERED, failed { BroadcastRefusedException(405, serverText) }.failure)
        assertEquals(BroadcastFailure.SERVER_FAILED, failed { BroadcastRefusedException(502, serverText) }.failure)
        assertEquals(BroadcastFailure.REFUSED, failed { BroadcastRefusedException(400, serverText) }.failure)
        assertEquals(BroadcastFailure.NO_ANSWER, failed { BroadcastRefusedException(302, serverText) }.failure)
    }

    /** What the node said picks the sentence and is never shown; both backends pass the same daemon words on. */
    @Test
    fun aNodesReasonPicksTheSentenceOnBothBackends() {
        val reasons = mapOf(
            "min relay fee not met, 100 < 226000" to BroadcastFailure.FEE_TOO_LOW,
            "mempool min fee not met" to BroadcastFailure.FEE_TOO_LOW,
            "insufficient fee, rejecting replacement" to BroadcastFailure.FEE_TOO_LOW,
            "insufficient priority" to BroadcastFailure.FEE_TOO_LOW,
            "dust" to BroadcastFailure.TOO_SMALL,
            "Missing inputs" to BroadcastFailure.COINS_SPENT,
            "bad-txns-inputs-missingorspent" to BroadcastFailure.COINS_SPENT,
            "bad-txns-inputs-spent" to BroadcastFailure.COINS_SPENT,
            "txn-mempool-conflict" to BroadcastFailure.COINS_SPENT,
            "Transaction already in block chain" to BroadcastFailure.ALREADY_KNOWN,
            "txn-already-in-mempool" to BroadcastFailure.ALREADY_KNOWN,
            "mandatory-script-verify-flag-failed (Signature must be zero for failed CHECK(MULTI)SIG operation)" to BroadcastFailure.REFUSED,
            "something no list knows" to BroadcastFailure.REFUSED
        )
        for((reason, expected) in reasons) {
            assertEquals(expected, failed { electrum("the transaction was rejected by network rules.\n\n$reason\n[0200...]") }.failure, "Electrum: $reason")
            assertEquals(expected, failed { BroadcastRefusedException(400, "sendrawtransaction RPC error: {\"code\":-26,\"message\":\"$reason\"}") }.failure,
                "Esplora: $reason")
        }
        //already there overrules a second reason beside it: such a transaction may be out
        assertEquals(BroadcastFailure.ALREADY_KNOWN, failed { electrum("already in block chain; min relay fee not met") }.failure)
    }

    /** A private (MWEB) send goes out through the phone's own daemon: its failures are chosen by status, its words never shown. */
    @Test
    fun theMwebDaemonsFailureIsChosenByItsStatusAndItsWordsNeverShown() {
        fun grpc(status: io.grpc.Status, said: String = serverText) = status.withDescription(said).asRuntimeException()
        assertEquals(BroadcastFailure.NO_ANSWER, failed { grpc(io.grpc.Status.DEADLINE_EXCEEDED) }.failure)
        assertEquals(BroadcastFailure.NO_ANSWER, failed { grpc(io.grpc.Status.UNAVAILABLE) }.failure)
        assertEquals(BroadcastFailure.NO_ANSWER, failed { grpc(io.grpc.Status.CANCELLED) }.failure)
        assertEquals(BroadcastFailure.REFUSED, failed { grpc(io.grpc.Status.UNKNOWN) }.failure)
        assertEquals(BroadcastFailure.REFUSED, failed { grpc(io.grpc.Status.INVALID_ARGUMENT) }.failure)
        assertEquals(BroadcastFailure.FEE_TOO_LOW, failed { grpc(io.grpc.Status.UNKNOWN, "min relay fee not met, 100 < 2000") }.failure)
        assertEquals(BroadcastFailure.COINS_SPENT, failed { grpc(io.grpc.Status.UNKNOWN, "bad-txns-inputs-missingorspent") }.failure)
        assertEquals(BroadcastFailure.NOT_CONNECTED, failed(asked = false) { grpc(io.grpc.Status.UNAVAILABLE) }.failure,
            "the daemon could not be started: nothing was handed to it")
        val e = failed { grpc(io.grpc.Status.UNKNOWN) }
        assertEquals(BroadcastFailure.REFUSED.sentence, e.message)
        assertNull(e.cause)
        assertFalse(e.message!!.contains("555-0100"))
        //a status without words is a refusal like any other
        assertEquals(BroadcastFailure.REFUSED, failed { io.grpc.Status.INTERNAL.asRuntimeException() }.failure)
    }

    /** Only a failure before the transaction was handed to a server, or air-gapped mode, says that nothing was sent. */
    @Test
    fun onlyAFailureBeforeTheHandOverSaysNothingWasSent() {
        for(failure in BroadcastFailure.entries) {
            val saysNothingSent = failure.sentence.contains("Nothing was sent")
            assertEquals(failure == BroadcastFailure.NOT_CONNECTED || failure == BroadcastFailure.AIR_GAPPED, saysNothingSent, failure.name)
        }
    }

    /** A server whose broadcast call throws [failure] (or answers [answer]); nothing else of it is asked. */
    private class Server(private val failure: Exception? = null, private val answer: String = "id") : ChainBackend {
        val sent = ArrayList<String>()
        override suspend fun broadcast(txHex: String): String {
            sent.add(txHex)
            failure?.let { throw it }
            return answer
        }
        override suspend fun connect() {}
        override suspend fun balance(script: ByteArray): Balance = throw UnsupportedOperationException()
        override suspend fun history(script: ByteArray): List<HistoryEntry> = throw UnsupportedOperationException()
        override suspend fun unspent(script: ByteArray): List<Utxo> = throw UnsupportedOperationException()
        override suspend fun transaction(txid: String): TxDetail = throw UnsupportedOperationException()
        override suspend fun rawTransaction(txid: String): ByteArray = throw UnsupportedOperationException()
        override suspend fun tipHeight(): Long = throw UnsupportedOperationException()
        override suspend fun feeRate(targetBlocks: Int): Double? = throw UnsupportedOperationException()
        override fun close() {}
    }

    /** The call the repository's broadcast makes, for the Send screen and the PSBT screen alike. */
    @Test
    fun theSendsBroadcastGivesAFixedSentenceForBothBackendsAndForTheConnect() {
        fun through(server: Server) = assertFailsWith<BroadcastFailedException> {
            runBlocking { broadcastWithFixedFailure({ block -> block(server) }, "0200") }
        }
        val esplora = Server(BroadcastRefusedException(400, serverText))
        assertEquals(BroadcastFailure.REFUSED.sentence, through(esplora).message)
        assertEquals(listOf("0200"), esplora.sent, "one broadcast, of the transaction given")
        assertEquals(BroadcastFailure.FEE_TOO_LOW.sentence,
            through(Server(electrum("the transaction was rejected by network rules.\n\nmin relay fee not met, 100 < 226000"))).message)
        val connect = assertFailsWith<BroadcastFailedException> {
            runBlocking { broadcastWithFixedFailure({ throw IOException("https://evil.example answered HTTP 500: <b>call 555-0100</b>") }, "0200") }
        }
        assertEquals(BroadcastFailure.NOT_CONNECTED.sentence, connect.message)
        assertNull(connect.cause)
        assertEquals("id", runBlocking { broadcastWithFixedFailure({ block -> block(Server()) }, "0200") })
    }

    @Test
    fun cancellationAndASuccessPassThrough() {
        assertFailsWith<CancellationException> { runBlocking { fixedBroadcastFailure<String> { throw CancellationException("left") } } }
        assertEquals("id", runBlocking { fixedBroadcastFailure { reached -> reached(); "id" } })
    }
}
