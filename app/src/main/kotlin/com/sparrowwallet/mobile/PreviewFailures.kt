package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.electrum.ElectrumRpcException
import com.sparrowwallet.mobile.electrum.RateLimitedException
import java.io.IOException

/**
 * Why the ordinary Send could not build a preview (or a PSBT), as the Send screen says it. Every sentence is written
 * here. Building a preview reads the wallet's coins and a fee rate from a server, so what it throws can hold a
 * server's own text (an Esplora body, an Electrum error, a URL): that text only picks the sentence, and is never shown.
 */
enum class PreviewFailure(val sentence: String) {
    AIR_GAPPED("Air-gapped mode is on, so the wallet cannot reach its server."),
    NO_SERVER("This coin needs your own server. Set one under Server, then try again."),
    UNREACHABLE("The server could not be reached. Check the connection, then try again."),
    BUSY("The server is busy. Try again in a minute."),
    BAD_ANSWER("The server gave an answer the wallet could not use. Try again, or choose another server."),
    MWEB_UNAVAILABLE("The private (MWEB) side could not build this just now. Try again in a moment."),
    NOT_ENOUGH("There are not enough funds for this amount and its fee."),
    NOTHING_TO_SPEND("No coins are ready to be spent yet. A coin needs 6 confirmations."),
    BELOW_DUST("This amount is too small for the network to pass on."),
    FEE_EATS_BALANCE("The balance is too small to cover the fee."),
    BAD_ADDRESS("This is not an address the wallet can pay."),
    NOT_FOR_THIS_WALLET("This wallet cannot make that payment."),
    FAILED("The transaction could not be built.");

    companion object {
        /**
         * The kind of [failure]. A server's failures are known by their type. A refusal of the wallet's own builders is
         * a plain IllegalArgumentException or IllegalStateException (what `require` and `check` throw, never a subclass,
         * which a parser's or a server reader's is) whose words begin as one of the refusals the builders are known to
         * make; those words are the app's own, and still only pick a sentence. Anything else could not be built.
         */
        fun of(failure: Throwable): PreviewFailure = when {
            failure is AirGappedException -> AIR_GAPPED
            failure is RateLimitedException -> BUSY
            failure is ElectrumRpcException || failure is kotlinx.serialization.SerializationException -> BAD_ANSWER
            failure is io.grpc.StatusRuntimeException || failure is io.grpc.StatusException -> MWEB_UNAVAILABLE
            //the backends' own complaint about an answer is a plain IOException; a connection's failure is one of its subclasses
            failure is IOException -> if(failure.javaClass == IOException::class.java) BAD_ANSWER else UNREACHABLE
            failure.javaClass == IllegalArgumentException::class.java || failure.javaClass == IllegalStateException::class.java ->
                refusal(failure.message)
            else -> FAILED
        }

        private fun refusal(said: String?): PreviewFailure {
            val words = said ?: return FAILED
            return REFUSALS.firstOrNull { (start, _) -> words.startsWith(start) }?.second
                ?: if(words.endsWith(NEEDS_OWN_SERVER)) NO_SERVER else FAILED
        }

        /** How the builders' refusals begin (TxBuilder's, and the repository's for MWEB sends and peg-ins), with their kind. */
        private val REFUSALS = listOf(
            "Insufficient funds" to NOT_ENOUGH,
            "Insufficient MWEB funds" to NOT_ENOUGH,
            "No spendable coins" to NOTHING_TO_SPEND,
            "No private coins" to NOTHING_TO_SPEND,
            "Amount is below" to BELOW_DUST,
            "Amount too small" to BELOW_DUST,
            "Balance is too small" to FEE_EATS_BALANCE,
            "Not a valid MWEB address" to BAD_ADDRESS,
            "Moving coins into MWEB needs" to NOT_FOR_THIS_WALLET,
            "Signing on another device is not available" to NOT_FOR_THIS_WALLET
        )

        /** How the repository's refusal of a coin without a public server ends. */
        private const val NEEDS_OWN_SERVER = "needs your own server"
    }
}
