package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.storage.VaultData

/**
 * What adding and deleting companions needs of the wallets on this phone. [WalletRepository] gives its own files;
 * tests give a temporary directory laid out the same way.
 */
interface CompanionFiles {
    /** The wallets on this phone, as the wallet list reads them. */
    fun wallets(): List<WalletRepository.WalletInfo>

    /** Opens a wallet's vault to read it, never as a session unlock; throws on a wrong password. */
    fun open(name: String, password: String): VaultData

    /** Seals a new wallet from [data] under [password] and writes its meta; a refusal, or null when saved. */
    fun save(name: String, data: VaultData, password: String): String?

    /** Rewrites one wallet's meta as [info] gives it, its links among it. */
    fun rewriteMeta(info: WalletRepository.WalletInfo)

    /**
     * Removes one wallet and everything kept for it: its vault and meta, its inscription results and kept marks,
     * its caches, its session total and name, its pending PSBTs.
     */
    fun deleteOne(name: String)
}

/**
 * The same seed's Taproot wallet beside a Litecoin or Bellscoin wallet, as drongo's `Wallet.addTaprootChildWallet()`
 * makes it on the desktop: the parent's seed (BIP39 or Electrum) and passphrase, re-derived at BIP86 account 0 on the
 * parent's coin and network, whatever the parent's own path or account. It is its own vault, sealed with the parent's
 * password, as the MWEB Private companion is.
 */
object TaprootCompanion {
    const val SUFFIX = " Taproot"
    const val WRONG_PASSWORD = "Wrong password"
    const val NO_SEED = "This wallet has no seed on this phone."
    const val NOT_AVAILABLE = "Taproot wallets are not available for this coin."
    /** A Taproot, MWEB or companion wallet. */
    const val NOT_THIS_WALLET = "A Taproot wallet cannot be added to this wallet."
    /** A seed kind this phone does not derive (it stores only BIP39 and Electrum seeds). */
    const val SEED_KIND = "A Taproot wallet cannot be made from this kind of seed."
    /** Saving or linking failed; whatever this call wrote has been taken back. */
    const val SAVE_FAILED = "The Taproot wallet could not be saved."
    const val NO_WALLET = "This wallet is not on this phone."

    fun nameTaken(name: String): String = "A wallet named $name already exists."

    /** The seed kinds drongo derives a Taproot child from (Keystore.fromSeed takes any) that this phone stores. */
    private val SEED_TYPES = setOf("BIP39", "ELECTRUM")

    /**
     * Whether the wallet is offered "Add Taproot wallet": the wallet and its seed allow one and it has no Taproot link
     * running both ways among [wallets] (the wallets on this phone). A one-sided link counts as none, so the button
     * shows and [add] finishes the link.
     */
    fun offered(info: WalletRepository.WalletInfo, hasSeed: Boolean, seedType: String?,
                wallets: List<WalletRepository.WalletInfo>): Boolean =
        walletProblem(info) == null && mutualTaproot(info, wallets) == null && seedProblem(hasSeed, seedType) == null

    /** The wallet's Taproot companion when the link runs both ways, else null. */
    private fun mutualTaproot(info: WalletRepository.WalletInfo, wallets: List<WalletRepository.WalletInfo>): String? =
        info.links.taproot?.takeIf { t -> wallets.any { it.name == t && linkedBack(info, it) } }

    /** Whether [other] and [info] are joined by a link only one of them writes: [add] finishes it rather than refusing the name. */
    private fun halfLinked(info: WalletRepository.WalletInfo, other: WalletRepository.WalletInfo): Boolean =
        !linkedBack(info, other) && (info.links.taproot == other.name || other.links.parent == info.name)

    /** "<name> Taproot", the parent's part cut so the whole is a wallet file name of at most [WALLET_NAME_MAX] characters. */
    fun name(parentName: String): String =
        walletFileName(parentName.take(WALLET_NAME_MAX - SUFFIX.length).trimEnd() + SUFFIX)

