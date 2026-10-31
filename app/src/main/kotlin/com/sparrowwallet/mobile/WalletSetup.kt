package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType

/** What creating or restoring a wallet may choose for each coin. */
object WalletSetup {
    /** Script types the coin offers, its default first. */
    fun scriptTypes(coin: Coin): List<ScriptType> =
        if(coin.segwit) listOf(ScriptType.P2WPKH, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH) else listOf(ScriptType.P2PKH)

    fun derivation(coin: Coin, network: Network, scriptType: ScriptType): String =
        ChainParams.of(coin, network).derivationPath(scriptType)

    /** Electrum seeds exist only in Electrum-LTC; no other coin's wallet makes them. */
    fun electrumSeedAllowed(coin: Coin): Boolean = coin == Coin.LITECOIN

    fun scriptTypeLabel(scriptType: ScriptType): String = when(scriptType) {
        ScriptType.P2WPKH -> "Native Segwit"
        ScriptType.P2SH_P2WPKH -> "Nested Segwit"
        ScriptType.P2PKH -> "Legacy"
    }
}
