package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.electrum.SocksProxy
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Opens the right reader for an indexer URL, like the desktop's OrdClient.detect and checkChain, and refuses one for
 * another chain before any of its answers can be used. The readers' constructors are internal, so this is the only way
 * the app gets one.
 */
object Indexers {
    const val UNSUPPORTED = "This kind of indexer is not supported on the phone yet. Use Built-in."

    private const val NINTONDO_DOMAIN = ".nintondo.io"

    //ordinalslite.com indexes Litecoin mainnet only; Nintondo's doge testnet answered with 502s, so it is left out.
    //Junkcoin and Luckycoin have no public indexer, and no Dingocoin coin carries an NFT.
    private val PUBLIC = mapOf(
        Coin.LITECOIN to mapOf(Network.MAINNET to "https://ordinalslite.com"),
        Coin.BELLSCOIN to mapOf(Network.MAINNET to "https://bells-mainnet-api.nintondo.io", Network.TESTNET to "https://bells-testnet-api.nintondo.io"),
        Coin.DOGECOIN to mapOf(Network.MAINNET to "https://doge-mainnet-api.nintondo.io"),
        Coin.PEPECOIN to mapOf(Network.MAINNET to "https://pepe-mainnet-api.nintondo.io", Network.TESTNET to "https://pepe-testnet-api.nintondo.io")
    )

    /** The public indexer for a coin and network, or null when there is none. */
    fun publicUrl(coin: Coin, network: Network): String? = PUBLIC[coin]?.get(network)

    /** True for Nintondo's indexer, known by its host name; false for anything that is not a URL. */
    fun isNintondo(url: String): Boolean =
        runCatching { URI(url.trim()).host?.lowercase(Locale.ROOT)?.endsWith(NINTONDO_DOMAIN) == true }.getOrDefault(false)

    /**
     * The reader for [url], with its chain checked:
     * - a Nintondo host: its status must name this coin and network;
     * - otherwise GET /status must be ord's JSON naming a chain, which must be [network], and the server's block 0 must
     *   be the coin's genesis block on that network. ord names only the network, so a Bitcoin ord or an ord-dogecoin
     *   server would pass for Litecoin on its name alone (the desktop checks Litecoin by name only; the phone is stricter,
     *   as Litecoin is where ord's empty answer can clear a coin);
     * - anything else throws [IndexerException] with [UNSUPPORTED].
     * A wrong chain throws [IndexerException] with the desktop's wording; a 429 throws
     * [com.sparrowwallet.mobile.electrum.RateLimitedException]; plain http through [proxy] is refused before any request.
     * The reader is closed on every failure.
     */
    suspend fun open(url: String, coin: Coin, network: Network, proxy: SocksProxy?): InscriptionIndexer =
        open(url, coin, network, proxy, isNintondo(url))

    /** [open] with the kind given, so a local test server can stand in for Nintondo's host. */
    internal suspend fun open(url: String, coin: Coin, network: Network, proxy: SocksProxy?, nintondo: Boolean): InscriptionIndexer {
        val indexer = try {
            if(nintondo) NintondoIndexer(url, proxy, coin, network) else OrdIndexer(url, proxy, network)
        } catch(e: URISyntaxException) {
            throw IndexerException("Not an http or https URL", e)
        } catch(e: IllegalArgumentException) {
            throw IndexerException(e.message ?: "Not an http or https URL", e)
        }
        try {
            //Nintondo's status names the coin and network; ord's names the network, and is refused if it is not ord's
            indexer.status()
            if(indexer is OrdIndexer) requireGenesis(indexer, coin, network)
            return indexer
        } catch(e: Throwable) {
            indexer.close()
            throw e
        }
    }

    /**
     * ord 0.20 names only the network, so its block 0 tells the coin (the desktop's checkChain, for Litecoin too). A hash
     * that is missing (no /blockhash/0 and no /block/0), malformed or another chain's refuses the server.
     */
    private suspend fun requireGenesis(indexer: OrdIndexer, coin: Coin, network: Network) {
        val expected = genesis(coin, network)
            ?: throw IndexerException("Cannot check that this inscriptions server is for ${coin.displayName} ${network.displayName}")
        val genesis = indexer.genesis()
            ?: throw IndexerException("Inscriptions server did not give its first block hash, so it cannot be checked to be a ${coin.displayName} server")
        if(genesis != expected) {
            throw IndexerException("Inscriptions server is for another chain (its block 0 is ${genesis.take(16)}...), not a ${coin.displayName} ${network.displayName} server")
        }
    }

    /**
     * Genesis block hashes (display order, lowercase hex). Litecoin's are the hashes of the genesis headers compiled into
     * drongo's Network (the phone's chain parameters carry none); the others are copied from the desktop's ChainGenesis,
     * which cites each chain's chainparams.cpp. Bellscoin and Junkcoin share one genesis between mainnet and testnet: the
     * genesis names the coin and the status names the network. Dingocoin shares Dogecoin's genesis and has no
     * inscriptions, so it is not listed; a coin not listed cannot be checked and is refused.
     */
    internal fun genesis(coin: Coin, network: Network): String? = when(coin) {
        Coin.LITECOIN -> when(network) {
            Network.MAINNET -> "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2"
            Network.TESTNET -> "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0"
        }
        Coin.DOGECOIN -> when(network) {
            Network.MAINNET -> "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691"
            Network.TESTNET -> "bb0a78264637406b6360aad926284d544d7049f45189db5664f3c4d07350559e"
        }
        Coin.PEPECOIN -> when(network) {
            Network.MAINNET -> "37981c0c48b8d48965376c8a42ece9a0838daadb93ff975cb091f57f8c2a5faa"
            Network.TESTNET -> "f9f4ea4ae7f6ea4c55040ede2019ba0a53e262f46ec9bce3dcda2cb11f96fc52"
        }
        Coin.BELLSCOIN -> "e5be24df57c43a82d15c2f06bda961296948f8f8eb48501bed1efb929afe0698"
        Coin.JUNKCOIN -> "a2effa738145e377e08a61d76179c21703e13e48910b30a2a87f0dfe794b64c6"
        else -> null
    }
}
