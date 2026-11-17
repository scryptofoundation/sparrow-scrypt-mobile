package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.electrum.BroadcastRefusedException
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.ElectrumRpcException
import com.sparrowwallet.mobile.electrum.RateLimitedException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Why an ordinary send's broadcast failed, when the failure is not a refusal of the app's own ([ownSentence]), as the
 * Send screen says it. Every sentence is written here. What a server answered (an Esplora body, an Electrum error)
 * only picks the sentence and is never put into one: it is text a stranger's server chose, and it can hold anything.
 *
 * Only [NOT_CONNECTED] says that nothing was sent: the transaction never left the phone. Every other kind is about a
 * transaction that was handed to a server. [REFUSED] and the kinds beside it are said only of a refusal the coin's node
 * itself made and the server passed on; any other error ([NOT_CLEAR]) may come from a server whose node already took
 * the transaction, so it sends the user to the wallet's transactions before sending again.
 */
enum class BroadcastFailure(val sentence: String) {
    NOT_CONNECTED("The server could not be reached. Nothing was sent."),
    NO_ANSWER("The server did not answer, so it is not known whether the transaction went out. Check the wallet's transactions before you send again."),
    BUSY("The server is busy. Check the wallet's transactions in a minute, then try again."),
    NOT_OFFERED("This server does not take transactions. Choose another server, then try again."),
    SERVER_FAILED("The server could not handle the transaction just now. Check the wallet's transactions before you send again."),
    FEE_TOO_LOW("The server did not accept the transaction: its fee is too low for the network just now."),
    TOO_SMALL("The server did not accept the transaction: an amount in it is too small for the network to pass on."),
    COINS_SPENT("The server did not accept the transaction: a coin it spends is already spent, or the server does not know it. " +
        "Check the wallet's transactions."),
    ALREADY_KNOWN("The server says it already has this transaction. Check the wallet's transactions."),
    REFUSED("The server did not accept the transaction."),
    NOT_CLEAR("The server answered with an error, and it is not clear whether it took the transaction. " +
        "Check the wallet's transactions before you send again.");

    companion object {
        /**
         * The kind of [failure]. [handedOver]: the transaction had been handed to a server's broadcast call when it
         * failed; before that nothing can have been sent. The kind comes from the exception's type, an Esplora
         * server's HTTP status, and for a refusal which of a few known reasons the node's words hold.
         */
        fun of(failure: Throwable, handedOver: Boolean): BroadcastFailure = when {
            !handedOver -> NOT_CONNECTED
            failure is RateLimitedException -> BUSY
            failure is ElectrumRpcException -> nodeSaid(failure.serverMessage, ELECTRUM_NODE_SAID)
            failure is BroadcastRefusedException -> when(failure.status) {
                404, 405 -> NOT_OFFERED
                408, 429 -> BUSY
                in 400..499 -> nodeSaid(failure.message, ESPLORA_NODE_SAID)
                in 500..599 -> SERVER_FAILED
                else -> NO_ANSWER
            }
            //a private (MWEB) send goes out through the phone's own daemon: no answer in time, or an answer that refuses
            failure is io.grpc.StatusRuntimeException -> when(failure.status.code) {
                io.grpc.Status.Code.DEADLINE_EXCEEDED, io.grpc.Status.Code.UNAVAILABLE, io.grpc.Status.Code.CANCELLED -> NO_ANSWER
                else -> reason(failure.status.description)
            }
            else -> NO_ANSWER
        }

        /**
         * The kind of an error a server answered a broadcast with. Only what begins as the server passes its node's own
         * refusal on ([front]) is a refusal, of the kind its reason names; any other error is not known to be one.
         */
        private fun nodeSaid(said: String?, front: String): BroadcastFailure {
            val lower = said?.lowercase()?.trimStart() ?: return NOT_CLEAR
            return if(lower.startsWith(front)) reason(lower.substring(front.length)) else NOT_CLEAR
        }

        /** What ElectrumX and Fulcrum put in front of every error their daemon gives to a broadcast (lower case). */
        private const val ELECTRUM_NODE_SAID = "the transaction was rejected by network rules."

        /** How an Esplora server (electrs) begins the body of a daemon error to a broadcast (lower case). */
        private const val ESPLORA_NODE_SAID = "sendrawtransaction rpc error"

        /** The kind of a node's refusal, by what it said ([said], lower-cased here and never shown). */
        private fun reason(said: String?): BroadcastFailure {
            val lower = said?.lowercase() ?: return REFUSED
            return when {
                ALREADY.any { it in lower } -> ALREADY_KNOWN
                SPENT.any { it in lower } -> COINS_SPENT
                LOW_FEE.any { it in lower } -> FEE_TOO_LOW
                "dust" in lower -> TOO_SMALL
                else -> REFUSED
            }
        }

        private val ALREADY = listOf("already")
        private val SPENT = listOf("missing inputs", "missingorspent", "inputs-spent", "mempool-conflict")
        private val LOW_FEE = listOf("relay fee not met", "min fee not met", "insufficient fee", "insufficient priority")
    }
}

/**
 * A failed broadcast of an ordinary send. Its message is the [failure]'s fixed sentence, or the app's own words when
 * the failure was a refusal of the app's own ([failure] is then null); it has no cause. So a screen that shows the
 * message shows a sentence of the app's and nothing a server wrote.
 */
class BroadcastFailedException(val failure: BroadcastFailure?, sentence: String) : Exception(sentence)

/**
 * Runs a broadcast and turns whatever it throws into a [BroadcastFailedException]. [run] calls the function it is given
 * at the moment it hands the transaction to a server. Cancellation passes through.
 */
internal suspend fun <T> fixedBroadcastFailure(run: suspend (handedOver: () -> Unit) -> T): T {
    var handedOver = false
    try {
        return run { handedOver = true }
    } catch(e: CancellationException) {
        throw e
    } catch(e: Exception) {
        //the app's own refusal (air-gapped mode, a coin without a public server) shows as it always did
        ownSentence(e)?.let { throw BroadcastFailedException(null, it) }
        val failure = BroadcastFailure.of(e, handedOver)
        throw BroadcastFailedException(failure, failure.sentence)
    }
}

/**
 * Broadcasts [txHex] through the backend [connected] opens (the repository's connect with fallback), with every failure,
 * of the connect as of the broadcast, as a [BroadcastFailedException].
 */
internal suspend fun broadcastWithFixedFailure(connected: suspend (suspend (ChainBackend) -> String) -> String, txHex: String): String =
    fixedBroadcastFailure { handedOver ->
        connected { backend ->
            handedOver()
            backend.broadcast(txHex)
        }
    }
