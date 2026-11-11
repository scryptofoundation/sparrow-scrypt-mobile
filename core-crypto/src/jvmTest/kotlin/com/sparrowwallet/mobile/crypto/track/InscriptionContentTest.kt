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
import kotlin.test.assertFalse
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
        val truncated = assertNotNull(InscriptionContent.ord(reveal(listOf(at(1)), listOf(cut), 10_000), 0))
        assertEquals("text/plain;charset=utf-8", truncated.contentType)
        assertEquals(0, truncated.body.size)
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
        //Only the canonical index spelling names an inscription
        val genesis = steps.first().txid
        assertNull(InscriptionContent.fromChain(genesis + "i+0", steps.last().txid, chain, 1_000_000))
        assertNull(InscriptionContent.fromChain(genesis + "i00", steps.last().txid, chain, 1_000_000))
        assertNull(InscriptionContent.fromChain(genesis.uppercase() + "i0", steps.last().txid, chain, 1_000_000))
    }

    @Test
    fun aChainWalkedOnlyFromItsFirstStepIsNotTheWholeContent() = runBlocking {
        val chain = FakeChainSource()
        val data = body(4_000)
        val steps = doginalsSteps(data, hash(9))
        assertTrue(steps.size > 1)
        for(step in steps) {
            chain.add(step, 101)
        }
        val genesis = steps.first().txid
        //An indexer-named id carries the first step as its last: the walk holds only the genesis, so no content
        assertNull(InscriptionContent.fromChain(genesis + "i0", genesis, chain, 1_000_000))
        assertNull(InscriptionContent.doginals(steps.dropLast(1)))
        assertNull(InscriptionContent.doginals(steps.take(1)))
        assertContentEquals(data, assertNotNull(InscriptionContent.doginals(steps)).body)
    }

    @Test
    fun aSingleStepInscriptionWorksWithItsGenesisAsTheLastStep() = runBlocking {
        val chain = FakeChainSource()
        val data = body(100)
        val steps = doginalsSteps(data, hash(9))
        assertEquals(1, steps.size)
        chain.add(steps.first(), 101)
        val genesis = steps.first().txid
        assertContentEquals(data, assertNotNull(InscriptionContent.fromChain(genesis + "i0", genesis, chain, 1_000_000)).body)
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
        //A limit far below the chain stops the walk once the payload read passes the walk allowance: one fetch for the
        //named transaction, then one per step walked
        chain.transactionCalls = 0
        assertNull(InscriptionContent.fromChain(steps.first().txid + "i0", steps.last().txid, chain, 100))
        val walked = stepsWalked(steps, 100)
        assertTrue(walked < steps.size)
        assertEquals(1 + walked, chain.transactionCalls)
    }

    /**
     * Over the limit, readFromChain still says what the inscription is: its type and size, with no body assembled (the
     * parts are counted, not kept). fromChain keeps answering null for it.
     */
    @Test
    fun oversizedContentGivesItsTypeAndSizeWithoutTheBody() = runBlocking {
        val chain = FakeChainSource()
        val big = ByteArray(1_300) { 7 }
        val reveal = chain.add(reveal(listOf(at(1)), listOf(script(envelopeOf("image/png", big))), 10_000), 100)
        val ord = assertNotNull(InscriptionContent.readFromChain(reveal.txid + "i0", null, chain, 1_299))
        assertEquals("image/png", ord.contentType)
        assertEquals(0, ord.body.size)
        assertEquals(1_300L, ord.size)
        assertFalse(ord.complete)
        assertFalse(ord.sizeOver)
        assertNull(InscriptionContent.fromChain(reveal.txid + "i0", null, chain, 1_299))
        //Within the limit it is the content, complete
        val whole = assertNotNull(InscriptionContent.readFromChain(reveal.txid + "i0", null, chain, 1_300))
        assertTrue(whole.complete)
        assertEquals(1_300L, whole.size)

        val data = body(4_000)
        val steps = doginalsSteps(data, hash(9))
        for(step in steps) {
            chain.add(step, 101)
        }
        //Over the limit once assembled: type and exact size
        val doginal = assertNotNull(InscriptionContent.readFromChain(steps.first().txid + "i0", steps.last().txid, chain, 3_999))
        assertEquals("image/png", doginal.contentType)
        assertEquals(4_000L, doginal.size)
        assertEquals(0, doginal.body.size)
        assertFalse(doginal.complete)
        //Far over: the walk stops early, so neither the type nor the full size is known, only that it is over the limit
        val walked = assertNotNull(InscriptionContent.readFromChain(steps.first().txid + "i0", steps.last().txid, chain, 100))
        assertNull(walked.contentType)
        assertEquals(100L, walked.size)
        assertTrue(walked.sizeOver)
        assertFalse(walked.complete)
        assertEquals(0, walked.body.size)
        //An id the transactions do not hold is still nothing
        assertNull(InscriptionContent.readFromChain(reveal.txid + "i1", null, chain, 1_299))
    }

    private fun payloadOf(step: com.sparrowwallet.mobile.crypto.RawTx.Parsed): Int {
        val chunks = com.sparrowwallet.mobile.crypto.ScriptReader.chunksUpToError(step.scriptSigs[0])
        return chunks.subList(0, chunks.size - 2).sumOf { it.data?.size ?: 0 }
    }

    /** The steps walked back from the last before the data pushes read pass maxBytes plus 2% and 1 KB. */
    private fun stepsWalked(steps: List<com.sparrowwallet.mobile.crypto.RawTx.Parsed>, maxBytes: Int): Int {
        var payload = 0L
        var walked = 0
        for(step in steps.reversed()) {
            walked++
            payload += payloadOf(step)
            if(payload > maxBytes.toLong() + maxBytes / 50 + 1_024) break
        }
        return walked
    }

    @Test
    fun aBodyNearTheLimitMadeOfFullPiecesIsReturned() = runBlocking {
        val chain = FakeChainSource()
        val data = body(50_000)
        val steps = doginalsSteps(data, hash(9))
        assertTrue(steps.size > 30)
        for(step in steps) {
            chain.add(step, 101)
        }
        assertContentEquals(data, assertNotNull(InscriptionContent.fromChain(steps.first().txid + "i0", steps.last().txid, chain, 50_000)).body)
        assertNull(InscriptionContent.fromChain(steps.first().txid + "i0", steps.last().txid, chain, 49_999))
        //Far over: the walk stops early, exactly when the payload read passes the allowance
        chain.transactionCalls = 0
        assertNull(InscriptionContent.fromChain(steps.first().txid + "i0", steps.last().txid, chain, 20_000))
        val walked = stepsWalked(steps, 20_000)
        assertTrue(walked < steps.size)
        assertEquals(1 + walked, chain.transactionCalls)
    }

    @Test
    fun numberingFollowsTheLocatorsIds() {
        //Push A writes "ord" with OP_PUSHDATA1, so it lacks the minimal envelope bytes the locator numbers by; push B is normal
        val a = script(listOf(op(OP_0), op(OP_IF), byteArrayOf(0x4c, 3) + "ord".encodeToByteArray(), op(OP_0),
            push("X".encodeToByteArray()), op(OP_ENDIF)))
        val b = script(envelope("Y"))
        val tx = reveal(listOf(at(1), at(2)), listOf(a, b), 10_000)
        assertEquals(listOf(tx.txid + "i0"), InscriptionLocator.ordIds(tx))
        assertEquals("Y", text(assertNotNull(InscriptionContent.ord(tx, 0)).body))
        assertNull(InscriptionContent.ord(tx, 1))
    }

    @Test
    fun anEnvelopeInsideAPushedBlobTakesAnIdAndHasNoContent() {
        //Deliberate divergence from the Java: the locator counts these bytes as an envelope, so the id stays in step
        val blob = script(listOf(push(byteArrayOf(0x00, 0x63, 0x03, 0x6f, 0x72, 0x64))))
        val tx = reveal(listOf(at(1), at(2)), listOf(blob, script(envelope("real"))), 10_000)
        assertEquals(listOf(tx.txid + "i0", tx.txid + "i1"), InscriptionLocator.ordIds(tx))
        assertNull(InscriptionContent.ord(tx, 0))
        assertEquals("real", text(assertNotNull(InscriptionContent.ord(tx, 1)).body))
    }
}
