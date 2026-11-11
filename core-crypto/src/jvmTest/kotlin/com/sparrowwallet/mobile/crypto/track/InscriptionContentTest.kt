package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.track.TrackFixtures.OP_0
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.OP_CHECKSIG
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.OP_ENDIF
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.OP_IF
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.body
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.doginalsSteps
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelope
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelopeWithFields
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.hash
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.op
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.push
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.reveal
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.script
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InscriptionContentTest {
    private fun at(n: Int) = hash(n) to 0

    /** An ord envelope of the given type and body, the body cut into 520 byte pushes as ord writes it. */
    private fun envelopeOf(contentType: String, data: ByteArray): List<ByteArray> =
        listOf(push(ByteArray(32) { 4 }), op(OP_CHECKSIG), op(OP_0), op(OP_IF), push("ord".encodeToByteArray()),
            push(byteArrayOf(1)), push(contentType.encodeToByteArray()), op(OP_0)) +
            data.toList().chunked(520).map { push(it.toByteArray()) } + op(OP_ENDIF)

    private fun text(bytes: ByteArray) = bytes.decodeToString()

    @Test
    fun ordText() {
        val tx = reveal(listOf(at(1)), listOf(script(envelope("hello"))), 10_000)
        val content = assertNotNull(InscriptionContent.ord(tx, 0))
        assertEquals("text/plain;charset=utf-8", content.contentType)
        assertNull(content.contentEncoding)
        assertEquals("hello", text(content.body))
    }

    @Test
    fun ordSecondEnvelopeAndLongBody() {
        val big = ByteArray(1_300) { 'x'.code.toByte() }
        val tx = reveal(listOf(at(1), at(2)), listOf(script(envelope("first")), script(envelopeOf("image/png", big))), 10_000)
        val content = assertNotNull(InscriptionContent.ord(tx, 1))
        assertEquals("image/png", content.contentType)
        assertContentEquals(big, content.body)
        assertNull(InscriptionContent.ord(tx, 2))
    }

    @Test
    fun contentEncodingAndFirstOccurrenceWins() {
        val tx = reveal(listOf(at(1)), listOf(script(
            envelopeWithFields("zipped", push(byteArrayOf(9)), push("br".encodeToByteArray()))
        )), 10_000)
        val content = assertNotNull(InscriptionContent.ord(tx, 0))
        assertEquals("br", content.contentEncoding)
        assertEquals("zipped", text(content.body))

        //A second content type after the first is ignored
        val twice = envelopeWithFields("x", push(byteArrayOf(1)), push("text/other".encodeToByteArray()))
        val first = assertNotNull(InscriptionContent.ord(reveal(listOf(at(1)), listOf(script(twice)), 10_000), 0))
        assertEquals("text/plain;charset=utf-8", first.contentType)
    }

    @Test
    fun bodyIsThePushesAfterOpZeroUpToEndif() {
        val script = script(listOf(op(OP_0), op(OP_IF), push("ord".encodeToByteArray()), op(OP_0),
            push("ab".encodeToByteArray()), push("cd".encodeToByteArray()), push(ByteArray(0)), push("ef".encodeToByteArray()),
            op(OP_ENDIF), push("after".encodeToByteArray())))
        val content = assertNotNull(InscriptionContent.ord(reveal(listOf(at(1)), listOf(script), 10_000), 0))
        assertNull(content.contentType)
        assertEquals("abcdef", text(content.body))
    }

    @Test
    fun envelopeWithoutBodyIsEmpty() {
        val script = script(listOf(op(OP_0), op(OP_IF), push("ord".encodeToByteArray()), push(byteArrayOf(1)),
            push("text/plain".encodeToByteArray()), op(OP_ENDIF)))
        val content = assertNotNull(InscriptionContent.ord(reveal(listOf(at(1)), listOf(script), 10_000), 0))
        assertEquals("text/plain", content.contentType)
        assertEquals(0, content.body.size)
    }

    @Test
    fun noEnvelopeAndMalformedScripts() {
        assertNull(InscriptionContent.ord(reveal(listOf(at(1)), listOf(null), 10_000), 0))
        //A push that runs past the end: the chunks before it are still read, nothing throws
        val cut = script(envelope("hello")).let { it.copyOfRange(0, it.size - 3) }
        assertNotNull(InscriptionContent.ord(reveal(listOf(at(1)), listOf(cut), 10_000), 0))
        assertNull(InscriptionContent.ord(reveal(listOf(at(1)), listOf(byteArrayOf(0x4d, 5)), 10_000), 0))
    }

    @Test
    fun doginalsContent() {
        val data = body(4_000)
        val steps = doginalsSteps(data, hash(9))
        val content = assertNotNull(InscriptionContent.doginals(steps))
        assertEquals("image/png", content.contentType)
        assertNull(content.contentEncoding)
        assertContentEquals(data, content.body)
        assertNull(InscriptionContent.doginals(steps.drop(1)))
        assertNull(InscriptionContent.doginals(emptyList()))
    }

    @Test
    fun fromChain() = runBlocking {
        val chain = FakeChainSource()
        val reveal = chain.add(reveal(listOf(at(1)), listOf(script(envelope("on chain"))), 10_000), 100)
        assertEquals("on chain", text(assertNotNull(InscriptionContent.fromChain(reveal.txid + "i0", reveal.txid, chain, 1_000_000)).body))
        //An ord id needs no last step
        assertEquals("on chain", text(assertNotNull(InscriptionContent.fromChain(reveal.txid + "i0", null, chain, 1_000_000)).body))
        assertNull(InscriptionContent.fromChain(reveal.txid + "i1", null, chain, 1_000_000))

        val data = body(4_000)
        val steps = doginalsSteps(data, hash(9))
        for(step in steps) {
            chain.add(step, 101)
        }
        val doginal = InscriptionContent.fromChain(steps.first().txid + "i0", steps.last().txid, chain, 1_000_000)
        assertContentEquals(data, assertNotNull(doginal).body)
        assertNull(InscriptionContent.fromChain(steps.first().txid + "i0", null, chain, 1_000_000))
        //The last step of a chain whose genesis is another transaction does not give this id
        assertNull(InscriptionContent.fromChain(steps[1].txid + "i0", steps.last().txid, chain, 1_000_000))
        assertNull(InscriptionContent.fromChain("nonsense", null, chain, 1_000_000))
        assertNull(InscriptionContent.fromChain(steps.first().txid + "ix", null, chain, 1_000_000))
    }

    @Test
    fun bodyOverTheLimitIsNotAssembled() = runBlocking {
        val chain = FakeChainSource()
        val big = ByteArray(1_300) { 7 }
        val reveal = chain.add(reveal(listOf(at(1)), listOf(script(envelopeOf("image/png", big))), 10_000), 100)
        assertEquals(1_300, assertNotNull(InscriptionContent.fromChain(reveal.txid + "i0", null, chain, 1_300)).body.size)
        assertNull(InscriptionContent.fromChain(reveal.txid + "i0", null, chain, 1_299))
        assertNull(InscriptionContent.ord(reveal, 0, 1_299))

        val data = body(4_000)
        val steps = doginalsSteps(data, hash(9))
        for(step in steps) {
            chain.add(step, 101)
        }
        assertContentEquals(data, assertNotNull(InscriptionContent.fromChain(steps.first().txid + "i0", steps.last().txid, chain, 4_000)).body)
        assertNull(InscriptionContent.fromChain(steps.first().txid + "i0", steps.last().txid, chain, 3_999))
        //A limit far below the chain stops the walk before it fetches every step
        chain.transactionCalls = 0
        assertNull(InscriptionContent.fromChain(steps.first().txid + "i0", steps.last().txid, chain, 100))
        assertTrue(chain.transactionCalls < steps.size + 1, "walked ${chain.transactionCalls} for ${steps.size} steps")
    }
}
