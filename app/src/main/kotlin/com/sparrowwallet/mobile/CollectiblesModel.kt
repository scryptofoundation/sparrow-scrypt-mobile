package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.InscriptionContent
import com.sparrowwallet.mobile.crypto.track.InscriptionTracer
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.math.BigDecimal
import java.math.BigInteger

/**
 * One inscription on one coin, as the Collectibles grid draws it. [value] is the coin's value when known. [mintTxid] is
 * kept as the check found it and is passed unchanged to [WalletRepository.inscriptionContent] as the last step of a
 * Doginals chain. [key] is unique within a view (outpoint, id and position on the coin), for a list key: the same id can
 * appear on more than one coin.
 */
data class CollectibleTile(
    val inscriptionId: String, val outpoint: String, val label: String, val mintTxid: String,
    val mintHeight: Int, val mintTime: Long, val onCoin: Int, val value: Long?, val source: InscriptionCheck.Source,
    val key: String = "$outpoint/$inscriptionId/0"
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
    const val HELD = "Held: an indexer says this coin carries something"
    const val RUNES_UNREADABLE = "Some rune balances could not be read."
    const val MAX_REASON_CHARS = 120
    private const val MAX_AMOUNT_DIGITS = 40
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
                .thenByDescending { it.mintTime }.thenBy { it.inscriptionId }.thenBy { it.outpoint }.thenBy { it.key }
        )
        val withRunes = coin == Coin.LITECOIN && setting !is InscriptionSetting.BuiltIn
        val sums = if(withRunes) sumRunes(rows) else null
        val unchecked = rows.mapNotNull { unchecked(it, deeperQueued, sums?.represented ?: emptySet()) }
        return CollectiblesView(
            header = header(checks != null, status, setting),
            tiles = tiles,
            runes = sums?.rows,
            runesNote = when {
                coin == Coin.LITECOIN && setting is InscriptionSetting.BuiltIn -> RUNES_NOTE
                sums != null && sums.skipped -> RUNES_UNREADABLE
                else -> null
            },
            unchecked = unchecked,
            empty = checks != null && tiles.isEmpty() && unchecked.isEmpty()
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
        return row.found.mapIndexed { at, it ->
            CollectibleTile(
                it.inscriptionId, row.outpoint, label(it), it.mintTxid, it.mintHeight, it.mintTime, row.found.size, row.value,
                row.source, "${row.outpoint}/${it.inscriptionId}/$at"
            )
        }
    }

    /**
     * Server-controlled text made safe to show: line breaks and tabs become spaces, other control, format and invisible
     * code points are dropped (the preview text rule), and it is cut to [MAX_REASON_CHARS] characters.
     */
    internal fun shown(reason: String): String {
        val out = StringBuilder()
        var i = 0
        while(i < reason.length && out.length < MAX_REASON_CHARS) {
            val cp = reason.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code || cp == '\r'.code || cp == '\t'.code -> out.append(' ')
                PreviewRules.isStripped(cp) -> {}
                out.length + Character.charCount(cp) > MAX_REASON_CHARS -> break
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString().trim()
    }

    /**
     * Rows for the Unchecked list: UNCHECKED rows (with their own reason), CLEAN rows without a mined block, an INSCRIBED
     * row with nothing named, and any row Send holds by the runes mark that no tile and no rune balance stands for
     * ([represented]: coins with a balance row). Reasons are compared with the tracer's constants before they are
     * made safe to show.
     */
    private fun unchecked(row: InscriptionCheck, queued: Set<String>, represented: Set<String>): UncheckedRow? = when {
        row.verdict == Verdict.UNCHECKED -> {
            val reason = row.reason ?: NO_REASON
            UncheckedRow(row.outpoint, row.value, shown(reason), canCheckDeeper(reason) && row.outpoint !in queued)
        }
        row.verdict == Verdict.INSCRIBED && row.found.isEmpty() -> UncheckedRow(row.outpoint, row.value, shown(row.reason ?: UNNAMED), false)
        row.verdict == Verdict.INSCRIBED -> null
        row.runes && row.outpoint !in represented -> UncheckedRow(row.outpoint, row.value, HELD, false)
        row.verdict == Verdict.CLEAN && !row.proven && !row.runes -> UncheckedRow(row.outpoint, row.value, WAITING, false)
        else -> null
    }

    private fun canCheckDeeper(reason: String) = reason == InscriptionTracer.BUDGET_USED || reason == InscriptionTracer.FEES_NEED_DEEPER

    private class RuneSums(val rows: List<RuneRow>, val represented: Set<String>, val skipped: Boolean)

    /**
     * Balances summed per rune name over every row that carries a rune list (the marks of rows not listed are not in
     * [rows]). Amounts are decimal integer text up to u128, so they are added as BigInteger and shifted by divisibility
     * as text. When one name arrives with different divisibilities, the first seen (rows in outpoint order, then name
     * order) sets the divisibility and the symbol, and an entry with another divisibility is left out of the sum rather
     * than mixed in. An unreadable amount (not digits, or over 40 of them) or a divisibility outside 0..38 is left out
     * too. [RuneSums.skipped] says an entry was left out; [RuneSums.represented] the coins with an entry in a balance.
     */
    private fun sumRunes(rows: List<InscriptionCheck>): RuneSums {
        class Sum(val divisibility: Int, val symbol: String?, var total: BigInteger)
        val sums = LinkedHashMap<String, Sum>()
        val represented = HashSet<String>()
        var skipped = false
        for(row in rows) {
            val list = row.runeList ?: continue
            for(name in list.keys.sorted()) {
                val balance = list.getValue(name)
                if(balance.divisibility !in 0..MAX_DIVISIBILITY || balance.amount.isEmpty() || balance.amount.length > MAX_AMOUNT_DIGITS ||
                    !balance.amount.all { it in '0'..'9' }) {
                    skipped = true
                    continue
                }
                val amount = BigInteger(balance.amount)
                val sum = sums[name]
                if(sum == null) {
                    sums[name] = Sum(balance.divisibility, balance.symbol, amount)
                } else if(sum.divisibility == balance.divisibility) {
                    sum.total += amount
                } else {
                    skipped = true
                    continue
                }
                represented.add(row.outpoint)
            }
        }
        val out = sums.entries.sortedBy { it.key }.map { RuneRow(it.key, it.value.symbol, shift(it.value.total, it.value.divisibility)) }
        return RuneSums(out, represented, skipped)
    }

    private fun shift(total: BigInteger, divisibility: Int): String =
        BigDecimal(total, divisibility).toPlainString()
}

