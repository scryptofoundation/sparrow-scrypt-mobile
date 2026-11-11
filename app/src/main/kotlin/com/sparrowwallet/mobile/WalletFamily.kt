package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.storage.VaultData

/** A position of the Home layer switch, in the switch's order, with its label. */
enum class Layer(val label: String) {
    PUBLIC("Public"),
    PRIVATE("Private"),
    TAPROOT("Taproot")
}

/** One unlocked wallet of a family: its name on this phone and its vault. */
data class Member(val name: String, val data: VaultData)

/**
 * The wallets one password opens together on Home: the [public] wallet and, when linked both ways and unlocked with the
 * same password, its Private MWEB wallet and its same-seed Taproot wallet. A wallet on its own (an MWEB wallet without
 * its public wallet among them) is a family of one in the [public] slot.
 */
data class WalletFamily(val public: Member, val private: Member? = null, val taproot: Member? = null) {
    /** PUBLIC, then PRIVATE and TAPROOT when present. */
    val layers: List<Layer> get() = Layer.entries.filter { member(it) != null }

    fun member(layer: Layer): Member? = when(layer) {
        Layer.PUBLIC -> public
        Layer.PRIVATE -> private
        Layer.TAPROOT -> taproot
    }

    /**
     * The MWEB counterpart of the member shown on [layer], what "the linked wallet" meant before Taproot wallets: the
     * Private wallet for the Public layer, the Public wallet for the Private layer, none for the Taproot layer.
     */
    fun mwebCounterpart(layer: Layer): Member? = when(layer) {
        Layer.PUBLIC -> private
        Layer.PRIVATE -> public
        Layer.TAPROOT -> null
    }

    /** The family with each member named in [updated] holding its new vault (labels saved, labels synced). */
    fun withData(updated: Map<String, VaultData>): WalletFamily {
        fun Member.updated() = updated[name]?.let { copy(data = it) } ?: this
        return WalletFamily(public.updated(), private?.updated(), taproot?.updated())
    }

    /** A family and the layer of the wallet that opened it. */
    data class Opened(val family: WalletFamily, val layer: Layer)

    companion object {
        private const val MWEB = "MWEB"
        private const val P2TR = "P2TR"

        /**
         * The family [tapped] (already unlocked as [tappedData]) belongs to, among [wallets] (the wallets on this phone),
         * shown on the tapped wallet's layer. A companion's parent leads to the public wallet. Each other member is
         * unlocked through [unlock] with the same password and is left out when it will not unlock; only a companion
         * linked both ways ([linkedBack]) is ever unlocked, and its vault must be of its layer's kind (MWEB for Private,
         * P2TR for Taproot). A companion whose parent will not unlock opens on its own.
         */
        fun open(tapped: String, tappedData: VaultData, wallets: List<WalletRepository.WalletInfo>,
                 unlock: (String) -> VaultData?): Opened {
            val alone = Opened(WalletFamily(Member(tapped, tappedData)), Layer.PUBLIC)
            val byName = wallets.associateBy { it.name }
            val self = byName[walletFileName(tapped)] ?: return alone
            fun tryUnlock(name: String): VaultData? = runCatching { unlock(name) }.getOrNull()

            val parent = self.links.parent?.let { byName[it] }?.takeIf { linkedBack(it, self) }
            // a tapped companion's layer, judged before its parent is unlocked: a vault of the wrong kind opens alone
            val tappedLayer = when {
                parent == null -> Layer.PUBLIC
                parent.links.mweb == self.name -> Layer.PRIVATE
                else -> Layer.TAPROOT
            }
            if(tappedLayer != Layer.PUBLIC && tappedData.scriptType != kindOf(tappedLayer)) return alone
            val root = parent ?: self
            val rootMember = if(parent == null) Member(tapped, tappedData)
                             else Member(parent.name, tryUnlock(parent.name) ?: return alone)

            fun companion(layer: Layer, name: String?): Member? {
                if(layer == tappedLayer) return Member(tapped, tappedData)
                val info = name?.let { byName[it] }?.takeIf { it.name != self.name && linkedBack(root, it) } ?: return null
                val data = tryUnlock(info.name) ?: return null
                return if(data.scriptType == kindOf(layer)) Member(info.name, data) else null
            }
            val family = WalletFamily(rootMember, companion(Layer.PRIVATE, root.links.mweb), companion(Layer.TAPROOT, root.links.taproot))
            return Opened(family, tappedLayer)
        }

        private fun kindOf(layer: Layer): String? = when(layer) {
            Layer.PUBLIC -> null
            Layer.PRIVATE -> MWEB
            Layer.TAPROOT -> P2TR
        }
    }
}

/** The layer switch's segments: one per layer in the order Public, Private, Taproot; none when there is only one layer. */
fun switchLayers(layers: List<Layer>): List<Layer> =
    Layer.entries.filter { it in layers }.takeIf { it.size > 1 }.orEmpty()
