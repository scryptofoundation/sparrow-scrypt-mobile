package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletMetaTest {
    @Test
    fun metaWithoutCoinReadsAsLitecoin() {
        val meta = WalletMeta.decode("""{"network":"mainnet","scriptType":"P2WPKH"}""")
        assertEquals(Coin.LITECOIN, meta.coin)
        assertEquals(Network.MAINNET, meta.network)
        assertEquals("P2WPKH", meta.scriptType)
        assertEquals(null, meta.linkedWallet)
    }

    @Test
    fun metaRoundTripsCoin() {
        val json = WalletMeta.encode(Network.TESTNET, Coin.DOGECOIN, "P2PKH", "Other")
        assertEquals(WalletMeta.Decoded(Network.TESTNET, Coin.DOGECOIN, "P2PKH", "Other"), WalletMeta.decode(json))
    }

    @Test
    fun metaWithUnknownCoinFails() {
        assertFailsWith<IllegalArgumentException> {
            WalletMeta.decode("""{"network":"mainnet","scriptType":"P2PKH","coin":"bitcoin"}""")
        }
    }
}
