package com.sparrowwallet.mobile.crypto.evm

import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.secpPublicKeyUncompressed

/**
 * The EVM account of a wallet seed at the standard Ethereum path m/44'/60'/0'/0/index, so the same seed shows the same
 * address here, on the desktop (drongo EvmAccount and its same-seed LitVmLink) and in any other EVM wallet.
 *
 * Read only: only the 20-byte address comes out; nothing here returns, logs or keeps a private key or the seed. The
 * caller passes the MASTER key of the wallet's own seed bytes (an Electrum seed's Electrum seed bytes; a BIP39
 * passphrase is part of the seed): a key at any other depth would give a different, wrong address, so it is refused.
 *
 * Wiping is best effort. The private key arrays of the keys derived on the way (the master's is the caller's) are
 * zeroed before this returns, also on an exception. Not wiped, because this code does not hold them: the copies
 * HDKey.deriveChild makes for HMAC input and for the native tweak call, the copy handed to the native call that makes
 * the public key, and anything the JVM or the native library keeps.
 */
object EvmAccount {
    const val DERIVATION_PREFIX = "m/44'/60'/0'/0/"

    private val ACCOUNT_PATH = longArrayOf(44 + HDKey.HARDENED_BIT, 60 + HDKey.HARDENED_BIT, HDKey.HARDENED_BIT, 0L)

    fun address(master: HDKey, index: Int = 0): EvmAddress {
        require(master.depth == 0) { "The account address is derived from the master key" }
        require(index >= 0) { "Account index must be non-negative" }
        var key = master
        try {
            for(child in ACCOUNT_PATH + index.toLong()) {
                val next = key.deriveChild(child)
                if(key !== master) {
                    key.privKey.fill(0)
                }
                key = next
            }
            return EvmAddress.ofPublicKey(secpPublicKeyUncompressed(key.privKey))
        } finally {
            if(key !== master) {
                key.privKey.fill(0)
            }
        }
    }
}
