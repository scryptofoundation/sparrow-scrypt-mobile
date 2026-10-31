package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImportRulesTest {
    @Test
    fun mwebNeedsLitecoin() {
        assertNull(ImportRules.problem(Coin.LITECOIN, "MWEB", linkedMweb = false))
        assertNull(ImportRules.problem(Coin.LITECOIN, "P2WPKH", linkedMweb = true))
        assertNotNull(ImportRules.problem(Coin.DOGECOIN, "MWEB", linkedMweb = false))
        assertEquals("Only Litecoin wallets have a linked MWEB wallet",
            ImportRules.problem(Coin.BELLSCOIN, "P2WPKH", linkedMweb = true))
    }

    @Test
    fun legacyIsAllowedOnDogecoin() {
        assertNull(ImportRules.problem(Coin.DOGECOIN, "P2PKH", linkedMweb = false))
        assertNull(ImportRules.problem(Coin.LITECOIN, "P2SH_P2WPKH", linkedMweb = false))
        assertNull(ImportRules.problem(Coin.LITECOIN, null, linkedMweb = false))
    }

    @Test
    fun segwitIsRefusedOnDogecoin() {
        assertEquals("Dogecoin has no P2WPKH wallets", ImportRules.problem(Coin.DOGECOIN, "P2WPKH", linkedMweb = false))
        assertEquals("Mobile does not support P2TR wallets yet", ImportRules.problem(Coin.LITECOIN, "P2TR", linkedMweb = false))
    }
}
