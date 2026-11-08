package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImportRulesTest {
    @Test
    fun mwebNeedsLitecoin() {
        assertNull(ImportRules.problem(Coin.LITECOIN, Network.MAINNET, "MWEB", linkedMweb = false))
        assertNull(ImportRules.problem(Coin.LITECOIN, Network.MAINNET, "P2WPKH", linkedMweb = true))
        assertNotNull(ImportRules.problem(Coin.DOGECOIN, Network.MAINNET, "MWEB", linkedMweb = false))
        assertEquals("Only Litecoin wallets have a linked MWEB wallet",
            ImportRules.problem(Coin.BELLSCOIN, Network.MAINNET, "P2WPKH", linkedMweb = true))
    }

    @Test
    fun legacyIsAllowedOnDogecoin() {
        assertNull(ImportRules.problem(Coin.DOGECOIN, Network.MAINNET, "P2PKH", linkedMweb = false))
        assertNull(ImportRules.problem(Coin.LITECOIN, Network.MAINNET, "P2SH_P2WPKH", linkedMweb = false))
        assertNull(ImportRules.problem(Coin.LITECOIN, Network.MAINNET, null, linkedMweb = false))
    }

    @Test
    fun segwitIsRefusedOnDogecoin() {
        assertEquals("Dogecoin has no P2WPKH wallets", ImportRules.problem(Coin.DOGECOIN, Network.MAINNET, "P2WPKH", linkedMweb = false))
        assertEquals("Mobile does not support P2WSH wallets yet", ImportRules.problem(Coin.LITECOIN, Network.MAINNET, "P2WSH", linkedMweb = false))
    }

    @Test
    fun taprootFollowsTheNetwork() {
        assertNull(ImportRules.problem(Coin.LITECOIN, Network.MAINNET, "P2TR", linkedMweb = false))
        assertNull(ImportRules.problem(Coin.BELLSCOIN, Network.MAINNET, "P2TR", linkedMweb = false))
        assertEquals("Taproot is not active on Bellscoin Testnet", ImportRules.problem(Coin.BELLSCOIN, Network.TESTNET, "P2TR", linkedMweb = false))
        assertEquals("Taproot is not active on Dogecoin Mainnet", ImportRules.problem(Coin.DOGECOIN, Network.MAINNET, "P2TR", linkedMweb = false))
    }
}
