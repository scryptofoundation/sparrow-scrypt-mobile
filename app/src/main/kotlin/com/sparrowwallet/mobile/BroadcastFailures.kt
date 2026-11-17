package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.electrum.BroadcastRefusedException
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.ElectrumRpcException
import com.sparrowwallet.mobile.electrum.RateLimitedException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Why an ordinary send's broadcast failed, as the Send screen says it. Every sentence is written here. What a server
 * answered (an Esplora body, an Electrum error) only picks the sentence and is never put into one: it is text a
 * stranger's server chose, and it can hold anything.
 *
 * Only [NOT_CONNECTED] and [AIR_GAPPED] say that nothing was sent: the transaction never left the phone. Every other
 * kind is about a transaction that was handed to a server, where a refusal can also be said of one that is already out
 * (a node answers "already in block chain" as an error), so those send the user to the wallet's transactions.
 */
enum class BroadcastFailure(val sentence: String) {
    AIR_GAPPED("Air-gapped mode is on. Nothing was sent."),
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
    REFUSED("The server did not accept the transaction.");

    companion object {
        /**
         * The kind of [failure]. [handedOver]: the transaction had been handed to a server's broadcast call when it
         * failed; before that nothing can have been sent. The kind comes from the exception's type, an Esplora
         * server's HTTP status, and for a refusal which of a few known reasons the node's words hold.
         */
        fun of(failure: Throwable, handedOver: Boolean): BroadcastFailure = when {
            failure is AirGappedException -> AIR_GAPPED
            !handedOver -> NOT_CONNECTED
            failure is RateLimitedException -> BUSY
            failure is ElectrumRpcException -> reason(failure.serverMessage)
            failure is BroadcastRefusedException -> when(failure.status) {
                404, 405 -> NOT_OFFERED
                408, 429 -> BUSY
                in 400..499 -> reason(failure.message)
                in 500..599 -> SERVER_FAILED
                else -> NO_ANSWER
            }
            else -> NO_ANSWER
        }

        /** The kind of a server's refusal, by what its node said ([said], lower-cased here and never shown). */
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
 * A failed broadcast of an ordinary send. Its message is the [failure]'s fixed sentence and it has no cause, so a screen
 * that shows the message shows that sentence and nothing a server wrote.
 */
class BroadcastFailedException(val failure: BroadcastFailure) : Exception(failure.sentence)

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
        throw BroadcastFailedException(BroadcastFailure.of(e, handedOver))
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
