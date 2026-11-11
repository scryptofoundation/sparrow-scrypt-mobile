package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network

/**
 * The wallet list's sections: one per coin with wallets, in the coin order. A companion (the
 * Private MWEB wallet or the Taproot wallet) stays hidden under its parent, which shows as one
 * card with its layers, only while the link runs both ways ([linkedBack]); any other wallet is listed on its own.
 */
fun groupByCoin(wallets: List<WalletRepository.WalletInfo>): List<Pair<Coin, List<WalletRepository.WalletInfo>>> {
    val byName = wallets.associateBy { it.name }
    val visible = wallets.filter { w -> w.links.parent?.let { byName[it] }?.let { linkedBack(it, w) } != true }
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
 * Taproot wallet's), from [totals] by name. A companion counts only if a wallet of its kind has that name and the
 * link runs both ways ([linkedBack]), so a companion is never added to a card and also listed as its own row.
 */
fun walletBalance(info: WalletRepository.WalletInfo, wallets: List<WalletRepository.WalletInfo>,
                  totals: Map<String, Long>): Long? {
    val own = totals[info.name]
    val linked = listOfNotNull(info.links.mweb?.let { it to "MWEB" }, info.links.taproot?.let { it to "P2TR" })
        .mapNotNull { (ln, type) ->
            wallets.firstOrNull { it.name == ln && it.scriptType == type }?.takeIf { linkedBack(info, it) }?.let { totals[it.name] }
        }
    return if(own == null && linked.isEmpty()) null else (own ?: 0) + linked.sum()
}

/**
 * Whether [companion] is [parent]'s companion with the link running both ways: two different wallets, the parent not
 * itself a companion, the parent naming it among its companions in the slot of its kind (MWEB in `mweb`, P2TR in
 * `taproot`) and it naming the parent. A one-sided link or one of the wrong kind (a stop
 * between the two link writes, an older build that dropped a field, a parent relinked elsewhere) counts as none.
 * The one test of a link: the list hides, balances add, deletes take and screens open only companions that pass it.
 */
fun linkedBack(parent: WalletRepository.WalletInfo, companion: WalletRepository.WalletInfo): Boolean =
    parent.name != companion.name && parent.links.parent == null &&
        companion.links.parent == parent.name &&
        ((parent.links.mweb == companion.name && companion.scriptType == "MWEB") ||
            (parent.links.taproot == companion.name && companion.scriptType == "P2TR"))

/** The wallet name of a file in the wallets directory: only a `.vault` file is a wallet. */
fun walletVaultName(fileName: String): String? = if(fileName.endsWith(".vault")) fileName.removeSuffix(".vault") else null

enum class CompanionKind { MWEB, TAPROOT }

/**
 * The meta writes that link [companion] under [parent], parent first, each keeping the wallet's
 * other links. If only the first write lands, the parent names a companion that does not name it
 * back: the companion is still listed on its own, so nothing is hidden, and linking again finishes it.
 * A wallet is never linked to itself: that gives no writes.
 */
fun companionLinkWrites(parent: WalletRepository.WalletInfo, companion: WalletRepository.WalletInfo,
                        kind: CompanionKind): List<WalletRepository.WalletInfo> = if(parent.name == companion.name) emptyList() else listOf(
    parent.copy(links = when(kind) {
        CompanionKind.MWEB -> parent.links.copy(mweb = companion.name)
        CompanionKind.TAPROOT -> parent.links.copy(taproot = companion.name)
    }),
    companion.copy(links = companion.links.copy(parent = parent.name))
)
