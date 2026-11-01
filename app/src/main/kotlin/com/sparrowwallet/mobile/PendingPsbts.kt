package com.sparrowwallet.mobile

/**
 * The unsigned PSBT each wallet last sent out for external signing, so leaving the External
 * signing screen (to sign on this phone's Sign screen, say) does not lose it. Memory only:
 * it holds no keys, and a PSBT from before a restart is stale anyway.
 */
class PendingPsbts {
    private val byWallet = HashMap<String, WalletRepository.UnsignedSendPreview>()

    fun keep(wallet: String, unsigned: WalletRepository.UnsignedSendPreview) {
        byWallet[wallet] = unsigned
    }

    operator fun get(wallet: String): WalletRepository.UnsignedSendPreview? = byWallet[wallet]

    fun drop(wallet: String) {
        byWallet.remove(wallet)
    }
}
