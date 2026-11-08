package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaprootRulesTest {
    @Test fun taprootWalletsOnLitecoinAndBellscoinMainnet() {
        assertTrue(ChainParams.of(Coin.LITECOIN, Network.MAINNET).supports(ScriptType.P2TR))
        assertTrue(ChainParams.of(Coin.LITECOIN, Network.TESTNET).supports(ScriptType.P2TR))
        assertTrue(ChainParams.of(Coin.BELLSCOIN, Network.MAINNET).supports(ScriptType.P2TR))
        assertFalse(ChainParams.of(Coin.BELLSCOIN, Network.TESTNET).supports(ScriptType.P2TR))
        assertFalse(ChainParams.of(Coin.JUNKCOIN, Network.TESTNET).supports(ScriptType.P2TR))
        assertFalse(ChainParams.of(Coin.DOGECOIN, Network.MAINNET).supports(ScriptType.P2TR))
    }

    @Test fun coinAloneNeverSupportsTaproot() {
        assertTrue(Coin.entries.none { it.supports(ScriptType.P2TR) })
    }

    @Test fun otherScriptTypesStillFollowTheCoin() {
        assertTrue(ChainParams.of(Coin.BELLSCOIN, Network.TESTNET).supports(ScriptType.P2WPKH))
        assertFalse(ChainParams.of(Coin.DOGECOIN, Network.MAINNET).supports(ScriptType.P2WPKH))
        assertTrue(ChainParams.of(Coin.DOGECOIN, Network.MAINNET).supports(ScriptType.P2PKH))
    }

    @Test fun taprootDestinations() {
        assertTrue(ChainParams.of(Coin.JUNKCOIN, Network.TESTNET).taprootDestinations)
        assertTrue(ChainParams.of(Coin.BELLSCOIN, Network.MAINNET).taprootDestinations)
        assertFalse(ChainParams.of(Coin.BELLSCOIN, Network.TESTNET).taprootDestinations)
    }

    @Test fun bip86Paths() {
        assertEquals("m/86'/2'/0'", ChainParams.of(Coin.LITECOIN, Network.MAINNET).derivationPath(ScriptType.P2TR))
        assertEquals("m/86'/762'/0'", ChainParams.of(Coin.BELLSCOIN, Network.MAINNET).derivationPath(ScriptType.P2TR))
        assertEquals("m/86'/1'/0'", ChainParams.of(Coin.LITECOIN, Network.TESTNET).derivationPath(ScriptType.P2TR))
    }
}
