package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Addresses
import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendWarningTest {
    private val master = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
    private val doge = ChainParams.of(Coin.DOGECOIN, Network.MAINNET)
    private val dogeReceive0 = Addresses.forKey(master.derivePath("m/44'/3'/0'/0/0").pubKey, ScriptType.P2PKH, doge)

    @Test
    fun sharedEncodingIsReported() {
        assertTrue(Coin.DINGOCOIN in Addresses.coinsSharingEncoding(dogeReceive0, doge))
        val warning = sendWarning(Coin.DOGECOIN, Network.MAINNET, dogeReceive0)
        assertTrue(warning != null && "Dingocoin" in warning, warning)
    }

    @Test
    fun segwitAddressHasNoWarning() {
        val ltc = ChainParams.of(Coin.LITECOIN, Network.MAINNET)
        val address = Addresses.forKey(master.derivePath("m/84'/2'/0'/0/0").pubKey, ScriptType.P2WPKH, ltc)
        assertNull(sendWarning(Coin.LITECOIN, Network.MAINNET, address))
    }
}