    /**
     * Why the wallet cannot have its Taproot wallet added now, or null. [wallets] are the wallets on this phone; a
     * wallet holding the name that is half-linked with this one is not a refusal, as [add] finishes that link.
     */
    fun problem(info: WalletRepository.WalletInfo, wallets: List<WalletRepository.WalletInfo>, hasSeed: Boolean, seedType: String?): String? =
        walletProblem(info)
            ?: mutualTaproot(info, wallets)?.let { nameTaken(it) }
            ?: seedProblem(hasSeed, seedType)
            ?: name(info.name).takeIf { n -> wallets.any { it.name == n && !halfLinked(info, it) } }?.let { nameTaken(it) }

    private fun walletProblem(info: WalletRepository.WalletInfo): String? = when {
        (info.coin != Coin.LITECOIN && info.coin != Coin.BELLSCOIN) || !ChainParams.of(info.coin, info.network).taprootWallets -> NOT_AVAILABLE
        info.scriptType == ScriptType.P2TR.name || info.scriptType == "MWEB" || info.links.isCompanion -> NOT_THIS_WALLET
        else -> null
    }

    private fun seedProblem(hasSeed: Boolean, seedType: String?): String? = when {
        !hasSeed -> NO_SEED
        seedType !in SEED_TYPES -> SEED_KIND
        else -> null
    }

    /** The companion's vault: the parent's seed, kind and passphrase at BIP86 account 0, nothing of its history or labels. */
    fun vaultData(parent: VaultData): VaultData {
        val params = ChainParams.of(Coin.fromId(parent.coin), Network.fromId(parent.network))
        return VaultData(parent.mnemonic, parent.passphrase, parent.seedType, params.derivationPath(ScriptType.P2TR, 0),
            parent.network, ScriptType.P2TR.name, coin = parent.coin)
    }

    /**
     * Adds the Taproot wallet of [parentName], sealed under [password]; a refusal, or null when it is added and linked.
     * The refusals that need no seed come first; then the parent's vault is opened (a wrong password writes nothing);
     * the seed goes only into the new vault. A wallet already named "<name> Taproot" is linked when its own vault opens
     * with the same password and holds the same account (coin, network and xpub), and refused otherwise. If linking
     * fails, what this call wrote is taken back.
     */
    fun add(files: CompanionFiles, parentName: String, password: String): String? {
        val wallets = files.wallets()
        val byName = wallets.associateBy { it.name }
        val parent = byName[walletFileName(parentName)] ?: return NO_WALLET
        walletProblem(parent)?.let { return it }
        //Linked both ways already: refused. A one-sided link (a stop between the two writes) is finished below
        mutualTaproot(parent, wallets)?.let { return nameTaken(it) }
        val name = name(parent.name)
        val seed = try {
            files.open(parent.name, password)
        } catch(e: Exception) {
            return WRONG_PASSWORD
        }
        seedProblem(seed.mnemonic.isNotBlank(), seed.seedType)?.let { return it }
        val companion = vaultData(seed)
        byName[name]?.let { existing -> return adopt(files, parent, existing, companion, password, byName) }
        val saved = try {
            files.save(name, companion, password)
        } catch(e: Exception) {
            //the name was free, so whatever is under it now is this call's
            runCatching { files.deleteOne(name) }
            return SAVE_FAILED
        }
        if(saved != null) return saved
        val created = WalletRepository.WalletInfo(name, Network.fromId(companion.network), companion.scriptType,
            WalletLinks(), Coin.fromId(companion.coin))
        return link(files, parent, created, removeOnFailure = true)
    }