/**
 * A thread-safe least-recently-used map of loaded inscription content: at most [capacity] entries and [maxBytes] of
 * bodies in all, the least recently used out first. A body larger than [maxBytes] is not cached.
 */
class ContentCache(private val capacity: Int, private val maxBytes: Long = MAX_BYTES) {
    private val map = LinkedHashMap<String, InscriptionContent>(16, 0.75f, true)
    private var total = 0L
    internal val inflight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, InscriptionContent?>>>()

    fun get(key: String): InscriptionContent? = synchronized(map) { map[key] }

    fun put(key: String, content: InscriptionContent) {
        synchronized(map) {
            if(content.body.size > maxBytes) return
            map.put(key, content)?.let { total -= it.body.size }
            total += content.body.size
            val eldest = map.entries.iterator()
            while((map.size > capacity || total > maxBytes) && eldest.hasNext()) {
                val entry = eldest.next()
                if(entry.key == key) continue
                total -= entry.value.body.size
                eldest.remove()
            }
        }
    }

    fun size(): Int = synchronized(map) { map.size }

    /** The bodies' bytes held. */
    fun bytes(): Long = synchronized(map) { total }

    companion object {
        /** The most body bytes held in all: 8 MiB. */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}

/** The rules around reading an inscription's content for a preview: kept apart from the repository so they are tested. */
object ContentLoading {
    const val CACHE_ENTRIES = 64

    /** The cache key: content is per coin and network as well as per id. */
    fun key(coin: Coin, network: Network, inscriptionId: String): String = "${coin.id}:${network.id}:$inscriptionId"

    /**
     * Content from [cache] or from [load], which is cached only when it gives content (a failure or a missing body is
     * never kept). Callers asking for one key at once share one load (a failure is shared by those callers only; a
     * cancelled load is retried by the next waiting caller). Null on any failure and, with no network call and no cache
     * read, while [airGapped]. Cancellation is rethrown.
     */
    suspend fun load(cache: ContentCache, key: String, airGapped: Boolean, load: suspend () -> InscriptionContent?): InscriptionContent? {
        if(airGapped) return null
        while(true) {
            cache.get(key)?.let { return it }
            val mine = CompletableDeferred<Pair<Boolean, InscriptionContent?>>()
            val other = cache.inflight.putIfAbsent(key, mine)
            if(other != null) {
                //Another caller is loading this one: share its answer, or load it here if that caller was cancelled
                val (finished, content) = other.await()
                if(finished) return content
                continue
            }
            try {
                val content = load()
                if(content != null) cache.put(key, content)
                mine.complete(true to content)
                return content
            } catch(e: CancellationException) {
                mine.complete(false to null)
                throw e
            } catch(e: Exception) {
                mine.complete(true to null)
                return null
            } finally {
                cache.inflight.remove(key, mine)
            }
        }
    }
}
