package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.InscriptionContent
import com.sparrowwallet.mobile.crypto.track.InscriptionTracer
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.BigInteger

/** One inscription on one coin, as the Collectibles grid draws it. [value] is the coin's value when known. */
data class CollectibleTile(
    val inscriptionId: String, val outpoint: String, val label: String, val mintTxid: String,
    val mintHeight: Int, val mintTime: Long, val onCoin: Int, val value: Long?, val source: InscriptionCheck.Source
) {
    /** An unnamed inscription (its id starts with "?") has no content to read, so no preview. */
    val hasPreview: Boolean get() = !inscriptionId.startsWith("?")
}

/** A rune's summed balance; [amount] is already shifted by its divisibility. Display text only. */
data class RuneRow(val name: String, val symbol: String?, val amount: String)

data class UncheckedRow(val outpoint: String, val value: Long?, val reason: String, val canCheckDeeper: Boolean)

data class CollectiblesView(
    val header: String, val tiles: List<CollectibleTile>, val runes: List<RuneRow>?,
    val runesNote: String?, val unchecked: List<UncheckedRow>, val empty: Boolean
)

/**
 * What the Collectibles screen shows, worked out from the stored check rows. Plain Kotlin and deterministic: the same
 * inputs give the same view, whatever the device locale. The rows are the coins the last run listed: an indexer's kept
 * marks for coins no longer listed live beside them and are never passed in, so they cannot show as tiles or balances.
 */
object CollectiblesModel {
    const val RUNES_NOTE = "Rune balances need Public or Your own."
    const val WAITING = "Waiting for a confirmation"
    const val NO_REASON = "Not checked"
    /** An INSCRIBED row with no inscription named in it (a row cut short): never shown as a clean wallet. */
    const val UNNAMED = "Carries an inscription that is not named yet"
    private const val MAX_DIVISIBILITY = 38

    fun build(
        checks: Map<String, InscriptionCheck>?, status: CheckStatus?, setting: InscriptionSetting,
        coin: Coin, deeperQueued: Set<String>
    ): CollectiblesView {
        val rows = checks?.values?.sortedWith(compareBy({ it.txid }, { it.vout })) ?: emptyList()
        val tiles = rows.flatMap { tiles(it) }.sortedWith(
            compareByDescending<CollectibleTile> { if(it.mintHeight <= 0) Int.MAX_VALUE else it.mintHeight }
                .thenByDescending { it.mintTime }.thenBy { it.inscriptionId }.thenBy { it.outpoint }
        )
        val withRunes = coin == Coin.LITECOIN && setting !is InscriptionSetting.BuiltIn
        return CollectiblesView(
            header = header(checks != null, status, setting),
            tiles = tiles,
            runes = if(withRunes) runes(rows) else null,
            runesNote = if(coin == Coin.LITECOIN && setting is InscriptionSetting.BuiltIn) RUNES_NOTE else null,
            unchecked = rows.mapNotNull { unchecked(it, deeperQueued) },
            empty = checks != null && tiles.isEmpty() && rows.none { it.verdict == Verdict.INSCRIBED }
        )
    }

    fun label(found: Found): String = when {
        found.inscriptionId.startsWith("?") -> "Inscription"
        found.number != null -> "#${group(found.number!!)}"
        found.mintHeight <= 0 -> "Unconfirmed"
        else -> "Block ${group(found.mintHeight.toLong())}"
    }

