package com.sparrowwallet.mobile.crypto

/** Address, key and derivation constants for one coin on one network (drongo's Network under a coin scope). */
data class ChainParams(
    val coin: Coin,
    val network: Network,
    val p2pkhVersion: Int,
    val p2shVersion: Int,
    /** Deprecated P2SH version also accepted when decoding (Litecoin's Bitcoin-shared prefix). */
    val p2shLegacyVersion: Int?,
    val wifVersion: Int,
    val xpubHeader: Int,
    val xprvHeader: Int,
    /** null on chains without segwit. */
    val bech32Hrp: String?,
    /** Litecoin only. */
    val mwebHrp: String?
) {
    val defaultScriptType: ScriptType get() = if(coin.segwit) ScriptType.P2WPKH else ScriptType.P2PKH

    /** BIP44 coin type for this network: every coin's testnet uses 1, as drongo derives it. */
    val coinType: Int get() = if(network == Network.TESTNET) 1 else coin.coinType

    fun derivationPath(scriptType: ScriptType = defaultScriptType, account: Int = 0): String =
        "m/${scriptType.purpose}'/$coinType'/$account'"

    /** Smallest output the chain relays; Litecoin keeps the standard 546. */
    val dustLimit: Long get() = if(coin.dustLimit == 0L) 546L else coin.dustLimit

    companion object {
        private const val XPUB = 0x0488B21E
        private const val XPRV = 0x0488ADE4
        private const val DGUB = 0x02FACAFD
        private const val DGPV = 0x02FAC398
        private const val TPUB = 0x043587CF
        private const val TPRV = 0x04358394

        fun of(coin: Coin, network: Network): ChainParams = if(network == Network.MAINNET) when(coin) {
            Coin.LITECOIN -> ChainParams(coin, network, 48, 50, 5, 176, XPUB, XPRV, "ltc", "ltcmweb")
            Coin.DOGECOIN -> ChainParams(coin, network, 30, 22, null, 158, DGUB, DGPV, null, null)
            Coin.PEPECOIN -> ChainParams(coin, network, 56, 22, null, 158, DGUB, DGPV, null, null)
            Coin.LUCKYCOIN -> ChainParams(coin, network, 47, 5, null, 175, XPUB, XPRV, null, null)
            Coin.BELLSCOIN -> ChainParams(coin, network, 25, 30, null, 153, DGUB, DGPV, "bel", null)
            Coin.DINGOCOIN -> ChainParams(coin, network, 30, 22, null, 158, DGUB, DGPV, null, null)
            Coin.JUNKCOIN -> ChainParams(coin, network, 16, 5, null, 144, XPUB, XPRV, null, null)
        } else when(coin) {
            Coin.LITECOIN -> ChainParams(coin, network, 111, 58, 196, 239, TPUB, TPRV, "tltc", "tmweb")
            Coin.BELLSCOIN -> ChainParams(coin, network, 33, 22, null, 158, TPUB, TPRV, "tbel", null)
            Coin.JUNKCOIN -> ChainParams(coin, network, 111, 5, null, 239, TPUB, TPRV, null, null)
            Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN ->
                ChainParams(coin, network, 113, 196, null, 241, TPUB, TPRV, null, null)
        }
    }
}

/** Litecoin's parameters on this network, for the call sites that predate the coin axis. */
val Network.litecoin: ChainParams get() = ChainParams.of(Coin.LITECOIN, this)
