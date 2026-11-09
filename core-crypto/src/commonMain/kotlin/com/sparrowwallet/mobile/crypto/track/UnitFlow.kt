package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx

/**
 * Ordinal theory's first in, first out flow through one transaction: the inputs' units are laid end to end in input
 * order, the outputs take them in output order, and what is left is the fee.
 */
object UnitFlow {
    /** The pieces on the outpoints tx spends that a piece on one of its outputs (or its fee) came from, in input order. */
    fun back(tx: RawTx.Parsed, inputValues: List<Long>, piece: Piece): List<Piece> {
        checkValues(tx, inputValues)
        val flow = toFlow(tx, inputValues, piece.vout, piece.range)
        val pieces = ArrayList<Piece>()
        var inputStart = 0L
        for(i in tx.inputs.indices) {
            val value = inputValues[i]
            if(value > 0) {
                val input = UnitRange(inputStart, inputStart + value)
                val shared = input.intersect(flow)
                if(shared != null) {
                    val txInput = tx.inputs[i]
                    pieces.add(
                        Piece(txInput.prevTxid, txInput.prevVout.toInt(), shared.shift(-inputStart), piece.coinOffset + (shared.start - flow.start))
                    )
                }
            }
            inputStart += value
        }
        return pieces
    }

    /** Where a range on input inputIndex goes: pieces on tx outputs, or on its fee (vout FEE). */
    fun forward(tx: RawTx.Parsed, inputValues: List<Long>, inputIndex: Int, range: UnitRange, coinOffset: Long): List<Piece> {
        checkValues(tx, inputValues)
        require(range.end <= inputValues[inputIndex]) { "Range $range is past input $inputIndex value" }
        var inputStart = 0L
        for(i in 0 until inputIndex) {
            inputStart += inputValues[i]
        }
        return place(tx, range.shift(inputStart), coinOffset)
    }

    /** Places a range of the transaction's flow on its outputs and fee. */
    internal fun place(tx: RawTx.Parsed, flow: UnitRange, coinOffset: Long): List<Piece> {
        val pieces = ArrayList<Piece>()
        var outputStart = 0L
        for((i, output) in tx.outputs.withIndex()) {
            val value = output.value
            if(value > 0) {
                val shared = UnitRange(outputStart, outputStart + value).intersect(flow)
                if(shared != null) {
                    pieces.add(Piece(tx.txid, i, shared.shift(-outputStart), coinOffset + (shared.start - flow.start)))
                }
            }
            outputStart += value
        }
        if(flow.end > outputStart) {
            val fee = UnitRange(maxOf(flow.start, outputStart), flow.end)
            pieces.add(Piece(tx.txid, Piece.FEE, fee.shift(-outputStart), coinOffset + (fee.start - flow.start)))
        }
        return pieces
    }

    fun fee(tx: RawTx.Parsed, inputValues: List<Long>): Long {
        checkValues(tx, inputValues)
        return inputValues.sum() - outputTotal(tx)
    }

    fun outputTotal(tx: RawTx.Parsed): Long = tx.outputs.sumOf { it.value }

    /** A range on an output (or the fee) as a range of the transaction's flow. */
    internal fun toFlow(tx: RawTx.Parsed, inputValues: List<Long>, vout: Int, range: UnitRange): UnitRange {
        val outputs = outputTotal(tx)
        if(vout == Piece.FEE) {
            val fee = inputValues.sum() - outputs
            require(range.end <= fee) { "Range $range is past the fee of $fee" }
            return range.shift(outputs)
        }
        require(vout >= 0 && vout < tx.outputs.size) { "No output $vout" }
        var start = 0L
        for(i in 0 until vout) {
            start += tx.outputs[i].value
        }
        require(range.end <= tx.outputs[vout].value) { "Range $range is past output $vout value" }
        return range.shift(start)
    }

    private fun checkValues(tx: RawTx.Parsed, inputValues: List<Long>) {
        require(inputValues.size == tx.inputs.size) { "Need one value per input" }
        require(inputValues.sum() >= outputTotal(tx)) { "The inputs hold less than the outputs" }
    }
}
