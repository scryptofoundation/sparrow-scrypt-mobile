package com.sparrowwallet.mobile.crypto

import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The production secp256k1 code (libsecp256k1 for keys, BouncyCastle with drongo's RFC6979 nonce for
 * ECDSA) must give byte-identical results to the reference implementation it replaced.
 */
class SecpBackendTest {
    private val random = SecureRandom()
    private val n = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

    private fun scalar(): ByteArray {
        while(true) {
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            val value = BigInteger(1, bytes)
            if(value.signum() > 0 && value < n) {
                return bytes
            }
        }
    }

    @Test
    fun productionBackendIsConstantTime() {
        assertEquals("libsecp256k1 + BouncyCastle", SECP_BACKEND)
    }

    @Test
    fun keysMatchTheReference() {
        repeat(200) {
            val priv = scalar()
            val tweak = scalar()
            val pub = refPublicKeyCreate(priv)
            assertContentEquals(pub, secpPublicKeyCreate(priv))
            assertContentEquals(refPrivKeyTweakAdd(priv, tweak), secpPrivKeyTweakAdd(priv, tweak))
            assertContentEquals(refPubKeyTweakAdd(pub, tweak), secpPubKeyTweakAdd(pub, tweak))
            assertContentEquals(refPubKeyTweakMul(pub, tweak), secpPubKeyTweakMul(pub, tweak))
        }
    }

    /** HDKey passes its own key array to the tweak: libsecp256k1 must not write into the caller's arrays. */
    @Test
    fun inputsAreLeftUnchanged() {
        repeat(20) {
            val priv = scalar()
            val tweak = scalar()
            val pub = secpPublicKeyCreate(priv)
            val hash = ByteArray(32).also { random.nextBytes(it) }
            val copies = listOf(priv.copyOf(), tweak.copyOf(), pub.copyOf(), hash.copyOf())
            secpPublicKeyCreate(priv)
            secpPrivKeyTweakAdd(priv, tweak)
            secpPubKeyTweakAdd(pub, tweak)
            secpPubKeyTweakMul(pub, tweak)
            secpSign(hash, priv)
            listOf(priv, tweak, pub, hash).zip(copies).forEach { (after, before) -> assertContentEquals(before, after) }
        }
    }

    @Test
    fun signaturesMatchTheReferenceIncludingGrinding() {
        var ground = 0
        repeat(300) {
            val priv = scalar()
            val hash = ByteArray(32).also { random.nextBytes(it) }
            val (firstR, _) = refSignAttempt(BigInteger(1, priv), BigInteger(1, hash), priv, null)
            if(firstR.bitLength() > 255) {
                ground++
            }
            assertContentEquals(refSign(hash, priv), secpSign(hash, priv))
        }
        //About half of all first attempts have a high R, so the counter path is exercised many times
        assertTrue(ground > 50, "only $ground signatures needed grinding")
    }
}
