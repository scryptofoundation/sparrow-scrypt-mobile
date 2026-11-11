package com.sparrowwallet.mobile.electrum.indexer

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
 */
class OrdIndexer(baseUrl: String, proxy: SocksProxy?) : InscriptionIndexer {
    init {
        //Through a proxy or Tor, plain http could be read or forged by an exit node (the desktop's OrdClient.forApp)
        if(proxy != null && baseUrl.trim().lowercase(Locale.ROOT).startsWith("http://") && !isOnion(baseUrl)) {
            throw IndexerException("This inscriptions indexer uses plain http, which is not safe through a proxy or Tor. Use an https or .onion indexer.")
        }
    }

    private val http = HttpEndpoint(baseUrl, proxy, label = LABEL)
    @Volatile private var known: IndexerStatus? = null

    /** False until [status] has said the server indexes runes; [outputs] reads the status again on every call. */
    override val runesAware: Boolean get() = known?.runeIndex == true

    override suspend fun status(): IndexerStatus {
        val r = http.get("/status", JSON)
        requireOk(r)
        return read { OrdParse.status(r.body) }.also { known = it }
    }

    override suspend fun outputs(outpoints: List<String>): Map<String, IndexerOutput> {
        for(outpoint in outpoints) require(OrdParse.OUTPOINT.matches(outpoint)) { "Invalid outpoint ${outpoint.take(80)}" }
        val asked = outpoints.distinct()
        if(asked.isEmpty()) return emptyMap()
        //An empty answer can be vouched for only by a server that indexes runes; read once per call, so a stale answer does not keep vouching
        val runeIndex = status().runeIndex
        val outputs = LinkedHashMap<String, IndexerOutput>()
        for(batch in batches(asked)) {
            val r = http.post("/outputs", JsonArray(batch.map { JsonPrimitive(it) }).toString(), JSON, JSON)
            rateLimited(r)
            if(r.status == 404 || r.status == 405) {
                //Older servers have no batch route
                for(outpoint in batch) outputs[outpoint] = output(outpoint, runeIndex)
                continue
            }
            requireOk(r)
            outputs.putAll(read { OrdParse.outputs(batch, r.body, runeIndex) })
        }
        return outputs
    }

    /**
     * Batches of at most [BATCH_SIZE], in asking order, with no two outputs of one transaction in the same batch: ord's
     * answers name the transaction but not the output, so only then does the transaction check catch a misordered answer.
     */
    private fun batches(asked: List<String>): List<List<String>> {
        val batches = ArrayList<List<String>>()
        var pending = asked
        while(pending.isNotEmpty()) {
            val batch = ArrayList<String>()
            val txids = HashSet<String>()
            val later = ArrayList<String>()
            for(outpoint in pending) {
                if(batch.size < BATCH_SIZE && txids.add(outpoint.substringBefore(':'))) batch += outpoint else later += outpoint
            }
            batches += batch
            pending = later
        }
        return batches
    }

    private suspend fun output(outpoint: String, runeIndex: Boolean): IndexerOutput {
        val r = http.get("/output/$outpoint", JSON)
        rateLimited(r)
        //An output the indexer does not know is unknown, never clean
        if(r.status == 404) return IndexerOutput.unknown(outpoint)
        requireOk(r)
        return read { OrdParse.output(outpoint, r.body, runeIndex) }
    }

    override suspend fun inscription(id: String): IndexerInscription? {
        require(OrdParse.INSCRIPTION_ID.matches(id)) { "Invalid inscription id ${id.take(80)}" }
        val r = http.get("/inscription/$id", JSON)
        rateLimited(r)
        if(r.status == 404) return null
        requireOk(r)
        val inscription = read { OrdParse.inscription(r.body) }
        if(inscription.id != id) throw IndexerException("${http.url} answered for another inscription than $id")
        return inscription
    }

    override fun close() = http.close()

    private fun rateLimited(r: HttpEndpoint.Response) {
        if(r.status == 429) throw RateLimitedException("${http.url} answered HTTP 429 (rate limited)")
    }

    private fun requireOk(r: HttpEndpoint.Response) {
        rateLimited(r)
        if(r.status != 200) throw IndexerException("${http.url} answered HTTP ${r.status}")
    }

    private inline fun <T> read(parse: () -> T): T = try {
        parse()
    } catch(e: IllegalArgumentException) {
        throw IndexerException("${http.url}: ${e.message}", e)
    }

    companion object {
        const val LABEL = "Inscription indexer"

        /** An onion service: Tor carries it end to end, so plain http to it is not exposed to an exit node. */
        private fun isOnion(url: String): Boolean =
            runCatching { URI(url.trim()).host?.lowercase(Locale.ROOT)?.endsWith(".onion") == true }.getOrDefault(false)
        private const val BATCH_SIZE = 100
        private const val JSON = "application/json"
    }
}

internal actual fun showableSymbol(codePoint: Int): Boolean {
    if(Character.isISOControl(codePoint)) return false
    return when(Character.getType(codePoint).toByte()) {
        Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE -> false
        else -> true
    }
}
