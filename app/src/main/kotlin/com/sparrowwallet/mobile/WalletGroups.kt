package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin

/**
 * The wallet list's sections: one per coin with wallets, in the coin order. A linked MWEB
 * wallet stays hidden under its public wallet, which shows as one card with both layers.
 */
fun groupByCoin(wallets: List<WalletRepository.WalletInfo>): List<Pair<Coin, List<WalletRepository.WalletInfo>>> {
    val names = wallets.map { it.name }.toSet()
    val visible = wallets.filter { w -> !(w.scriptType == "MWEB" && w.linkedWallet != null && w.linkedWallet in names) }
    return Coin.entries.mapNotNull { coin ->
        visible.filter { it.coin == coin }.takeIf { it.isNotEmpty() }?.let { coin to it }
    }
}

/**
 * A section's total from the balances known this session. Balances are never written to disk,
 * so a wallet not unlocked since the app started has none and adds nothing; null when none is known.
 */
fun coinTotal(wallets: List<WalletRepository.WalletInfo>, balanceOf: (String) -> Long?): Long? {
    val known = wallets.mapNotNull { balanceOf(it.name) }
    return if(known.isEmpty()) null else known.sum()
}
