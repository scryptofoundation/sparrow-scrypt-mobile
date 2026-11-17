package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.electrum.ElectrumRpcException
import com.sparrowwallet.mobile.electrum.RateLimitedException
import java.io.IOException

/**
 * Why the ordinary Send could not build a preview (or a PSBT), when the failure is not a refusal of the app's own
 * ([ownSentence]). Every sentence is written here. Building a preview reads the wallet's coins and a fee rate from a
 * server, so what it throws can hold a server's own text (an Esplora body, an Electrum error's JSON, a URL, a piece of
 * an answer): that text is never shown, and only the failure's type picks the sentence.
 */
enum class PreviewFailure(val sentence: String) {
    UNREACHABLE("The server could not be reached. Check the connection, then try again."),
    BUSY("The server is busy. Try again in a minute."),
    BAD_ANSWER("The server gave an answer the wallet could not use. Try again, or choose another server."),
    MWEB_UNAVAILABLE("The private (MWEB) side could not build this just now. Try again in a moment."),
    FAILED("The transaction could not be built.");

    companion object {
        /** What the screen shows for [failure]: the app's own sentence when it is one, else the fixed sentence of its kind. */
        fun sentence(failure: Throwable): String = ownSentence(failure) ?: of(failure).sentence

        /** The kind of a [failure] that is not the app's own refusal, by its type. Its text is not read. */
        fun of(failure: Throwable): PreviewFailure = when {
            failure is RateLimitedException -> BUSY
            failure is ElectrumRpcException || failure is kotlinx.serialization.SerializationException -> BAD_ANSWER
            failure is io.grpc.StatusRuntimeException || failure is io.grpc.StatusException -> MWEB_UNAVAILABLE
            //the backends' own complaint about an answer is a plain IOException; a connection's failure is one of its subclasses
            failure is IOException -> if(failure.javaClass == IOException::class.java) BAD_ANSWER else UNREACHABLE
            else -> FAILED
        }
    }
}

/**
 * The words of [failure] when they are the app's own fixed sentence, which the screens show as they always did; null
 * when they are not, or cannot be told to be. The app's own are: air-gapped mode's refusal; a plain
 * IllegalArgumentException or IllegalStateException (what `require` and `check` throw, never a subclass: a parser's or
 * a server reader's failure is one, or another type altogether) whose words begin as one of the refusals the wallet's
 * builders and the repository are known to make, or end as the repository's for a coin without a public server.
 * Everything after such a beginning is the builder's own arithmetic. The address reader's refusals of an address of the
 * wrong network, of another coin, of MWEB and of Taproot where it is not active are told by their whole form
 * ([ADDRESS_REFUSALS]), in which only a name of one word (letters, digits) varies. A server can put no text of its own
 * through here.
 */
internal fun ownSentence(failure: Throwable): String? {
    if(failure is AirGappedException) {
        return failure.message
    }
    if(failure.javaClass != IllegalArgumentException::class.java && failure.javaClass != IllegalStateException::class.java) {
        return null
    }
    val words = failure.message ?: return null
    return words.takeIf { OWN_BEGINNINGS.any { words.startsWith(it) } || words.endsWith(NEEDS_OWN_SERVER) || ADDRESS_REFUSALS.any { it.matches(words) } }
}

/**
 * The address reader's refusals (Addresses.toScriptPubKey), each in its whole form: an address of the coin's other
 * network, an address of another coin, an MWEB address, a Taproot address on a chain without Taproot. What varies in
 * them is a coin's or a network's name, one word each. PreviewFailureTest holds the real reader's to it.
 */
private val ADDRESS_REFUSALS = listOf(
    Regex("That's an? [A-Za-z0-9]+ address, but this wallet is on [A-Za-z0-9]+"),
    Regex("Not an? [A-Za-z]+ address"),
    Regex("MWEB destinations aren't supported on mobile yet"),
    Regex("Taproot is not active on [A-Za-z]+ [A-Za-z0-9]+")
)

/** How the refusals of TxBuilder, and of the repository for MWEB sends and peg-ins, begin. PreviewFailureTest holds the real ones to it. */
private val OWN_BEGINNINGS = listOf(
    "Insufficient funds", "Insufficient MWEB funds", "No spendable coins", "No private coins", "Amount is below", "Amount too small",
    "Balance is too small", "Fee rate must be at least", "Not a valid MWEB address", "Moving coins into MWEB needs",
    "Signing on another device is not available", "Peg-in max calculation mismatch"
)

/** How the repository's refusal of a coin without a public server ends. */
private const val NEEDS_OWN_SERVER = "needs your own server"
