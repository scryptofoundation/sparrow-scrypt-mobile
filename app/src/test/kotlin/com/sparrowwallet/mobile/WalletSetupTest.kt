package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletSetupTest {
    @Test
    fun scriptTypesFollowSegwit() {
        assertEquals(listOf(ScriptType.P2PKH), WalletSetup.scriptTypes(Coin.DOGECOIN))
        assertEquals(ScriptType.P2WPKH, WalletSetup.scriptTypes(Coin.BELLSCOIN).first())
        assertEquals(listOf(ScriptType.P2WPKH, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH), WalletSetup.scriptTypes(Coin.LITECOIN))
    }

    @Test
    fun derivationMatchesTheCoinVectors() {
        assertEquals("m/44'/3'/0'", WalletSetup.derivation(Coin.DOGECOIN, Network.MAINNET, ScriptType.P2PKH))
        assertEquals("m/84'/1'/0'", WalletSetup.derivation(Coin.LITECOIN, Network.TESTNET, ScriptType.P2WPKH))
        assertEquals("m/44'/762'/0'", WalletSetup.derivation(Coin.BELLSCOIN, Network.MAINNET, ScriptType.P2PKH))
    }

    @Test
    fun electrumSeedsAreLitecoinOnly() {
        assertTrue(WalletSetup.electrumSeedAllowed(Coin.LITECOIN))
        assertFalse(WalletSetup.electrumSeedAllowed(Coin.DOGECOIN))
        assertFalse(WalletSetup.electrumSeedAllowed(Coin.BELLSCOIN))
    }
}
