package com.sparrowwallet.mobile.crypto

import kotlin.math.ceil

/**
 * A utxo the wallet can spend, paired with the key that owns it and the wallet's script type.
 * [prevTx] is the serialized transaction that created it, for a PSBT's signer (Legacy needs it).
 */
class SpendableInput(
    val txid: String, // display (big-endian) hex
    val vout: Long,
    val value: Long,  // smallest units (litoshis, sats)
    val key: HDKey,
    val scriptType: ScriptType,
    val prevTx: ByteArray? = null
)

class BuiltTx(val tx: Tx, val amount: Long, val fee: Long, val change: Long) {
    val vsize: Int get() {
        val base = tx.serialize(false).size
        val total = tx.serialize(true).size
        return (base * 3 + total + 3) / 4
    }
    fun hex(): String = tx.serialize().toHex()
}

/** The same selection as [BuiltTx] but left unsigned, for the PSBT / external-signer flow. */
class UnsignedBuild(
    val tx: Tx,
    val inputs: List<SpendableInput>,
    val amount: Long,
    val fee: Long,
    val change: Long,
    /** The coin it spends, marked in the PSBT made from it. */
    val coin: Coin
) {
    /**
     * What the fee comes to per estimated vbyte once signed. Above the rate asked for where the coin charges
     * whole kilobytes (Dingocoin), so it agrees with what a signer's review shows.
     */
    val paidFeeRate: Long
        get() = fee / TxBuilder.estimateVsize(inputs.map { it.scriptType }, tx.outputs.map { it.script.size })
}

/**
 * Builds and signs a single-key spend on any coin: largest-first coin selection, fee from a
 * rate against the estimated vsize (charged on the coin's fee size, whole kilobytes on
 * Dingocoin), change back to the wallet (dropped into the fee when below the coin's dust
 * limit). Signing goes through [InputSigner], verified byte-for-byte against desktop drongo
 * in SignedTxCoinParityTest and TxParityTest.
 */
object TxBuilder {
    /** Litecoin's dust limit, for the call sites that predate the coin axis. */
    const val DUST_LIMIT = 546L

    /**
     * Selects, assembles and signs. [chainTip] is the tip of the chain the transaction is meant for, as the wallet's
     * own server gave it (null: not known); with [params] it says which signature hash the chain takes
     * ([ChainSig.of]). The caller always says it.
     */
    fun build(
        candidates: List<SpendableInput>,
        destScript: ByteArray,
        amount: Long,
        feeRatePerVb: Long,
        changeScript: ByteArray,
        sendMax: Boolean = false,
        params: ChainParams,
        chainTip: Long?
    ): BuiltTx {
        val unsigned = buildUnsigned(candidates, destScript, amount, feeRatePerVb, changeScript, sendMax, params)
        return sign(unsigned, ChainSig.of(params, chainTip))
    }

