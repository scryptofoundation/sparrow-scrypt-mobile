package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.ScriptReader
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelope
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelopeBody
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelopeWithFields
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.envelopeWithPointer
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.hash
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.op
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.opNumber
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.push
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.reveal
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.script
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.spend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InscriptionLocatorTest {
    private fun at(n: Int) = hash(n) to 0

    @Test
    fun ordInputZero() {
        val reveal = reveal(listOf(at(1)), listOf(script(envelope("a"))), 10_000)
        assertEquals(listOf(Creation(reveal.txid + "i0", 0, 0)), InscriptionLocator.ord(reveal, listOf(10_000L)))
    }

    @Test
    fun ordSecondInput() {
        val reveal = reveal(listOf(at(1), at(2)), listOf(null, script(envelope("b"))), 15_000, 5_000)
        assertEquals(listOf(Creation(reveal.txid + "i0", 0, 10_000)), InscriptionLocator.ord(reveal, listOf(10_000L, 10_000L)))
    }

    @Test
    fun ordPointer() {
        val placed = reveal(listOf(at(1)), listOf(script(envelopeWithPointer("c", 12_000))), 10_000, 5_000)
        assertEquals(listOf(Creation(placed.txid + "i0", 1, 2_000)), InscriptionLocator.ord(placed, listOf(15_000L)))

        val ignored = reveal(listOf(at(1)), listOf(script(envelopeWithPointer("c", 20_000))), 10_000, 5_000)
        assertEquals(listOf(Creation(ignored.txid + "i0", 0, 0)), InscriptionLocator.ord(ignored, listOf(15_000L)))
    }

    @Test
    fun ordToFee() {
        val reveal = reveal(listOf(at(1), at(2)), listOf(null, script(envelope("d"))), 5_000)
        assertEquals(listOf(Creation(reveal.txid + "i0", Piece.FEE, 0)), InscriptionLocator.ord(reveal, listOf(5_000L, 5_000L)))
    }

    @Test
    fun ordTwoEnvelopesOneInput() {
        //The second envelope without its key and OP_CHECKSIG, as the Java case drops its first two chunks
        val both = script(envelope("e") + envelopeBody("f"))
        val reveal = reveal(listOf(at(1)), listOf(both), 10_000)
        assertEquals(
            listOf(Creation(reveal.txid + "i0", 0, 0), Creation(reveal.txid + "i1", 0, 0)),
            InscriptionLocator.ord(reveal, listOf(10_000L))
        )
    }

    @Test
    fun ordMalformedStillCounts() {
        //A 32 byte push, OP_CHECKSIG, OP_FALSE OP_IF push "ord", then a push that claims 10 bytes but has 2
        val broken = byteArrayOf(
            0x20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xac.toByte(), 0x00, 0x63, 0x03, 0x6f, 0x72, 0x64, 0x0a, 0x01, 0x02
        )
        val reveal = reveal(listOf(at(1)), listOf(broken), 10_000)
        assertEquals(listOf(Creation(reveal.txid + "i0", 0, 0)), InscriptionLocator.ord(reveal, listOf(10_000L)))
    }

    /** Review I5: ord reads tag and value pushes written as OP_N, and a pointer padded past 8 bytes with zeros. */
    @Test
    fun pointerAsOpNumberAndPadded() {
        val small = reveal(listOf(at(1)), listOf(script(envelopeWithFields("p", opNumber(2), opNumber(5)))), 10_000)
        assertEquals(listOf(Creation(small.txid + "i0", 0, 5)), InscriptionLocator.ord(small, listOf(10_000L)))

        val padded = byteArrayOf(0xe0.toByte(), 0x2e, 0, 0, 0, 0, 0, 0, 0, 0)
        val wide = reveal(listOf(at(1)), listOf(script(envelopeWithFields("p", push(byteArrayOf(2)), push(padded)))), 10_000, 5_000)
        assertEquals(listOf(Creation(wide.txid + "i0", 1, 2_000)), InscriptionLocator.ord(wide, listOf(15_000L)))
    }

    /** Review I5: a pointer ord could read but this code cannot place puts the inscription anywhere in the transaction. */
    @Test
    fun unreadablePointerIsAnywhere() {
        val huge = byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1)
        val reveal = reveal(listOf(at(1)), listOf(script(envelopeWithFields("p", push(byteArrayOf(2)), push(huge)))), 10_000, 5_000)
        assertEquals(listOf(Creation(reveal.txid + "i0", Creation.ANYWHERE, -1)), InscriptionLocator.ord(reveal, listOf(15_000L)))
    }

    /** Review I4: ord-dogecoin follows the pushes, not the redeem script, so a piece under another redeem script still counts. */
    @Test
    fun doginalsUnderAnotherRedeemScript() {
        val piece = TrackFixtures.doginalsPieces("image/png", TrackFixtures.body(600)).first()
        val chunks = TrackFixtures.doginalsStepChunks(piece).dropLast(1) + push(byteArrayOf(0x51, 0x51))
        val other = TrackFixtures.tx(listOf(at(9)), listOf(100_000L), scriptSigs = listOf(script(chunks)), version = 1)
        val found = assertNotNull(InscriptionLocator.doginals(other))
        assertTrue(found.genesis)
        assertTrue(found.last)
    }

    @Test
    fun ordIdsNeedNoInputValues() {
        val reveal = reveal(listOf(at(1), at(2)), listOf(null, script(envelope("b"))), 15_000, 5_000)
        assertEquals(listOf(reveal.txid + "i0"), InscriptionLocator.ordIds(reveal))
        assertEquals(emptyList(), InscriptionLocator.ordIds(spend(listOf(at(1)), 1_000)))
    }

    @Test
    fun plainSpendCreatesNothing() {
        val plain = spend(listOf(at(1)), 10_000)
        assertEquals(emptyList(), InscriptionLocator.ord(plain, listOf(10_000L)))
        assertNull(InscriptionLocator.doginals(plain))
    }

    @Test
    fun doginalsChainSteps() {
        val steps = TrackFixtures.doginalsSteps(TrackFixtures.body(4_000), hash(9))
        assertTrue(steps.size >= 3, "a 4,000-byte body takes several steps")

        val first = assertNotNull(InscriptionLocator.doginals(steps.first()))
        assertTrue(first.genesis)
        assertFalse(first.last)
        assertEquals(hash(9), first.previousTxid)

        val middle = assertNotNull(InscriptionLocator.doginals(steps[1]))
        assertFalse(middle.genesis)
        assertFalse(middle.last)

        val last = assertNotNull(InscriptionLocator.doginals(steps.last()))
        assertFalse(last.genesis)
        assertTrue(last.last)
        assertEquals(steps[steps.size - 2].txid, last.previousTxid)
    }

    @Test
    fun doginalsSingleStepIsGenesisAndLast() {
        val steps = TrackFixtures.doginalsSteps(TrackFixtures.body(600), hash(9))
        assertEquals(1, steps.size)
        val only = assertNotNull(InscriptionLocator.doginals(steps.first()))
        assertTrue(only.genesis)
        assertTrue(only.last)
    }

    @Test
    fun numberChunks() {
        assertEquals(0, InscriptionLocator.number(ScriptReader.Chunk(0x00, ByteArray(0))))
        assertEquals(16, InscriptionLocator.number(ScriptReader.Chunk(0x60, null)))
        assertEquals(100, InscriptionLocator.number(ScriptReader.Chunk(1, byteArrayOf(100))))
        assertEquals(300, InscriptionLocator.number(ScriptReader.Chunk(2, byteArrayOf(44, 1))))
    }

    /** Added: a complete envelope before a truncated push keeps its pointer, as drongo keeps the chunks parsed so far. */
    @Test
    fun envelopeBeforeATruncatedPushKeepsItsPointer() {
        val truncated = script(envelopeWithPointer("c", 12_000)) + byteArrayOf(0x0a, 0x01)
        val reveal = reveal(listOf(at(1)), listOf(truncated), 10_000, 5_000)
        assertEquals(listOf(Creation(reveal.txid + "i0", 1, 2_000)), InscriptionLocator.ord(reveal, listOf(15_000L)))
    }

    /** Added: an OP_0 chunk is no push at all as a Doginals last element. */
    @Test
    fun nonPushLastElementIsNotDoginals() {
        val sig = script(listOf(push(byteArrayOf(1)), push(byteArrayOf(2)), push(byteArrayOf(3)), op(0x51)))
        val tx = TrackFixtures.tx(listOf(at(9)), listOf(1_000L), scriptSigs = listOf(sig), version = 1)
        assertNull(InscriptionLocator.doginals(tx))
    }
}
