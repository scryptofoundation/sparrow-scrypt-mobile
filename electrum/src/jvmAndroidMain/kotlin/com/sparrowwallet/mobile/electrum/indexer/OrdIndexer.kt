package com.sparrowwallet.mobile.electrum.indexer

import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.SocksProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

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
    private val http = HttpEndpoint(baseUrl, proxy, label = LABEL)
    @Volatile private var known: IndexerStatus? = null

    /** False until [status] has said the server indexes runes. */
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
        //An empty answer can be vouched for only by a server that indexes runes
        val runeIndex = (known ?: status()).runeIndex
        val outputs = LinkedHashMap<String, IndexerOutput>()
        for(batch in asked.chunked(BATCH_SIZE)) {
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