    /** Selection and transaction assembly without signing: the PSBT source. */
    fun buildUnsigned(
        candidates: List<SpendableInput>,
        destScript: ByteArray,
        amount: Long,
        feeRatePerVb: Long,
        changeScript: ByteArray,
        sendMax: Boolean = false,
        params: ChainParams
    ): UnsignedBuild {
        val coin = params.coin
        val floor = ceil(coin.recommendedFeeRate).toLong()
        require(feeRatePerVb >= floor) {
            if(coin == Coin.LITECOIN) "Fee rate must be at least $floor lit/vB" else "Fee rate must be at least $floor sat/vB for ${coin.displayName}"
        }
        val unit = if(coin == Coin.LITECOIN) "litoshi" else coin.subunit
        val units = if(coin == Coin.LITECOIN) "litoshis" else coin.subunit
        val dust = params.dustLimit
        val sorted = candidates.filter { it.value > 0 }.sortedByDescending { it.value }
        require(sorted.isNotEmpty()) { "No spendable coins" }
        require(sorted.all { it.scriptType == sorted[0].scriptType }) { "All inputs must share the wallet's script type" }
        val inputType = sorted[0].scriptType
        require(coin.supports(inputType)) { "${coin.displayName} has no $inputType inputs" }
        fun fee(inputs: Int, outputScriptSizes: List<Int>) =
            coin.feeSize(estimateVsize(List(inputs) { inputType }, outputScriptSizes)) * feeRatePerVb

        if(sendMax) {
            val total = sorted.sumOf { it.value }
            val fee = fee(sorted.size, listOf(destScript.size))
            val sendValue = total - fee
            require(sendValue >= dust) { "Balance is too small to cover the network fee" }
            return assemble(sorted, listOf(TxOutput(sendValue, destScript)), sendValue, fee, 0, coin)
        }

        require(amount >= dust) { "Amount is below the $dust $unit dust limit" }
        val selected = ArrayList<SpendableInput>()
        var total = 0L
        var fee = 0L
        for(input in sorted) {
            selected.add(input)
            total += input.value
            fee = fee(selected.size, listOf(destScript.size, changeScript.size))
            if(total >= amount + fee) {
                break
            }
        }
        require(total >= amount + fee) { "Insufficient funds: need ${amount + fee} $units, have $total spendable" }

        var change = total - amount - fee
        val outputs = ArrayList<TxOutput>()
        outputs.add(TxOutput(amount, destScript))
        if(change >= dust) {
            outputs.add(TxOutput(change, changeScript))
        } else {
            fee += change
            change = 0
        }
        return assemble(selected, outputs, amount, fee, change, coin)
    }

    private fun assemble(
        inputs: List<SpendableInput>,
        outputs: List<TxOutput>,
        amount: Long,
        fee: Long,
        change: Long,
        coin: Coin
    ): UnsignedBuild {
        val tx = Tx(2, inputs.map { TxInput(it.txid, it.vout) }, outputs)
        return UnsignedBuild(tx, inputs, amount, fee, change, coin)
    }

    /** Estimated vsize for a tx spending only P2WPKH inputs (72-byte signatures assumed). */
    fun estimateVsize(inputCount: Int, outputScriptSizes: List<Int>): Long =
        estimateVsize(List(inputCount) { ScriptType.P2WPKH }, outputScriptSizes)

    /**
     * Estimated vsize with 72-byte signatures: a P2PKH input is 148 bytes, a P2SH-P2WPKH input
     * 64 bytes plus a 108-byte witness, a P2WPKH input 41 plus 108. The segwit marker and flag
     * count only when some input has a witness.
     */
    fun estimateVsize(inputTypes: List<ScriptType>, outputScriptSizes: List<Int>): Long {
        val inputBase = inputTypes.sumOf {
            when(it) {
                ScriptType.P2PKH -> 148
                ScriptType.P2SH_P2WPKH -> 64
                ScriptType.P2WPKH -> 41
            }.toInt()
        }
        val segwitInputs = inputTypes.count { it != ScriptType.P2PKH }
        val base = 4 + varIntSize(inputTypes.size) + inputBase +
            varIntSize(outputScriptSizes.size) + outputScriptSizes.sumOf { 8 + varIntSize(it) + it } + 4
        val witness = if(segwitInputs > 0) 2 + segwitInputs * 108 else 0 // per input: count(1) + sig(1+72) + pubkey(1+33)
        return ((base * 4 + witness + 3) / 4).toLong()
    }

    private fun varIntSize(n: Int): Int = when {
        n < 0xFD -> 1
        n <= 0xFFFF -> 3
        else -> 5
    }

    private fun sign(unsigned: UnsignedBuild, chainSig: ChainSig): BuiltTx {
        val inputs = unsigned.inputs
        InputSigner.signAll(unsigned.tx, inputs.map { it.key }, inputs[0].scriptType, inputs.map { it.value }, chainSig)
        return BuiltTx(unsigned.tx, unsigned.amount, unsigned.fee, unsigned.change)
    }
}