    /** Digits in threes with commas, whatever the device locale. */
    internal fun group(n: Long): String {
        val digits = n.toString()
        val negative = digits.startsWith("-")
        val body = if(negative) digits.substring(1) else digits
        val out = StringBuilder()
        for((i, c) in body.withIndex()) {
            if(i > 0 && (body.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return (if(negative) "-" else "") + out
    }

    private fun header(hasChecks: Boolean, status: CheckStatus?, setting: InscriptionSetting): String = when {
        status != null && status.running -> "Checked ${status.done} of ${status.total} coins"
        !hasChecks -> "Not checked yet"
        status != null && status.indexerUnreachable && setting is InscriptionSetting.Public -> "Public indexer unreachable, checking on-chain"
        status != null && status.indexerUnreachable && setting is InscriptionSetting.YourOwn -> "Your indexer unreachable, checking on-chain"
        else -> "Up to date"
    }

    private fun tiles(row: InscriptionCheck): List<CollectibleTile> {
        if(row.verdict != Verdict.INSCRIBED) return emptyList()
        val distinct = row.found.distinctBy { it.inscriptionId }
        return distinct.map {
            CollectibleTile(it.inscriptionId, row.outpoint, label(it), it.mintTxid, it.mintHeight, it.mintTime, distinct.size, row.value, row.source)
        }
    }

    /**
     * Unchecked: UNCHECKED rows, and CLEAN rows without a mined block behind them. A CLEAN row held by the runes mark is
     * not listed (it shows through the rune balances), but an UNCHECKED one is: its inscription check is still open.
     */
    private fun unchecked(row: InscriptionCheck, queued: Set<String>): UncheckedRow? = when {
        row.verdict == Verdict.UNCHECKED -> {
            val reason = row.reason ?: NO_REASON
            UncheckedRow(row.outpoint, row.value, reason, canCheckDeeper(reason) && row.outpoint !in queued)
        }
        row.verdict == Verdict.CLEAN && !row.proven && !row.runes -> UncheckedRow(row.outpoint, row.value, WAITING, false)
        row.verdict == Verdict.INSCRIBED && row.found.isEmpty() -> UncheckedRow(row.outpoint, row.value, row.reason ?: UNNAMED, false)
        else -> null
    }

    private fun canCheckDeeper(reason: String) = reason == InscriptionTracer.BUDGET_USED || reason == InscriptionTracer.FEES_NEED_DEEPER

    /**
     * Balances summed per rune name over every row that carries a rune list (the marks of rows not listed are not in
     * [rows]). Amounts are decimal integer text up to u128, so they are added as BigInteger and shifted by divisibility
     * as text. When one name arrives with different divisibilities, the first seen (rows in outpoint order, then name
     * order) sets the divisibility and the symbol, and an entry with another divisibility is left out of the sum rather
     * than mixed in. An unreadable amount or a divisibility outside 0..38 is left out too.
     */
    private fun runes(rows: List<InscriptionCheck>): List<RuneRow> {
        class Sum(val divisibility: Int, val symbol: String?, var total: BigInteger)
        val sums = LinkedHashMap<String, Sum>()
        for(row in rows) {
            val list = row.runeList ?: continue
            for(name in list.keys.sorted()) {
                val balance = list.getValue(name)
                if(balance.divisibility !in 0..MAX_DIVISIBILITY || balance.amount.isEmpty() || !balance.amount.all { it in '0'..'9' }) continue
                val amount = BigInteger(balance.amount)
                val sum = sums[name]
                if(sum == null) sums[name] = Sum(balance.divisibility, balance.symbol, amount)
                else if(sum.divisibility == balance.divisibility) sum.total += amount
            }
        }
        return sums.entries.sortedBy { it.key }.map { RuneRow(it.key, it.value.symbol, shift(it.value.total, it.value.divisibility)) }
    }

    private fun shift(total: BigInteger, divisibility: Int): String =
        BigDecimal(total, divisibility).toPlainString()
}

/** A thread-safe least-recently-used map of loaded inscription content. */
class ContentCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<String, InscriptionContent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InscriptionContent>?): Boolean = size > capacity
    }

    fun get(key: String): InscriptionContent? = synchronized(map) { map[key] }
    fun put(key: String, content: InscriptionContent) { synchronized(map) { map[key] = content } }
    fun size(): Int = synchronized(map) { map.size }
}

/** The rules around reading an inscription's content for a preview: kept apart from the repository so they are tested. */
object ContentLoading {
    const val CACHE_ENTRIES = 64

    /** The cache key: content is per coin and network as well as per id. */
    fun key(coin: Coin, network: Network, inscriptionId: String): String = "${coin.id}:${network.id}:$inscriptionId"

    /**
     * Content from [cache] or from [load], which is cached only when it gives content (a failure or a missing body is
     * never kept). Null on any failure and, with no network call and no cache read, while [airGapped]. Cancellation is
     * rethrown.
     */
    suspend fun load(cache: ContentCache, key: String, airGapped: Boolean, load: suspend () -> InscriptionContent?): InscriptionContent? {
        if(airGapped) return null
        cache.get(key)?.let { return it }
        return try {
            load()?.also { cache.put(key, it) }
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            null
        }
    }
}
