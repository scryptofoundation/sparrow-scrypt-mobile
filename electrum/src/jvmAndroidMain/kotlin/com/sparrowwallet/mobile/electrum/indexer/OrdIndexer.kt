package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.SocksProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.util.Locale

/** An indexer that failed to answer, answered with an error, or answered something that cannot be read. */
class IndexerException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads an ord 0.20 server (ord-litecoin, as at ordinalslite.com), like the desktop's Ord020Dialect: GET /status,
 * POST /outputs in batches of 100 (falling back to GET /output/{outpoint} each on servers without the batch route),
 * GET /inscription/{id}. Requests go through [HttpEndpoint], so they take the coin's proxy, cap the body, and stop in
 * air-gapped mode. A 429 throws [RateLimitedException] at once and is never waited out; any other answer but 200
 * throws [IndexerException], except a 404 for one output (unknown) or one inscription (null).
 *
 * Opened through [Indexers.open], which passes the wallet's [network]: every status read (one per [outputs] call) must
 * name it, and a status that is not ord's means a kind of server the phone does not read.
 */
class OrdIndexer internal constructor(baseUrl: String, proxy: SocksProxy?, private val network: Network? = null) : InscriptionIndexer {
    init {
        refusePlainHttpThroughProxy(baseUrl, proxy)
    }

    private val http = HttpEndpoint(baseUrl, proxy, label = LABEL)
    @Volatile private var known: IndexerStatus? = null

    /** False until [status] has said the server indexes runes; [outputs] reads the status again on every call. */
    override val runesAware: Boolean get() = known?.runeIndex == true

    override suspend fun status(): IndexerStatus {
        //A status read that fails leaves nothing vouching for runes
        known = null
        val r = http.get("/status", JSON)
        http.rateLimited(r)
        //A server without ord's JSON status (ord 0.5's bare OK, a web page, no /status at all) is not one the phone reads
        if(r.status == 404) throw IndexerException(Indexers.UNSUPPORTED)
        http.requireOk(r)
        val status = try {
            OrdParse.status(r.body)
        } catch(e: IllegalArgumentException) {
            throw IndexerException(Indexers.UNSUPPORTED, e)
        }
        //The desktop's OrdClient.requireChain: an answer for another network is never used
        if(network != null && status.chain != network.id) {
            throw IndexerException("Inscriptions server is on ${shown(status.chain)}, wallet is on ${network.displayName}")
        }
        return status.also { known = it }
    }

    /**
     * The hash of block 0 as the server indexed it, like the desktop's Ord020Dialect.genesis: GET /blockhash/0 (the bare
     * hash, or a quoted one), falling back to GET /block/0's hash only when that route is missing. Null when the server
     * gives none, or gives something that is not a hash; a 429 throws.
     */
    internal suspend fun genesis(): String? {
        val r = http.get("/blockhash/0", JSON)
        http.rateLimited(r)
        val hash = when(r.status) {
            200 -> r.body.trim()
            404 -> {
                val block = http.get("/block/0", JSON)
                http.rateLimited(block)
                if(block.status != 200) return null
                http.read { OrdParse.blockHash(block.body) }
            }
            else -> return null
        }
        val bare = if(hash.length == 66 && hash.startsWith('"') && hash.endsWith('"')) hash.substring(1, 65) else hash
        return bare.takeIf { GENESIS.matches(it) }
    }

