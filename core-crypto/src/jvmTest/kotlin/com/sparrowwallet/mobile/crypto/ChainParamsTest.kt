package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChainParamsTest {
    @Test fun everyEntryMatchesDrongo() {
        for(e in CoinVectors.file.entries) {
            val p = ChainParams.of(Coin.valueOf(e.coin), Network.valueOf(e.network))
            val tag = "${e.coin} ${e.network}"
            assertEquals(e.params.p2pkhVersion, p.p2pkhVersion, "$tag p2pkh")
            assertEquals(e.params.p2shVersion, p.p2shVersion, "$tag p2sh")
            assertEquals(e.params.wifVersion, p.wifVersion, "$tag wif")
            assertEquals(e.params.xpubHeader.toLong(16).toInt(), p.xpubHeader, "$tag xpub")
            assertEquals(e.params.xprvHeader.toLong(16).toInt(), p.xprvHeader, "$tag xprv")
            assertEquals(e.params.bech32Hrp, p.bech32Hrp, "$tag hrp")
            assertEquals(e.params.mwebHrp, p.mwebHrp, "$tag mweb hrp")
            assertEquals(e.params.coinType, p.coin.coinType, tag)
            assertEquals(e.params.recommendedFeeRate, p.coin.recommendedFeeRate, tag)
            assertEquals(e.params.dustLimit, p.coin.dustLimit, tag)
            assertEquals(e.params.segwit, p.coin.segwit, tag)
            assertEquals(e.params.mweb, p.coin.mweb, tag)
            assertEquals(e.params.auxPow, p.coin.auxPow, tag)
            assertEquals(e.params.ticker, p.coin.ticker, tag)
            assertEquals(e.params.subunit, p.coin.subunit, tag)
            assertEquals(e.params.coingeckoId, p.coin.coingeckoId, tag)
            assertEquals(e.params.uriScheme, p.coin.uriScheme, tag)
            for(t in e.scriptTypes) assertEquals(t.derivationPath, p.derivationPath(ScriptType.valueOf(t.scriptType)), "$tag ${t.scriptType}")
        }
        assertEquals(14, CoinVectors.file.entries.size)
    }

    @Test fun backendsFollowTheSpec() {
        assertEquals(setOf(Coin.LITECOIN, Coin.DOGECOIN, Coin.DINGOCOIN), Coin.entries.filter { it.backend == BackendKind.ELECTRUM }.toSet())
    }

    @Test fun onlySegwitCoinsOfferSegwitScripts() {
        assertEquals(setOf(Coin.LITECOIN, Coin.BELLSCOIN), Coin.entries.filter { it.supports(ScriptType.P2WPKH) }.toSet())
        assertTrue(Coin.entries.all { it.supports(ScriptType.P2PKH) })
    }

    @Test fun litecoinParamsEqualTodaysNetworkValues() {
        for(n in Network.entries) {
            val p = n.litecoin
            assertEquals(n.p2pkhVersion, p.p2pkhVersion)
            assertEquals(n.bech32Hrp, p.bech32Hrp)
            assertEquals(n.xpubHeader, p.xpubHeader)
        }
    }

    @Test fun dingoFeeSizeRoundsUpToKilobytes() {
        assertEquals(2000, Coin.DINGOCOIN.feeSize(1001))
        assertEquals(1000, Coin.DINGOCOIN.feeSize(1000))
        assertEquals(1001, Coin.DOGECOIN.feeSize(1001))
    }

    @Test fun fromIdRejectsUnknownCoin() {
        assertFailsWith<IllegalArgumentException> { Coin.fromId("bitcoin") }
        assertEquals(Coin.DOGECOIN, Coin.fromId("DOGECOIN".lowercase()))
    }

    @Test fun fromNameReadsDrongoNames() {
        assertEquals(Coin.PEPECOIN, Coin.fromName("PEPECOIN"))
        assertFailsWith<IllegalArgumentException> { Coin.fromName("BITCOIN") }
    }
}
