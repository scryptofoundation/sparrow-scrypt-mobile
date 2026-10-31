package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Addresses
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network

/** The amount with all 8 decimals, exact: high-supply coins pass what a double can hold. */
internal fun formatUnits(units: Long): String = java.math.BigDecimal.valueOf(units, 8).toPlainString()

/** Amounts in the coin's unit: every supported coin has 8 decimals. */
internal fun formatAmount(coin: Coin, units: Long): String = "${formatUnits(units)} ${coin.ticker}"

/** The amount without trailing-zero noise (0.50000000 → 0.5), sign dropped. */
internal fun formatUnitsShort(units: Long): String {
    val s = formatUnits(units).removePrefix("-").trimEnd('0')
    return if(s.endsWith('.')) s + "0" else s
}

internal fun formatAmountShort(coin: Coin, units: Long): String = "${formatUnitsShort(units)} ${coin.ticker}"

/** A USD price: cents from a dollar up, else three significant digits so sub-cent coins don't read $0.00. */
internal fun formatUsdPrice(price: Double): String {
    if(price >= 1) return "$%,.2f".format(price)
    val rounded = java.math.BigDecimal(price).round(java.math.MathContext(3)).stripTrailingZeros()
    return "$" + rounded.setScale(maxOf(rounded.scale(), 2)).toPlainString()
}

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
