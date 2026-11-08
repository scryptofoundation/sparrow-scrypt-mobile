package com.sparrowwallet.mobile.crypto

/**
 * secp256k1 operations (expect/actual).
 *
 * The JVM and Android actuals (SecpNative.kt) use libsecp256k1 for keys and tweaks and
 * BouncyCastle with drongo's nonce for ECDSA, both made for secret data. A plain
 * BigInteger version lives only in the tests, as a reference.
 */

/** Compressed (33-byte) public key for a 32-byte private key. */
expect fun secpPublicKeyCreate(privKey: ByteArray): ByteArray

/** (privKey + tweak) mod n, as 32 bytes. Throws if the result is zero or inputs invalid. */
expect fun secpPrivKeyTweakAdd(privKey: ByteArray, tweak: ByteArray): ByteArray

/** pubKey + G*tweak, compressed. Used by BIP32 CKDpub and MWEB stealth derivation. */
expect fun secpPubKeyTweakAdd(pubKey: ByteArray, tweak: ByteArray): ByteArray

/** pubKey * scalar (EC point multiplication), compressed. */
expect fun secpPubKeyTweakMul(pubKey: ByteArray, scalar: ByteArray): ByteArray

/**
 * ECDSA signature over a 32-byte message hash: RFC6979 deterministic nonce with
 * low-R grinding (§3.6 counter as 32-byte LE additional data) and low-S
 * normalization, byte-identical to drongo/Bitcoin Core. Returns compact 64-byte
 * r||s.
 */
expect fun secpSign(messageHash: ByteArray, privKey: ByteArray): ByteArray

/**
 * BIP340 Schnorr signature (64 bytes) over a 32-byte hash, with 32 zero bytes of auxiliary randomness, as
 * drongo signs. The private key is used as given (the caller applies any BIP341 tweak and parity negation).
 */
expect fun secpSignSchnorr(hash32: ByteArray, privKey32: ByteArray): ByteArray

/** True if sig64 is a valid BIP340 signature of hash32 under the 32-byte x-only public key. */
expect fun secpVerifySchnorr(sig64: ByteArray, hash32: ByteArray, xOnlyPub32: ByteArray): Boolean

/** n - privKey, as 32 bytes. Throws if the key is invalid. */
expect fun secpPrivKeyNegate(privKey32: ByteArray): ByteArray
