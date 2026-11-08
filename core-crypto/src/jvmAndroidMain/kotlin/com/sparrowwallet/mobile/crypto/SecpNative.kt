package com.sparrowwallet.mobile.crypto

import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.DSAKCalculator
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.security.SecureRandom

/**
 * secp256k1 for the phone and the JVM. Key creation and tweaks run in libsecp256k1, which is constant-time.
 * ECDSA signing runs the same code as the desktop's drongo (ECKey.signEcdsa): BouncyCastle's ECDSASigner with an
 * RFC6979 nonce whose retries add a counter as additional data (section 3.6) until R is low, then low S. That keeps
 * every signature byte-identical to the desktop's.
 */
internal const val SECP_BACKEND = "libsecp256k1 + BouncyCastle"

private val CURVE_PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
private val CURVE = ECDomainParameters(CURVE_PARAMS.curve, CURVE_PARAMS.g, CURVE_PARAMS.n, CURVE_PARAMS.h)
private val HALF_N: BigInteger = CURVE.n.shiftRight(1)

private inline fun <T> secp(block: Secp256k1.() -> T): T {
    try {
        return Secp256k1.block()
    } catch(e: IllegalArgumentException) {
        throw e
    } catch(e: Exception) {
        //libsecp256k1 refuses an invalid key, tweak or point; callers expect IllegalArgumentException as before
        throw IllegalArgumentException(e.message ?: "Invalid secp256k1 input", e)
    }
}

//secp256k1-kmp's JNI writes some results into the arrays it is given (a tweak lands in the key array), so every
//call gets copies: HDKey hands over its own key, which must stay the parent's
actual fun secpPublicKeyCreate(privKey: ByteArray): ByteArray {
    require(privKey.size == 32) { "Private key must be 32 bytes" }
    return secp { pubKeyCompress(pubkeyCreate(privKey.copyOf())) }
}

actual fun secpPrivKeyTweakAdd(privKey: ByteArray, tweak: ByteArray): ByteArray {
    require(privKey.size == 32 && tweak.size == 32) { "Keys must be 32 bytes" }
    return secp { privKeyTweakAdd(privKey.copyOf(), tweak.copyOf()) }
}

actual fun secpPubKeyTweakAdd(pubKey: ByteArray, tweak: ByteArray): ByteArray {
    require(tweak.size == 32) { "Tweak must be 32 bytes" }
    return secp { pubKeyCompress(pubKeyTweakAdd(pubkeyParse(pubKey.copyOf()), tweak.copyOf())) }
}

actual fun secpPubKeyTweakMul(pubKey: ByteArray, scalar: ByteArray): ByteArray {
    require(scalar.size == 32) { "Scalar must be 32 bytes" }
    return secp { pubKeyCompress(pubKeyTweakMul(pubkeyParse(pubKey.copyOf()), scalar.copyOf())) }
}

actual fun secpSign(messageHash: ByteArray, privKey: ByteArray): ByteArray {
    require(messageHash.size == 32 && privKey.size == 32) { "Hash and key must be 32 bytes" }
    val d = BigInteger(1, privKey)
    require(d.signum() > 0 && d < CURVE.n) { "Private key out of range" }

    var counter: Long? = null
    while(true) {
        val signer = ECDSASigner(NonceKCalculator(counter))
        signer.init(true, ECPrivateKeyParameters(d, CURVE))
        val components = signer.generateSignature(messageHash)
        val r = components[0]
        var s = components[1]
        if(s > HALF_N) {
            s = CURVE.n - s //low S (BIP62)
        }
        //Low R: the DER-encoded R fits in 32 bytes, as drongo's hasLowR (DER under 71 bytes) requires
        if(r.bitLength() <= 255) {
            return BigIntegers.asUnsignedByteArray(32, r) + BigIntegers.asUnsignedByteArray(32, s)
        }
        counter = (counter ?: 0L) + 1
    }
}

/**
 * drongo's HMacDSANonceKCalculator: BouncyCastle's HMacDSAKCalculator (RFC6979 section 3.2) with the retry counter
 * as additional data (section 3.6), written as a 32-byte little-endian number.
 */