    override suspend fun outputs(outpoints: List<String>): Map<String, IndexerOutput> {
        for(outpoint in outpoints) require(OrdParse.OUTPOINT.matches(outpoint)) { "Invalid outpoint ${outpoint.take(80)}" }
        val asked = outpoints.distinct()
        if(asked.isEmpty()) return emptyMap()
        //An empty answer can be vouched for only by a server that indexes runes; read once per call, so a stale answer does not keep vouching
        val runeIndex = status().runeIndex
        //ord's batch answers name the transaction but not the output, so only one output per transaction goes in a POST
        //(batches of [BATCH_SIZE]): then the transaction check catches a misordered answer. The others of a transaction
        //are asked one by one.
        val txids = HashSet<String>()
        val (batched, single) = asked.partition { txids.add(it.substringBefore(':')) }
        val found = HashMap<String, IndexerOutput>()
        for(batch in batched.chunked(BATCH_SIZE)) {
            val r = http.post("/outputs", JsonArray(batch.map { JsonPrimitive(it) }).toString(), JSON, JSON)
            http.rateLimited(r)
            if(r.status == 404 || r.status == 405) {
                //Older servers have no batch route
                for(outpoint in batch) found[outpoint] = output(outpoint, runeIndex)
                continue
            }
            http.requireOk(r)
            found.putAll(http.read { OrdParse.outputs(batch, r.body, runeIndex) })
        }
        for(outpoint in single) found[outpoint] = output(outpoint, runeIndex)
        return asked.associateWithTo(LinkedHashMap()) { found.getValue(it) }
    }

    private suspend fun output(outpoint: String, runeIndex: Boolean): IndexerOutput {
        val r = http.get("/output/$outpoint", JSON)
        http.rateLimited(r)
        //An output the indexer does not know is unknown, never clean
        if(r.status == 404) return IndexerOutput.unknown(outpoint)
        http.requireOk(r)
        return http.read { OrdParse.output(outpoint, r.body, runeIndex) }
    }

    override suspend fun inscription(id: String): IndexerInscription? {
        require(OrdParse.INSCRIPTION_ID.matches(id)) { "Invalid inscription id ${id.take(80)}" }
        val r = http.get("/inscription/$id", JSON)
        http.rateLimited(r)
        if(r.status == 404) return null
        http.requireOk(r)
        val inscription = http.read { OrdParse.inscription(r.body) }
        if(inscription.id != id) throw IndexerException("${http.url} answered for another inscription than $id")
        return inscription
    }

    override fun close() = http.close()

    companion object {
        const val LABEL = "Inscription indexer"
        private const val BATCH_SIZE = 100
        private const val JSON = "application/json"
        private val GENESIS = Regex("[0-9a-f]{64}")
    }
}

/**
 * Through a proxy or Tor, plain http could be read or forged by an exit node (the desktop's OrdClient.forApp): refused
 * unless the server is an onion service. Every indexer reader checks each host it talks to before any request.
 */
internal fun refusePlainHttpThroughProxy(baseUrl: String, proxy: SocksProxy?) {
    if(proxy != null && baseUrl.trim().lowercase(Locale.ROOT).startsWith("http://") && !isOnion(baseUrl)) {
        throw IndexerException("This inscriptions indexer uses plain http, which is not safe through a proxy or Tor. Use an https or .onion indexer.")
    }
}

/** An onion service: Tor carries it end to end, so plain http to it is not exposed to an exit node. */
private fun isOnion(url: String): Boolean =
    runCatching { URI(url.trim()).host?.lowercase(Locale.ROOT)?.endsWith(".onion") == true }.getOrDefault(false)

/** A 429 fails at once and is never waited out. */
internal fun HttpEndpoint.rateLimited(r: HttpEndpoint.Response) {
    if(r.status == 429) throw RateLimitedException("$url answered HTTP 429 (rate limited)")
}

/** Any answer but 200 is an error, never an empty answer. */
internal fun HttpEndpoint.requireOk(r: HttpEndpoint.Response) {
    rateLimited(r)
    if(r.status != 200) throw IndexerException("$url answered HTTP ${r.status}")
}

/** A parser's [IllegalArgumentException] as the indexer's error, naming the server. */
internal inline fun <T> HttpEndpoint.read(parse: () -> T): T = try {
    parse()
} catch(e: IllegalArgumentException) {
    throw IndexerException("$url: ${e.message}", e)
}

internal actual fun showableSymbol(codePoint: Int): Boolean {
    if(Character.isISOControl(codePoint)) return false
    return when(Character.getType(codePoint).toByte()) {
        Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE -> false
        else -> true
    }
}
