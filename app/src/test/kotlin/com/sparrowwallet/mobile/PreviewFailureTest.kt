package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.SpendableInput
import com.sparrowwallet.mobile.crypto.TxBuilder
import com.sparrowwallet.mobile.electrum.ElectrumRpcException
import com.sparrowwallet.mobile.electrum.RateLimitedException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the ordinary Send says when a preview cannot be built: the app's own sentence where the failure is the app's own
 * refusal, and otherwise one fixed sentence chosen by the kind of failure, never a server's text.
 */
class PreviewFailureTest {
    private val serverText = "<b>call 555-0100</b> https://evil.example/claim"
    private val ltc = ChainParams.of(Coin.LITECOIN, Network.MAINNET)
    private val key = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")).derivePath("m/84'/2'/0'/0/0")
    private val script = Scripts.outputScript(key.pubKey, ScriptType.P2WPKH)

    private fun coin(value: Long) = SpendableInput("ab".repeat(32), 0, value, key, ScriptType.P2WPKH)

    private fun builders(coins: List<SpendableInput>, amount: Long, sendMax: Boolean = false, rate: Long = 10): Throwable =
        assertFailsWith<Throwable> { TxBuilder.build(coins, script, amount, rate, script, sendMax, ltc, chainTip = null) }

    @Test
    fun everySentenceIsFixedPlainAndItsOwn() {
        val sentences = PreviewFailure.entries.map { it.sentence }
        assertEquals(sentences.size, sentences.toSet().size)
        for(sentence in sentences) {
            assertTrue(sentence.isNotBlank() && sentence.endsWith("."), sentence)
            assertFalse(sentence.contains(0x2014.toChar()) || sentence.contains('\n') || sentence.contains("http"), sentence)
        }
    }

    /**
     * The builder's own refusals, as the real builder throws them, are the app's own words and show as they always
     * did. A change of the builder's wording that this no longer knows fails here.
     */
    @Test
    fun theAppsOwnRefusalsShowAsTheyWere() {
        for(refusal in listOf(builders(listOf(coin(50_000)), 60_000), builders(emptyList(), 60_000), builders(listOf(coin(50_000)), 10),
            builders(listOf(coin(500)), 0, sendMax = true), builders(listOf(coin(50_000)), 20_000, rate = 0))) {
            assertEquals(refusal.message, ownSentence(refusal), refusal.message)
            assertEquals(refusal.message, PreviewFailure.sentence(refusal))
        }
        assertTrue(builders(listOf(coin(50_000)), 60_000).message!!.startsWith("Insufficient funds: need "))
        //the repository's own, by the words it throws them with
        for(words in listOf("Insufficient MWEB funds: need 5 litoshis, have 3", "No private coins with 6+ confirmations yet", "Amount too small",
            "Balance is too small to cover the peg-in fees", "Not a valid MWEB address", "Moving coins into MWEB needs a Native Segwit Litecoin wallet",
            "Signing on another device is not available for this wallet", "Peg-in max calculation mismatch")) {
            assertEquals(words, PreviewFailure.sentence(IllegalArgumentException(words)), words)
        }
        assertEquals("Dogecoin Mainnet needs your own server", PreviewFailure.sentence(IllegalStateException("Dogecoin Mainnet needs your own server")))
        assertEquals("Air-gapped mode is on: Electrum is disabled", PreviewFailure.sentence(AirGappedException("Electrum")))
    }

    /** Only a plain refusal that begins as one of the app's own is the app's own: a subclass, or other words, is not. */
    @Test
    fun onlyTheAppsOwnWordsAreShownAsTheyAre() {
        assertNull(ownSentence(NumberFormatException("Insufficient funds: $serverText")), "a parser's exception is not the builder's")
        assertNull(ownSentence(IllegalArgumentException(serverText)))
        assertNull(ownSentence(IllegalStateException("Connection closed by server")))
        assertNull(ownSentence(IOException("Insufficient funds")))
        assertNull(ownSentence(ElectrumRpcException("Insufficient funds", 1, "Insufficient funds")))
        assertNull(ownSentence(IllegalArgumentException()))
        assertNull(ownSentence(kotlinx.serialization.SerializationException("Insufficient funds")))
    }

    @Test
    fun aServersFailureIsChosenByItsTypeAndItsTextNeverShown() {
        val failures = mapOf<Throwable, PreviewFailure>(
            RateLimitedException("https://evil.example answered HTTP 429: $serverText") to PreviewFailure.BUSY,
            java.net.SocketTimeoutException(serverText) to PreviewFailure.UNREACHABLE,
            java.net.UnknownHostException("evil.example") to PreviewFailure.UNREACHABLE,
            IOException("https://evil.example answered HTTP 500: $serverText") to PreviewFailure.BAD_ANSWER,
            IOException("The server's block hash is not 64 hex characters: $serverText") to PreviewFailure.BAD_ANSWER,
            ElectrumRpcException("Electrum error: $serverText", 1, serverText) to PreviewFailure.BAD_ANSWER,
            kotlinx.serialization.SerializationException("Unexpected JSON token at offset 0: $serverText") to PreviewFailure.BAD_ANSWER,
            io.grpc.Status.UNAVAILABLE.withDescription(serverText).asRuntimeException() to PreviewFailure.MWEB_UNAVAILABLE,
            IllegalStateException("Connection closed by server") to PreviewFailure.FAILED,
            IllegalArgumentException(serverText) to PreviewFailure.FAILED,
            NullPointerException() to PreviewFailure.FAILED,
            NumberFormatException("Insufficient funds: $serverText") to PreviewFailure.FAILED
        )
        for((failure, expected) in failures) {
            assertEquals(expected, PreviewFailure.of(failure), failure.toString())
            val shown = PreviewFailure.sentence(failure)
            assertEquals(expected.sentence, shown, failure.toString())
            for(part in listOf("555-0100", "evil.example", "<b>")) {
                assertFalse(shown.contains(part))
            }
        }
    }
}
