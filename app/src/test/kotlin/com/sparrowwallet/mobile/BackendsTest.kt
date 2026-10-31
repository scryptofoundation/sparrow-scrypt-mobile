package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.electrum.ElectrumBackend
import com.sparrowwallet.mobile.electrum.EsploraBackend
import kotlin.test.Test
import kotlin.test.assertIs

class BackendsTest {
    @Test fun esploraSettingsOpenAnEsploraBackend() {
        val s = PublicServers.forCoin(Coin.PEPECOIN, Network.TESTNET).first()
        assertIs<EsploraBackend>(Backends.open(s, ChainParams.of(Coin.PEPECOIN, Network.TESTNET)))
    }

    @Test fun electrumSettingsOpenAnElectrumBackend() {
        val s = PublicServers.forCoin(Coin.DOGECOIN, Network.MAINNET).first()
        assertIs<ElectrumBackend>(Backends.open(s, ChainParams.of(Coin.DOGECOIN, Network.MAINNET)))
    }
}
