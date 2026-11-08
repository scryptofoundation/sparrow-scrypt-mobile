package com.sparrowwallet.mobile.crypto

/** BIP340/BIP341/BIP86 key helpers: key-path-only Taproot (no script tree). */
object Taproot {
    /** BIP340 tagged hash: sha256(sha256(tag) || sha256(tag) || data). */
    fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.encodeToByteArray())
        return sha256(tagHash + tagHash + data)
    }

    /** The 32-byte x coordinate of a compressed public key. */
    fun xOnly(pubKey33: ByteArray): ByteArray {
        require(pubKey33.size == 33) { "Expected a compressed public key" }
        return pubKey33.copyOfRange(1, 33)
    }

    /** BIP86 output key (x only) for an internal key: lift x to even y, add H_TapTweak(x) * G. */
    fun outputKey(internalPub33: ByteArray): ByteArray {
        val x = xOnly(internalPub33)
        val lifted = byteArrayOf(0x02) + x
        val tweaked = secpPubKeyTweakAdd(lifted, taggedHash("TapTweak", x))
        return xOnly(tweaked)
    }

    /** The private key whose public key has x equal to [outputKey] of this key's public key. */
    fun tweakPrivKey(priv32: ByteArray): ByteArray {
        val pub = secpPublicKeyCreate(priv32)
        val base = if(pub[0].toInt() == 0x03) secpPrivKeyNegate(priv32) else priv32.copyOf()
        return secpPrivKeyTweakAdd(base, taggedHash("TapTweak", xOnly(pub)))
    }
}
