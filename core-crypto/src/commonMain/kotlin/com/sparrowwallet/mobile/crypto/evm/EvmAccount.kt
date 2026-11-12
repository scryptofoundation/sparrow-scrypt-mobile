package com.sparrowwallet.mobile.crypto.evm

import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.secpPublicKeyUncompressed

/**
 * The EVM account of a wallet seed at the standard Ethereum path m/44'/60'/0'/0/index, so the same seed shows the same
 * address here, on the desktop (drongo EvmAccount and its same-seed LitVmLink) and in any other EVM wallet.
 *
 * Read only: only the 20-byte address comes out. The keys derived on the way stay inside this call and are wiped
 * before it returns; nothing here returns, logs or keeps a private key or the seed. The caller passes the master key
 * of the wallet's own seed bytes (an Electrum seed's Electrum seed bytes; a BIP39 passphrase is part of the seed).
 */
object EvmAccount {
    const val DERIVATION_PREFIX = "m/44'/60'/0'/0/"

    private val ACCOUNT_PATH = longArrayOf(44 + HDKey.HARDENED_BIT, 60 + HDKey.HARDENED_BIT, HDKey.HARDENED_BIT, 0L)

    fun address(master: HDKey, index: Int = 0): EvmAddress {
        require(index >= 0) { "Account index must be non-negative" }
        var key = master
        for(child in ACCOUNT_PATH + index.toLong()) {
            val next = key.deriveChild(child)
            if(key !== master) {
                key.privKey.fill(0)
            }
            key = next
        }
        try {
            return EvmAddress.ofPublicKey(secpPublicKeyUncompressed(key.privKey))
        } finally {
            key.privKey.fill(0)
        }
    }
}
