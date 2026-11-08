package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BIP340 Schnorr in libsecp256k1, with the zero aux drongo signs with. Vectors from bip-0340/test-vectors.csv. */
class SchnorrTest {
    private val key3 = "0000000000000000000000000000000000000000000000000000000000000003"
    private val pub3 = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9"

    @Test
    fun bip340VectorZeroAux() {
        val hash = ByteArray(32)
        val sig = secpSignSchnorr(hash, key3.hexToBytes())
        assertEquals(
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0".lowercase(),
            sig.toHex()
        )
        assertTrue(secpVerifySchnorr(sig, hash, pub3.hexToBytes()))
    }

    @Test
    fun bip340VerifyVectors() {
        //vector 4: valid
        assertTrue(secpVerifySchnorr(
            ("00000000000000000000003B78CE563F89A0ED9414F5AA28AD0D96D6795F9C63" +
                "76AFB1548AF603B3EB45C9F8207DEE1060CB71C04E80F593060B07D28308D7F4").hexToBytes(),
            "4DF3C3F68FCC83B27E9D42C90431A72499F17875C81A599B566C9889B9696703".hexToBytes(),
            "D69C3509BB99E412E68B0FE8544E72837DFA30746D8BE2AA65975F29D22DC7B9".hexToBytes()
        ))
        //vector 5: public key not on the curve
        assertFalse(secpVerifySchnorr(
            ("6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769" +
                "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B").hexToBytes(),
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89".hexToBytes(),
            "EEFDEA4CDB677750A420FEE807EACF21EB9898AE79B9768766E4FAA04A2D4A34".hexToBytes()
        ))
    }

    @Test
    fun negateTwiceIsIdentity() {
        val k = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef".hexToBytes()
        val copy = k.copyOf()
        val negated = secpPrivKeyNegate(k)
        assertContentEquals(copy, k)
        assertTrue(!negated.contentEquals(k))
        assertContentEquals(k, secpPrivKeyNegate(negated))
        assertContentEquals(copy, k)
    }

    @Test
    fun inputsAreLeftUnchanged() {
        val hash = ByteArray(32) { (it * 7 + 1).toByte() }
        val key = key3.hexToBytes()
        val hashCopy = hash.copyOf()
        val keyCopy = key.copyOf()
        secpSignSchnorr(hash, key)
        assertContentEquals(hashCopy, hash)
        assertContentEquals(keyCopy, key)
    }
}
