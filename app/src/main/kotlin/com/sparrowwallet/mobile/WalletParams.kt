package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
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