    /** Links [existing] as [parent]'s Taproot wallet when it is the same seed's, else refuses its name. */
    private fun adopt(files: CompanionFiles, parent: WalletRepository.WalletInfo, existing: WalletRepository.WalletInfo,
                      companion: VaultData, password: String, byName: Map<String, WalletRepository.WalletInfo>): String? {
        val taken = nameTaken(existing.name)
        if(existing.name == parent.name || existing.links.companions.isNotEmpty()) return taken
        //another wallet's companion, linked both ways
        val otherParent = existing.links.parent?.takeIf { it != parent.name }?.let { byName[it] }
        if(otherParent != null && linkedBack(otherParent, existing)) return taken
        //judged by its vault, not its meta (a stop between a vault and its meta reads as mainnet Litecoin)
        val data = try {
            files.open(existing.name, password)
        } catch(e: Exception) {
            return taken
        }
        if(data.scriptType != companion.scriptType || data.coin != companion.coin || data.network != companion.network ||
            walletAccountXpub(data) != walletAccountXpub(companion)) return taken
        val corrected = WalletRepository.WalletInfo(existing.name, Network.fromId(data.network), data.scriptType,
            existing.links.copy(parent = null), Coin.fromId(data.coin))
        return link(files, parent, corrected, removeOnFailure = false)
    }

    /**
     * Writes the link, parent first (see [companionLinkWrites]), and reads both back. On failure the companion's meta
     * is put back first (so it never names a parent that does not name it), then the parent's, then a companion this
     * call created is removed.
     */
    private fun link(files: CompanionFiles, parent: WalletRepository.WalletInfo, companion: WalletRepository.WalletInfo,
                     removeOnFailure: Boolean): String? {
        try {
            for(w in companionLinkWrites(parent, companion, CompanionKind.TAPROOT)) files.rewriteMeta(w)
            val now = files.wallets().associateBy { it.name }
            val p = now[parent.name]
            val c = now[companion.name]
            check(p != null && c != null && p.links.taproot == companion.name && linkedBack(p, c))
            return null
        } catch(e: Exception) {
            runCatching { files.rewriteMeta(companion) }
            runCatching { files.rewriteMeta(parent) }
            if(removeOnFailure) runCatching { files.deleteOne(companion.name) }
            return SAVE_FAILED
        }
    }
}

/**
 * Deleting a wallet deletes its companions (the Private MWEB wallet and the Taproot wallet), and deleting a companion
 * on its own takes it out of its parent's links.
 */
object CompanionDeletion {
    /** The companions deleted along with [name]: those it names that are on this phone with the link running both ways ([linkedBack]). */
    fun deletedWith(name: String, wallets: List<WalletRepository.WalletInfo>): List<String> {
        val byName = wallets.associateBy { it.name }
        val self = byName[name] ?: return emptyList()
        return self.links.companions.distinct().filter { c -> byName[c]?.let { linkedBack(self, it) } == true }
    }

    /** The delete confirmation's extra line, or null when nothing else is deleted. */
    fun confirmation(names: List<String>): String? =
        if(names.isEmpty()) null else "This also deletes its linked wallets: ${names.joinToString(", ")}."

    /**
     * Deletes [name] and the wallets [deletedWith] names, companions first and the wallet last. Each goes in three
     * steps: it stops naming a parent, no wallet names it any more, then its files go. Stopped at any point (a write
     * throws, or the app dies), nothing is hidden: a companion is either still linked both ways under its parent, or
     * listed on its own, or gone with no link left to it; the wallet itself is still listed until its own files go.
     * A failed write stops the delete and is thrown.
     */
    fun delete(files: CompanionFiles, name: String) {
        for(companion in deletedWith(name, files.wallets())) deleteOne(files, companion)
        deleteOne(files, name)
    }

    private fun deleteOne(files: CompanionFiles, name: String) {
        val wallets = files.wallets()
        wallets.firstOrNull { it.name == name }?.let { self ->
            if(self.links.parent != null) files.rewriteMeta(self.copy(links = self.links.copy(parent = null)))
        }
        for(w in wallets) {
            if(w.name != name && name in w.links.companions) files.rewriteMeta(w.copy(links = w.links.without(name)))
        }
        files.deleteOne(name)
    }

    private fun WalletLinks.without(name: String) =
        copy(mweb = mweb?.takeIf { it != name }, taproot = taproot?.takeIf { it != name })
}
