package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.SocksProxy
import java.net.URI

/**
 * Reads Nintondo's indexer for Bellscoin, Dogecoin and Pepecoin, like the desktop's NintondoDialect. Its api host
 * (`X-api.nintondo.io`) answers GET /status and GET /find_meta/{txid}i{vout} per output; the search host (`X-search.nintondo.io/pub`, derived from the api host with the same scheme and port) answers
 * GET /{id}/info. The content host is not used: the phone reads content from the chain.
 *
 * Nintondo has no rune or dune index, so [runesAware] is always false, and an empty find_meta is unknown (see
 * [NintondoParse]). Its status must name the wallet's coin and network ([Indexers.open] checks it before returning the
 * reader). Requests go through [HttpEndpoint] with the coin's proxy, a capped body and the air-gap gate; a 429 throws
 * [com.sparrowwallet.mobile.electrum.RateLimitedException] at once; any other answer but 200 throws [IndexerException],
 * except a 404 for one output (unknown) or one inscription (null).
 */
class NintondoIndexer internal constructor(
    apiUrl: String,
    proxy: SocksProxy?,
    coin: Coin,
    network: Network,
    /** The search host's base; derived from [apiUrl], null when it is not an `X-api` host (no details then). */
    searchUrl: String? = searchBase(apiUrl)
) : InscriptionIndexer {
    init {
        refusePlainHttpThroughProxy(apiUrl, proxy)
        searchUrl?.let { refusePlainHttpThroughProxy(it, proxy) }
    }

    private val http = HttpEndpoint(apiUrl, proxy, label = OrdIndexer.LABEL, usable = { !closed })
    private val search = searchUrl?.let { HttpEndpoint(it, proxy, label = OrdIndexer.LABEL, usable = { !closed }) }
    private val expectedNetworkName = coin.displayName + if(network == Network.TESTNET) " Testnet" else ""
    /** Set by [close]: every later call is refused before any request (see [refuseClosed]). */
    @Volatile private var closed = false

    /** Nintondo indexes inscriptions only: an answer showing no runes says nothing about runes or dunes. */
    override val runesAware: Boolean get() = false

    override suspend fun status(): IndexerStatus {
        refuseClosed(closed)
        val r = http.get("/status", JSON)
        http.requireOk(r)
        return try {
            NintondoParse.status(r.body, expectedNetworkName)
        } catch(e: IllegalArgumentException) {
            //The desktop's wording, shown when a Your own URL is refused on Save
            throw IndexerException(e.message ?: "Not a Nintondo indexer", e)
        }
    }

    override suspend fun outputs(outpoints: List<String>): Map<String, IndexerOutput> {
        for(outpoint in outpoints) require(OrdParse.OUTPOINT.matches(outpoint)) { "Invalid outpoint ${outpoint.take(80)}" }
        val outputs = LinkedHashMap<String, IndexerOutput>()
        for(outpoint in outpoints.distinct()) outputs[outpoint] = output(outpoint)
        return outputs
    }

    private suspend fun output(outpoint: String): IndexerOutput {
        //find_meta takes the outpoint as <txid>i<vout>; checked before each one, so a reader closed mid-pass stops asking
        refuseClosed(closed)
        val r = http.get("/find_meta/${outpoint.replace(':', 'i')}", JSON)
        http.rateLimited(r)
        //An output the indexer does not know is unknown, never clean
        if(r.status == 404) return IndexerOutput.unknown(outpoint)
        http.requireOk(r)
        return http.read { NintondoParse.meta(outpoint, r.body) }
    }

    /**
     * The inscription's number, type and size from the search host's info, which must be for [id]. The desktop also asks
     * the api host's /location for the satpoint it sends from; the phone finds where an inscription is on the chain and
     * [IndexerInscription] holds no place, so that request is not made.
     */
    override suspend fun inscription(id: String): IndexerInscription? {
        require(OrdParse.INSCRIPTION_ID.matches(id)) { "Invalid inscription id ${id.take(80)}" }
        val search = search ?: throw IndexerException("Not a Nintondo api server: ${http.url}")
        refuseClosed(closed)
        val info = search.get("/$id/info", JSON)
        search.rateLimited(info)
        if(info.status == 404) return null
        search.requireOk(info)
        return search.read { NintondoParse.info(id, info.body) }
    }

    /** Closes the reader for good: the call in progress fails with its connection, and every later one is refused. */
    override fun close() {
        closed = true
        http.close()
        search?.close()
    }

    companion object {
        private const val JSON = "application/json"
        private const val API_SUFFIX = "-api"

        /** The search host's base for an api host, with its /pub prefix, or null when the host is not an `X-api` host. */
        fun searchBase(apiUrl: String): String? = sibling(apiUrl, "-search")?.let { "$it/pub" }

        /** `X-api.rest` becomes `X<replacement>.rest`, with the same scheme and port, so the same proxy rules apply. */
        private fun sibling(apiUrl: String, replacement: String): String? {
            val uri = runCatching { URI(apiUrl.trim()) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val label = host.substringBefore('.')
            if(!label.endsWith(API_SUFFIX) || label.length == API_SUFFIX.length) return null
            val siblingHost = label.removeSuffix(API_SUFFIX) + replacement + host.substring(label.length)
            return uri.scheme + "://" + siblingHost + (if(uri.port < 0) "" else ":${uri.port}")
        }
    }
}
