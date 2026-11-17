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
import kotlin.test.assertTrue

/** What the ordinary Send says when a preview cannot be built: one of its own sentences, chosen by the kind of failure. */
class PreviewFailureTest {
    private val serverText = "<b>call 555-0100</b> https://evil.example/claim"
    private val ltc = ChainParams.of(Coin.LITECOIN, Network.MAINNET)
    private val key = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")).derivePath("m/84'/2'/0'/0/0")
    private val script = Scripts.outputScript(key.pubKey, ScriptType.P2WPKH)

    private fun coin(value: Long) = SpendableInput("ab".repeat(32), 0, value, key, ScriptType.P2WPKH)

    private fun builders(coins: List<SpendableInput>, amount: Long, sendMax: Boolean = false): Throwable =
        assertFailsWith<Throwable> { TxBuilder.build(coins, script, amount, 10, script, sendMax, ltc, chainTip = null) }

    @Test
    fun everySentenceIsFixedPlainAndItsOwn() {
        val sentences = PreviewFailure.entries.map { it.sentence }
        assertEquals(sentences.size, sentences.toSet().size)
        for(sentence in sentences) {
            assertTrue(sentence.isNotBlank() && sentence.endsWith("."), sentence)
            assertFalse(sentence.contains(0x2014.toChar()) || sentence.contains('\n') || sentence.contains("http"), sentence)
        }
    }

    /** The builder's own refusals, as the real builder throws them: a change of its wording fails here. */
    @Test
    fun theBuildersOwnRefusalsEachHaveTheirSentence() {
        assertEquals(PreviewFailure.NOT_ENOUGH, PreviewFailure.of(builders(listOf(coin(50_000)), 60_000)))
        assertEquals(PreviewFailure.NOTHING_TO_SPEND, PreviewFailure.of(builders(emptyList(), 60_000)))
        assertEquals(PreviewFailure.BELOW_DUST, PreviewFailure.of(builders(listOf(coin(50_000)), 10)))
        assertEquals(PreviewFailure.FEE_EATS_BALANCE, PreviewFailure.of(builders(listOf(coin(500)), 0, sendMax = true)))
        //the repository's own, by the words it throws them with
        assertEquals(PreviewFailure.NOT_ENOUGH, PreviewFailure.of(IllegalArgumentException("Insufficient MWEB funds: need 5 litoshis, have 3")))
        assertEquals(PreviewFailure.NOTHING_TO_SPEND,
            PreviewFailure.of(IllegalArgumentException("No private coins with 6+ confirmations yet")))
        assertEquals(PreviewFailure.BELOW_DUST, PreviewFailure.of(IllegalArgumentException("Amount too small")))
        assertEquals(PreviewFailure.FEE_EATS_BALANCE, PreviewFailure.of(IllegalArgumentException("Balance is too small to cover the peg-in fees")))
        assertEquals(PreviewFailure.BAD_ADDRESS, PreviewFailure.of(IllegalArgumentException("Not a valid MWEB address")))
        assertEquals(PreviewFailure.NOT_FOR_THIS_WALLET,
            PreviewFailure.of(IllegalArgumentException("Moving coins into MWEB needs a Native Segwit Litecoin wallet")))
        assertEquals(PreviewFailure.NOT_FOR_THIS_WALLET,
            PreviewFailure.of(IllegalArgumentException("Signing on another device is not available for this wallet")))
        assertEquals(PreviewFailure.NO_SERVER, PreviewFailure.of(IllegalStateException("Dogecoin Mainnet needs your own server")))
    }

    @Test
    fun aServersFailureIsChosenByItsTypeAndItsTextNeverShown() {
        val failures = mapOf<Throwable, PreviewFailure>(
            AirGappedException("Electrum") to PreviewFailure.AIR_GAPPED,
            RateLimitedException("https://evil.example answered HTTP 429: $serverText") to PreviewFailure.BUSY,
            java.net.SocketTimeoutException(serverText) to PreviewFailure.UNREACHABLE,
            java.net.UnknownHostException("evil.example") to PreviewFailure.UNREACHABLE,
            IOException("https://evil.example answered HTTP 500: $serverText") to PreviewFailure.BAD_ANSWER,
            ElectrumRpcException("Electrum error: $serverText", 1, serverText) to PreviewFailure.BAD_ANSWER,
            kotlinx.serialization.SerializationException("Unexpected JSON token at offset 0: $serverText") to PreviewFailure.BAD_ANSWER,
            io.grpc.Status.UNAVAILABLE.withDescription(serverText).asRuntimeException() to PreviewFailure.MWEB_UNAVAILABLE,
            IllegalStateException("Connection closed by server") to PreviewFailure.FAILED,
            NullPointerException() to PreviewFailure.FAILED,
            //a server's words dressed as a refusal of the builder's are still only a sentence of the app's
            NumberFormatException("Insufficient funds: $serverText") to PreviewFailure.FAILED
        )
        for((failure, expected) in failures) {
            val kind = PreviewFailure.of(failure)
            assertEquals(expected, kind, failure.toString())
            for(part in listOf("555-0100", "evil.example", "<b>")) {
                assertFalse(kind.sentence.contains(part))
            }
        }
        //whatever is thrown, what shows is one of the fixed sentences
        val shown = PreviewFailure.of(IllegalArgumentException("Insufficient funds: $serverText")).sentence
        assertTrue(shown in PreviewFailure.entries.map { it.sentence } && !shown.contains("evil"))
    }
}
