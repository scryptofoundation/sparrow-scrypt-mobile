package com.sparrowwallet.mobile.crypto

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaprootKeyTest {
    private val abandon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun bip86Vector() {
        val key = HDKey.fromSeed(Bip39.seed(abandon, "")).derivePath("m/86'/0'/0'/0/0")
        assertEquals("cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115", Taproot.xOnly(key.pubKey).toHex())
        val output = Taproot.outputKey(key.pubKey)
        assertEquals("a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c", output.toHex())
        val address = Addresses.forKey(key.pubKey, ScriptType.P2TR, ChainParams.of(Coin.LITECOIN, Network.MAINNET))
        val decoded = Bech32.decodeWitness(address)
        assertEquals("ltc", decoded.hrp)
        assertEquals(1, decoded.version)
        assertContentEquals(output, decoded.program)
    }

    @Test
    fun drongoLitecoinVector() {
        val internal = "02d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d".hexToBytes()
        assertEquals("53a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343", Taproot.outputKey(internal).toHex())
        assertEquals(
            "ltc1p2wsldez5mud2yam29q22wgfh9439spgduvct83k3pm50fcxa5dpshpe9c3",
            Addresses.forKey(internal, ScriptType.P2TR, ChainParams.of(Coin.LITECOIN, Network.MAINNET))
        )
    }

    @Test
    fun nintondoBellscoinVector() {
        val pub = "02688337c538eb7aaed6dfdd1dc572f30ec288eb833f19c141f9ec82795842da02".hexToBytes()
        assertEquals(
            "bel1paacjc7szg778dqsx3amwy7t02p8j5hsd9em3gxlq9jl9rl88jf3s53f3ea",
            Addresses.forKey(pub, ScriptType.P2TR, ChainParams.of(Coin.BELLSCOIN, Network.MAINNET))
        )
    }

    @Test
    fun tweakedPrivKeyMatchesOutputKeyForBothParities() {
        val random = SecureRandom()
        var even = false
        var odd = false
        repeat(200) {
            val k = ByteArray(32).also { random.nextBytes(it) }
            val pub = secpPublicKeyCreate(k)
            if(pub[0].toInt() == 2) even = true else odd = true
            val copy = k.copyOf()
            val tweaked = Taproot.tweakPrivKey(k)
            assertContentEquals(copy, k, "input key must not be modified")
            assertContentEquals(Taproot.outputKey(pub), Taproot.xOnly(secpPublicKeyCreate(tweaked)))
        }
        assertTrue(even && odd, "both 02 and 03 internal keys must occur")
    }

    @Test
    fun outputScriptFromXpubMatchesFromPrivateKey() {
        val account = HDKey.fromSeed(Bip39.seed(abandon, "")).derivePath("m/86'/0'/0'")
        val watch = XpubKey.fromXpub(account.xpub())
        for(i in 0L..4L) {
            val fromPriv = Scripts.outputScript(account.deriveChild(0).deriveChild(i).pubKey, ScriptType.P2TR)
            val fromXpub = Scripts.outputScript(watch.deriveChild(0).deriveChild(i).pubKey, ScriptType.P2TR)
            assertContentEquals(fromPriv, fromXpub)
            assertEquals(34, fromPriv.size)
            assertEquals(0x51, fromPriv[0].toInt())
            assertEquals(0x20, fromPriv[1].toInt())
        }
    }
}
