package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Addresses
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network

/** Amounts in the coin's unit: every supported coin has 8 decimals. */
internal fun formatAmount(coin: Coin, units: Long): String = "%.8f %s".format(units / 1e8, coin.ticker)

/** The amount without trailing-zero noise (0.50000000 → 0.5), sign dropped. */
internal fun formatUnitsShort(units: Long): String {
    val s = "%.8f".format(kotlin.math.abs(units) / 1e8).trimEnd('0')
    return if(s.endsWith('.')) s + "0" else s
}

internal fun formatAmountShort(coin: Coin, units: Long): String = "${formatUnitsShort(units)} ${coin.ticker}"

/** Parses a positive decimal amount into the smallest unit exactly, or null. */
internal fun parseAmount(text: String): Long? =
    try {
        val units = text.trim().toBigDecimal().movePointRight(8)
        if(units.stripTrailingZeros().scale() > 0) null else units.longValueExact().takeIf { it > 0 }
    } catch(e: Exception) {
        null
    }

/**
 * A warning when the address is equally valid on other coins (Dogecoin and Dingocoin share
 * every address byte), so the user checks that the recipient expects this coin.
 */
internal fun sendWarning(coin: Coin, network: Network, address: String): String? {
    val others = Addresses.coinsSharingEncoding(address, ChainParams.of(coin, network))
    if(others.isEmpty()) return null
    return "This address is also valid on ${others.joinToString(" and ") { it.displayName }}. " +
        "Check that the recipient expects ${coin.displayName}."
}
