package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType

/** What creating or restoring a wallet may choose for each coin. */
object WalletSetup {
    /** Script types the coin offers on the network, its default first, Taproot last and only where it is active. */
    fun scriptTypes(coin: Coin, network: Network): List<ScriptType> {
        val base = if(coin.segwit) listOf(ScriptType.P2WPKH, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH) else listOf(ScriptType.P2PKH)
        return if(ChainParams.of(coin, network).supports(ScriptType.P2TR)) base + ScriptType.P2TR else base
    }

    fun derivation(coin: Coin, network: Network, scriptType: ScriptType): String =
        ChainParams.of(coin, network).derivationPath(scriptType)

    /** The account path for an imported file that carries none: MWEB's m/1000', else the script type's. */
    fun importDerivation(coin: Coin, network: Network, scriptType: String?): String =
        if(scriptType == "MWEB") "m/1000'"
        else derivation(coin, network, scriptType?.let { walletScriptType(it) } ?: ChainParams.of(coin, network).defaultScriptType)

    /** Electrum seeds exist only in Electrum-LTC; no other coin's wallet makes them. */
    fun electrumSeedAllowed(coin: Coin): Boolean = coin == Coin.LITECOIN

    fun scriptTypeLabel(scriptType: ScriptType): String = when(scriptType) {
        ScriptType.P2WPKH -> "Native Segwit"
        ScriptType.P2SH_P2WPKH -> "Nested Segwit"
        ScriptType.P2PKH -> "Legacy"
        ScriptType.P2TR -> "Taproot"
    }
}
