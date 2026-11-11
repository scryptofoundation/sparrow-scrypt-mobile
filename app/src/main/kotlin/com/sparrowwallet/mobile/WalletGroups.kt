package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network

/**
 * The wallet list's sections: one per coin with wallets, in the coin order. A companion (the
 * Private MWEB wallet or the Taproot wallet) stays hidden under its parent, which shows as one
 * card with its layers; a companion whose parent is not on this phone is listed on its own.
 */
fun groupByCoin(wallets: List<WalletRepository.WalletInfo>): List<Pair<Coin, List<WalletRepository.WalletInfo>>> {
    val names = wallets.map { it.name }.toSet()
    val visible = wallets.filter { w -> w.links.parent?.let { it in names } != true }
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

/**
 * A wallet card's balance: its own synced total plus its companions' (the MWEB wallet's and the
 * Taproot wallet's), from [totals] by name. A companion counts only if a wallet of its kind has that name.
 */
fun walletBalance(info: WalletRepository.WalletInfo, wallets: List<WalletRepository.WalletInfo>,
                  totals: Map<String, Long>): Long? {
    val own = totals[info.name]
    val linked = listOfNotNull(info.links.mweb?.let { it to "MWEB" }, info.links.taproot?.let { it to "P2TR" })
        .mapNotNull { (ln, type) -> wallets.firstOrNull { it.name == ln && it.scriptType == type }?.let { totals[it.name] } }
    return if(own == null && linked.isEmpty()) null else (own ?: 0) + linked.sum()
}

enum class CompanionKind { MWEB, TAPROOT }

/**
 * The meta writes that link [companion] under [parent], parent first, each keeping the wallet's
 * other links. If only the first write lands, the parent names a companion that does not name it
 * back: the companion is still listed on its own, so nothing is hidden, and linking again finishes it.
 */
fun companionLinkWrites(parent: WalletRepository.WalletInfo, companion: WalletRepository.WalletInfo,
                        kind: CompanionKind): List<WalletRepository.WalletInfo> = listOf(
    parent.copy(links = when(kind) {
        CompanionKind.MWEB -> parent.links.copy(mweb = companion.name)
        CompanionKind.TAPROOT -> parent.links.copy(taproot = companion.name)
    }),
    companion.copy(links = companion.links.copy(parent = parent.name))
)