private class NonceKCalculator(private val counter: Long?) : DSAKCalculator {
    private val hMac = HMac(SHA256Digest())
    private val v = ByteArray(hMac.macSize)
    private val k = ByteArray(hMac.macSize)
    private lateinit var n: BigInteger

    override fun isDeterministic(): Boolean = true

    override fun init(n: BigInteger, random: SecureRandom?) {
        throw IllegalStateException("Operation not supported")
    }

    override fun init(n: BigInteger, d: BigInteger, message: ByteArray) {
        this.n = n
        v.fill(0x01)
        k.fill(0)

        val size = BigIntegers.getUnsignedByteLength(n)
        val x = ByteArray(size)
        val dVal = BigIntegers.asUnsignedByteArray(d)
        dVal.copyInto(x, x.size - dVal.size)

        var mInt = bitsToInt(message)
        if(mInt >= n) {
            mInt = mInt.subtract(n)
        }
        val m = ByteArray(size)
        val mVal = BigIntegers.asUnsignedByteArray(mInt)
        mVal.copyInto(m, m.size - mVal.size)

        val c = counter?.let { value -> ByteArray(size) { i -> if(i < 8) ((value ushr (8 * i)) and 0xFF).toByte() else 0 } }

        hMac.init(KeyParameter(k))
        hMac.update(v, 0, v.size)
        hMac.update(0x00)
        hMac.update(x, 0, x.size)
        hMac.update(m, 0, m.size)
        c?.let { hMac.update(it, 0, it.size) }
        hMac.doFinal(k, 0)

        hMac.init(KeyParameter(k))
        hMac.update(v, 0, v.size)
        hMac.doFinal(v, 0)

        hMac.update(v, 0, v.size)
        hMac.update(0x01)
        hMac.update(x, 0, x.size)
        hMac.update(m, 0, m.size)
        c?.let { hMac.update(it, 0, it.size) }
        hMac.doFinal(k, 0)

        hMac.init(KeyParameter(k))
        hMac.update(v, 0, v.size)
        hMac.doFinal(v, 0)
    }

    override fun nextK(): BigInteger {
        val t = ByteArray(BigIntegers.getUnsignedByteLength(n))
        while(true) {
            var tOff = 0
            while(tOff < t.size) {
                hMac.update(v, 0, v.size)
                hMac.doFinal(v, 0)
                val len = minOf(t.size - tOff, v.size)
                v.copyInto(t, tOff, 0, len)
                tOff += len
            }
            val candidate = bitsToInt(t)
            if(candidate.signum() > 0 && candidate < n) {
                return candidate
            }
            hMac.update(v, 0, v.size)
            hMac.update(0x00)
            hMac.doFinal(k, 0)
            hMac.init(KeyParameter(k))
            hMac.update(v, 0, v.size)
            hMac.doFinal(v, 0)
        }
    }

    private fun bitsToInt(t: ByteArray): BigInteger {
        var value = BigInteger(1, t)
        if(t.size * 8 > n.bitLength()) {
            value = value.shiftRight(t.size * 8 - n.bitLength())
        }
        return value
    }
}

actual fun secpSignSchnorr(hash32: ByteArray, privKey32: ByteArray): ByteArray {
    require(hash32.size == 32 && privKey32.size == 32) { "Hash and key must be 32 bytes" }
    return secp { signSchnorr(hash32.copyOf(), privKey32.copyOf(), ByteArray(32)) }
}

actual fun secpVerifySchnorr(sig64: ByteArray, hash32: ByteArray, xOnlyPub32: ByteArray): Boolean {
    require(sig64.size == 64 && hash32.size == 32 && xOnlyPub32.size == 32) { "Signature 64 bytes, hash and key 32 bytes" }
    return try {
        secp { verifySchnorr(sig64.copyOf(), hash32.copyOf(), xOnlyPub32.copyOf()) }
    } catch(e: IllegalArgumentException) {
        false //an x-only key not on the curve is an invalid signature, not an error
    }
}

actual fun secpPrivKeyNegate(privKey32: ByteArray): ByteArray {
    require(privKey32.size == 32) { "Private key must be 32 bytes" }
    return secp { privKeyNegate(privKey32.copyOf()) }
}
