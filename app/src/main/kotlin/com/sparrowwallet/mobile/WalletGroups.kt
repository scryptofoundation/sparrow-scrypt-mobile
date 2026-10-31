package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network

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
 * A section's total on one network from the balances known this session. Balances are never
 * written to disk, so a wallet not synced since the app started adds nothing; null when none is known.
 * Mainnet and testnet are kept apart: testnet coins have no price.
 */
fun coinTotal(wallets: List<WalletRepository.WalletInfo>, network: Network, balanceOf: (String) -> Long?): Long? {
    val known = wallets.filter { it.network == network }.mapNotNull { balanceOf(it.name) }
    return if(known.isEmpty()) null else known.sum()
}

/** A wallet card's balance: its own synced total plus its linked MWEB wallet's, from [totals] by name. */
fun walletBalance(info: WalletRepository.WalletInfo, wallets: List<WalletRepository.WalletInfo>,
                  totals: Map<String, Long>): Long? {
    val own = totals[info.name]
    val linked = info.linkedWallet?.takeIf { info.scriptType != "MWEB" }
        ?.let { ln -> wallets.firstOrNull { it.name == ln && it.scriptType == "MWEB" } }
        ?.let { totals[it.name] }
    return if(own == null && linked == null) null else (own ?: 0) + (linked ?: 0)
}
