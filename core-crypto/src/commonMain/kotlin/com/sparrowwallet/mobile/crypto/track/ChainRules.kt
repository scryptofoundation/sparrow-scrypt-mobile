package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network

/** Per coin facts the tracer relies on: where inscriptions begin, and block rewards where they are exact. */
object ChainRules {
    private const val COIN = 100_000_000L
    private val COVERED = setOf(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.BELLSCOIN, Coin.JUNKCOIN)

    /** The coins the built-in tracker checks. */
    fun covers(coin: Coin): Boolean = coin in COVERED

    /**
     * Whether the chain has a rune-like token protocol, whose tokens move to a coin with no marker in its creating
     * transaction (unallocated ones go to the first non-OP_RETURN output), so the trace cannot see them: Runes on
     * Litecoin (runestone `6a 5d`), Dunes on Dogecoin (dunestone `6a 01 44`), Dunes in the wonky-ord-pepecoin forks on
     * Pepecoin (live use unverified, kept on the safe side), junes on Junkcoin (ord-junkcoin, the Dunes code with the
     * same `OP_RETURN "D"` prefix). Bellscoin has none (BEL-20 is inscriptions). An unknown coin counts as having one.
     */
    fun hasRuneLikeTokens(coin: Coin): Boolean = coin != Coin.BELLSCOIN

    /**
     * The height of the chain's first inscription, below which no unit can carry one. From each indexer's chain.rs
     * (ord-litecoin, ord-dogecoin, ord-pepecoin, ord-bells, ord-junkcoin); 0 where the indexer has none.
     */
    fun firstInscriptionHeight(coin: Coin, network: Network): Int = when(coin) {
        Coin.LITECOIN -> when(network) {
            Network.MAINNET -> 2_424_429
            Network.TESTNET -> 2_669_127
        }
        Coin.DOGECOIN -> if(network == Network.MAINNET) 4_600_000 else 0
        Coin.PEPECOIN -> if(network == Network.MAINNET) 186_920 else 0
        Coin.BELLSCOIN -> if(network == Network.MAINNET) 22_470 else 0
        Coin.JUNKCOIN -> if(network == Network.MAINNET) 273_000 else 0
        else -> 0
    }

    /**
     * The block reward at a height, only where it is exact and simple: Litecoin's halving schedule, and Dogecoin
     * mainnet's flat reward from height 600,000. Null elsewhere (random or custom schedules).
     */
    fun subsidy(coin: Coin, network: Network, height: Int): Long? {
        if(coin == Coin.LITECOIN) {
            val halvings = height / 840_000
            return if(halvings >= 64) 0L else (50 * COIN) shr halvings
        }
        if(coin == Coin.DOGECOIN && network == Network.MAINNET && height >= 600_000) {
            return 10_000 * COIN
        }
        return null
    }
}
