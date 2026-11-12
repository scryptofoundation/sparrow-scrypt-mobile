package com.sparrowwallet.mobile.crypto.evm

/**
 * Keccak-256 as Ethereum and EVM chains such as LitVM use it: the original Keccak padding, not the NIST SHA3-256
 * standardised later (the two give different digests). Port of drongo's Keccak; the JVM and Android actual uses
 * BouncyCastle's KeccakDigest(256), as the desktop does.
 */
expect fun keccak256(data: ByteArray): ByteArray
