package com.sparrowwallet.mobile.crypto.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitFlowTest {
    private val prev0 = "0000000000000000000000000000000000000000000000000000000000000a00"
    private val prev1 = "0000000000000000000000000000000000000000000000000000000000000b00"

    private val tx = TrackFixtures.spendZero(listOf(prev0, prev1), 12_000, 2_000)
    private val inputs = listOf(10_000L, 5_000L)

    @Test
    fun backOutputOneLandsInSecondInput() {
        val pieces = UnitFlow.back(tx, inputs, Piece(tx.txid, 1, UnitRange(0, 2000), 7))
        assertEquals(listOf(Piece(prev1, 0, UnitRange(2000, 4000), 7)), pieces)
    }

    @Test
    fun backAcrossTwoInputs() {
        val pieces = UnitFlow.back(tx, inputs, Piece(tx.txid, 0, UnitRange(9000, 11000), 100))
        assertEquals(listOf(Piece(prev0, 0, UnitRange(9000, 10000), 100), Piece(prev1, 0, UnitRange(0, 1000), 1100)), pieces)
    }

    @Test
    fun backFee() {
        val pieces = UnitFlow.back(tx, inputs, Piece(tx.txid, Piece.FEE, UnitRange(0, 1000), 0))
        assertEquals(listOf(Piece(prev1, 0, UnitRange(4000, 5000), 0)), pieces)
    }

    @Test
    fun forwardToOutputAndFee() {
        assertEquals(listOf(Piece(tx.txid, 0, UnitRange(9000, 10000), 3)), UnitFlow.forward(tx, inputs, 0, UnitRange(9000, 10000), 3))
        assertEquals(listOf(Piece(tx.txid, Piece.FEE, UnitRange(500, 1000), 0)), UnitFlow.forward(tx, inputs, 1, UnitRange(4500, 5000), 0))
    }

    @Test
    fun rangePastOutputRefused() {
        assertFailsWith<IllegalArgumentException> { UnitFlow.back(tx, inputs, Piece(tx.txid, 1, UnitRange(1000, 3000), 0)) }
        assertEquals(1000, UnitFlow.fee(tx, inputs))
    }

    /** ord-litecoin index.rs list_fee_paying_transaction_range: 50 LTC split in two with a fee of 10. */
    @Test
    fun ordFeePayingTransactionRange() {
        val split = TrackFixtures.spendZero(listOf(prev0), 2_499_999_995L, 2_499_999_995L)
        val input = listOf(5_000_000_000L)
        assertEquals(
            listOf(Piece(prev0, 0, UnitRange(2_499_999_995L, 4_999_999_990L), 0)),
            UnitFlow.back(split, input, Piece(split.txid, 1, UnitRange(0, 2_499_999_995L), 0))
        )
        assertEquals(
            listOf(Piece(prev0, 0, UnitRange(4_999_999_990L, 5_000_000_000L), 0)),
            UnitFlow.back(split, input, Piece(split.txid, Piece.FEE, UnitRange(0, 10), 0))
        )
    }

    @Test
    fun unitRangeBasics() {
        val a = UnitRange(0, 10)
        assertEquals(10, a.length)
        assertTrue(a.overlaps(UnitRange(9, 20)))
        assertFalse(a.overlaps(UnitRange(10, 20)))
        assertEquals(UnitRange(5, 10), a.intersect(UnitRange(5, 20)))
        assertNull(a.intersect(UnitRange(10, 20)))
        assertEquals(UnitRange(5, 15), a.shift(5))
        assertFailsWith<IllegalArgumentException> { UnitRange(5, 5) }
        assertFailsWith<IllegalArgumentException> { UnitRange(-1, 5) }
    }

    @Test
    fun fixtureBuildsWhatItSays() {
        assertEquals(64, tx.txid.length)
        assertEquals(listOf(prev0, prev1), tx.inputs.map { it.prevTxid })
        assertEquals(14_000L, UnitFlow.outputTotal(tx))
    }
}
