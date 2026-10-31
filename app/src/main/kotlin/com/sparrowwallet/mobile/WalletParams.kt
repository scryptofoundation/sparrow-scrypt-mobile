package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.SpendableInput
import com.sparrowwallet.mobile.electrum.WalletScanner
import kotlin.math.ceil

/** Per-coin rules the repository and screens share; pure so they are tested on the JVM. */
fun walletParams(coin: Coin, network: Network): ChainParams = ChainParams.of(coin, network)

/** A public wallet's script type. MWEB wallets never reach here; their paths stay Litecoin-only. */
fun walletScriptType(scriptType: String): ScriptType =
    ScriptType.entries.firstOrNull { it.name == scriptType }
        ?: throw IllegalArgumentException("Not a public wallet script type: $scriptType")

/**
 * A server's fee estimate as whole sats (lits) per vbyte, never below the coin's relay floor.
 * The ceiling guards against absurd values while leaving room for coins whose floor is high
 * (Dogecoin estimates near 50,600 sat/vB, Dingocoin's floor is 100,000).
 */
fun clampFeeRate(coin: Coin, satPerVb: Double?): Long {
    val floor = ceil(coin.recommendedFeeRate).toLong()
    if(satPerVb == null || satPerVb.isNaN() || satPerVb <= 0) return if(coin == Coin.LITECOIN) 10L else floor
    return ceil(satPerVb).toLong().coerceIn(floor, maxOf(10_000L, floor * 100))
}

fun feeUnit(coin: Coin): String = if(coin == Coin.LITECOIN) "lit/vB" else "sat/vB"

/**
 * Whether the PSBT, external-signer and air-gapped paths support the wallet. They write witness
 * UTXOs and decode Litecoin addresses only, so other coins and script types wait for phase 5.
 */
fun externalSigningSupported(coin: Coin, scriptType: String): Boolean =
    coin == Coin.LITECOIN && scriptType == ScriptType.P2WPKH.name

/**
 * The spendable inputs of a scan (at least [minConfirmations] deep at [tip]) as the wallet's
 * script type, and the change script at the first unused change index.
 */
internal fun spendables(scan: WalletScanner.UtxoScan, tip: Long, account: HDKey, scriptType: ScriptType,
                        minConfirmations: Int): Pair<List<SpendableInput>, ByteArray> {
    val candidates = scan.utxos
        .filter { it.utxo.height in 1..(tip - (minConfirmations - 1)) }
        .map {
            SpendableInput(
                it.utxo.txHash, it.utxo.txPos.toLong(), it.utxo.value,
                account.deriveChild(it.chain.toLong()).deriveChild(it.index.toLong()), scriptType
            )
        }
    val changePub = account.deriveChild(1).deriveChild(scan.nextChangeIndex.toLong()).pubKey
    return candidates to Scripts.outputScript(changePub, scriptType)
}
