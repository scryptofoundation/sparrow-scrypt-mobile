package com.sparrowwallet.mobile.crypto

/** A coin id or name this build does not know; never read as Litecoin. */
class UnknownCoinException(id: String) : IllegalArgumentException("Unknown coin: $id")

/** How a coin's wallets reach the chain: an Electrum server or an Esplora REST API. */
enum class BackendKind { ELECTRUM, ESPLORA }

/** Single-signature script types the phone can derive, build and sign. MWEB is handled apart. */
enum class ScriptType(val purpose: Int) {
    P2PKH(44),
    P2SH_P2WPKH(49),
    P2WPKH(84)
}

/**
 * The chains Sparrow-Scrypt supports, in lockstep with drongo's Coin enum (values checked by
 * ChainParamsTest against vectors/coins.json). A wallet has one coin. Dogecoin and Dingocoin
 * share every address and key byte, so the coin always comes from the wallet and is never
 * inferred from an address, xpub or path.
 */
enum class Coin(
    val id: String,
    val displayName: String,
    val ticker: String,
    val coingeckoId: String,
    /** BIP44 coin type on mainnet. Luckycoin and Junkcoin use 0, as their own wallets do. */
    val coinType: Int,
    val auxPow: Boolean,
    val segwit: Boolean,
    val mweb: Boolean,
    /** Lowest fee rate the chain relays, in sats (lits) per vbyte. */
    val recommendedFeeRate: Double,
    /** drongo's dust limit in sats; 0 means the standard segwit rule. */
    val dustLimit: Long,
    val backend: BackendKind
) {
    LITECOIN("litecoin", "Litecoin", "LTC", "litecoin", 2, false, true, true, 1.0, 0, BackendKind.ELECTRUM),
    DOGECOIN("dogecoin", "Dogecoin", "DOGE", "dogecoin", 3, true, false, false, 1000.0, 1_000_000, BackendKind.ELECTRUM),
    PEPECOIN("pepecoin", "Pepecoin", "PEP", "pepecoin-network", 3434, true, false, false, 1000.0, 1_000_000, BackendKind.ESPLORA),
    LUCKYCOIN("luckycoin", "Luckycoin", "LKY", "luckycoin", 0, true, false, false, 1000.0, 1_000_000, BackendKind.ESPLORA),
    BELLSCOIN("bellscoin", "Bellscoin", "BELLS", "bellscoin", 762, false, true, false, 10.0, 10_000, BackendKind.ESPLORA),
    DINGOCOIN("dingocoin", "Dingocoin", "DINGO", "dingocoin", 3, true, false, false, 100000.0, 1_000, BackendKind.ELECTRUM),
    JUNKCOIN("junkcoin", "Junkcoin", "JKC", "junkcoin", 0, true, false, false, 100.0, 100_000, BackendKind.ESPLORA);

    /** Name of the smallest unit: Litecoin's litoshis are "lits", the others use "sats". */
    val subunit: String get() = if(this == LITECOIN) "lits" else "sats"

    val uriScheme: String get() = id

    fun supports(scriptType: ScriptType): Boolean = scriptType == ScriptType.P2PKH || segwit

    /** The size a fee rate is charged on: Dingocoin Core rounds up to whole kilobytes. */
    fun feeSize(vsize: Long): Long = if(this == DINGOCOIN) (vsize + 999) / 1000 * 1000 else vsize

    companion object {
        fun fromId(id: String): Coin =
            entries.firstOrNull { it.id == id.lowercase() } ?: throw UnknownCoinException(id)

        /** Reads drongo's enum names, as desktop writes them ("DOGECOIN"). */
        fun fromName(name: String): Coin =
            entries.firstOrNull { it.name == name } ?: throw UnknownCoinException(name)
    }
}
