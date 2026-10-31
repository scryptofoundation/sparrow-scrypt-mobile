package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerKeysTest {
    @Test fun keysArePerCoinAndNetwork() {
        assertEquals("server.dogecoin.mainnet", ServerKeys.prefix(Coin.DOGECOIN, Network.MAINNET))
        assertEquals("server.litecoin.testnet", ServerKeys.prefix(Coin.LITECOIN, Network.TESTNET))
    }

    @Test fun legacyLitecoinSettingsAreKept() {
        assertEquals("server.mainnet", ServerKeys.legacyPrefix(Coin.LITECOIN, Network.MAINNET))
        assertEquals("server.testnet", ServerKeys.legacyPrefix(Coin.LITECOIN, Network.TESTNET))
        for(coin in Coin.entries.filter { it != Coin.LITECOIN }) {
            assertNull(ServerKeys.legacyPrefix(coin, Network.MAINNET), coin.name)
        }
    }
}
