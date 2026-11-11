package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.ChainRules
import com.sparrowwallet.mobile.crypto.track.Found
import com.sparrowwallet.mobile.crypto.track.InscriptionContent
import com.sparrowwallet.mobile.crypto.track.InscriptionTracer
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    val key: String = "$outpoint/$inscriptionId/0",
    /** The coin's address position in the wallet (receive 0, change 1, and index), when the check stored it. */
    val chain: Int? = null, val index: Int? = null
) {
    /** An unnamed inscription (its id starts with "?") has no content to read, so no preview. */
    val hasPreview: Boolean get() = !inscriptionId.startsWith("?")
}

/** A rune's summed balance; [amount] is already shifted by its divisibility. Display text only. */
data class RuneRow(val name: String, val symbol: String?, val amount: String)

/** [queued]: a Check deeper was asked for this coin and is waiting for, or in, its run. */
data class UncheckedRow(val outpoint: String, val value: Long?, val reason: String, val canCheckDeeper: Boolean, val queued: Boolean = false)

/** What an unchecked row offers: nothing, "Check deeper", "Queued" (disabled), or "Check deeper" disabled while air-gapped. */
enum class DeeperButton { NONE, CHECK, QUEUED, OFF }

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
    const val CHECK_DEEPER = "Check deeper"
    const val QUEUED = "Queued"
    const val EMPTY = "No inscriptions in this wallet."
    /** The header while air-gapped: no checks run, the saved results show (spec, Errors). */
    const val CHECKS_OFF = "Checks are off while air-gapped"

    /** Whether Home offers Collectibles: public wallets on the coins the check covers, never an MWEB wallet. */
    fun available(coin: Coin, scriptType: String): Boolean = scriptType != "MWEB" && ChainRules.covers(coin)

    /** The row's Check deeper button. A row that cannot be checked deeper (held, unnamed, waiting, a server error) has none. */
    fun deeperButton(row: UncheckedRow, airGapped: Boolean): DeeperButton = when {
        row.queued -> DeeperButton.QUEUED
        !row.canCheckDeeper -> DeeperButton.NONE
        airGapped -> DeeperButton.OFF
        else -> DeeperButton.CHECK
    }

    fun build(
        checks: Map<String, InscriptionCheck>?, status: CheckStatus?, setting: InscriptionSetting,
        coin: Coin, deeperQueued: Set<String>, airGapped: Boolean = false
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
            header = if(airGapped) CHECKS_OFF else header(checks != null, status, setting),
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
                row.source, "${row.outpoint}/${it.inscriptionId}/$at", row.chain, row.index
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
        //Send holds it by an indexer's mark, which a deeper trace cannot lift: it says so, with no Check deeper
        row.verdict == Verdict.UNCHECKED && row.runes -> UncheckedRow(row.outpoint, row.value, HELD, false)
        row.verdict == Verdict.UNCHECKED -> {
            val reason = row.reason ?: NO_REASON
            val asked = row.outpoint in queued
            UncheckedRow(row.outpoint, row.value, shown(reason), canCheckDeeper(reason) && !asked, asked)
        }
        row.verdict == Verdict.INSCRIBED && row.found.isEmpty() -> UncheckedRow(row.outpoint, row.value, shown(row.reason ?: UNNAMED), false)
        row.verdict == Verdict.INSCRIBED -> null
        row.runes && row.outpoint !in represented -> UncheckedRow(row.outpoint, row.value, HELD, false)
        row.verdict == Verdict.CLEAN && !row.proven && !row.runes -> UncheckedRow(row.outpoint, row.value, WAITING, false)
        else -> null
    }

    private fun canCheckDeeper(reason: String) = reason == InscriptionTracer.BUDGET_USED || reason == InscriptionTracer.FEES_NEED_DEEPER ||
        reason == InscriptionChecker.LOST_REASON

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
     * read, while [airGapped]. Cancellation is rethrown. With [permits], the load holds one while it reads (each load
     * opens its own server connection), and gives it back however it ends, cancelled included.
     */
    suspend fun load(cache: ContentCache, key: String, airGapped: Boolean, load: suspend () -> InscriptionContent?): InscriptionContent? =
        load(cache, key, airGapped, null, load)

    suspend fun load(cache: ContentCache, key: String, airGapped: Boolean, permits: Semaphore?,
                     load: suspend () -> InscriptionContent?): InscriptionContent? {
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
                val content = if(permits == null) load() else permits.withPermit { load() }
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

/**
 * What an inscription's preview area shows, decided from the tile, the mode and the loaded content: the composables only
 * draw it. Content is attacker-chosen bytes from the chain: an image only when [PreviewRules.kind] says IMAGE (and the
 * decoder's own bounds agree, see [decodedBoundsOk]), text only as plain text from [PreviewRules.text], anything else a
 * placeholder naming its type and size. Never a link, markup, or anything opened or run.
 */
object InscriptionPreview {
    const val UNAVAILABLE = "Preview unavailable"
    const val AIR_GAPPED = "Off in air-gapped mode"
    const val UNKNOWN_TYPE = "Unknown type"
    const val MAX_TYPE_CHARS = 60
    /** The decoded longest side aimed at: a grid thumbnail, and the detail screen. */
    const val THUMB_TARGET = 256
    const val DETAIL_TARGET = 1_024
    /** Lines of text a tile shows. */
    const val TILE_TEXT_LINES = 4

    sealed interface Shown {
        data object Loading : Shown
        data class Unavailable(val text: String) : Shown
        /** Bytes [PreviewRules] cleared for the platform decoder, with the size their header declares. */
        class Image(val body: ByteArray, val width: Int, val height: Int) : Shown
        data class Text(val text: String) : Shown
        /** [note]: "Preview unavailable" for content over the size limit, whose body was never read. */
        data class Placeholder(val type: String, val size: String, val note: String? = null) : Shown
    }

    /**
     * An unnamed inscription has no content; air-gapped nothing is loaded; a failed load or decode is unavailable; no
     * content yet is loading. Otherwise [PreviewRules.kind] decides.
     */
    fun shown(tile: CollectibleTile, airGapped: Boolean, failed: Boolean, content: InscriptionContent?): Shown = when {
        !tile.hasPreview -> Shown.Unavailable(UNAVAILABLE)
        airGapped -> Shown.Unavailable(AIR_GAPPED)
        failed -> Shown.Unavailable(UNAVAILABLE)
        content == null -> Shown.Loading
        !content.complete -> Shown.Placeholder(typeText(content.contentType), sizeText(content), UNAVAILABLE)
        else -> when(PreviewRules.kind(content.contentType, content.contentEncoding, content.body)) {
            PreviewRules.Kind.IMAGE -> PreviewRules.dimensions(content.body)?.let { (w, h) -> Shown.Image(content.body, w, h) }
                ?: Shown.Unavailable(UNAVAILABLE)
            PreviewRules.Kind.TEXT -> Shown.Text(PreviewRules.text(content.body))
            PreviewRules.Kind.PLACEHOLDER -> Shown.Placeholder(typeText(content.contentType), sizeText(content.body.size))
        }
    }

    /** Whether a tile's content is read: never for an unnamed inscription, air-gapped, or after a failure this visit. */
    fun shouldLoad(tile: CollectibleTile, airGapped: Boolean, failures: PreviewFailures): Boolean =
        tile.hasPreview && !airGapped && tile.key !in failures

    /** The declared content type made safe to show (see [CollectiblesModel.shown]) and cut to [MAX_TYPE_CHARS]. */
    fun typeText(contentType: String?): String =
        CollectiblesModel.shown(contentType ?: "").take(MAX_TYPE_CHARS).trim().ifEmpty { UNKNOWN_TYPE }

    fun sizeText(bytes: Int): String = "${CollectiblesModel.group(bytes.toLong())} bytes"

    /** The content's size: counted, or only a bound when the reader stopped before the end. */
    fun sizeText(content: InscriptionContent): String =
        (if(content.sizeOver) "Over " else "") + "${CollectiblesModel.group(content.size)} bytes"

    /** The content line of the detail screen: type and size. */
    fun contentLine(content: InscriptionContent): String = "${typeText(content.contentType)}, ${sizeText(content)}"

    /**
     * The decoder's own bounds ([outWidth] x [outHeight], from a bounds-only pass) checked again: positive, never larger
     * than the header parse said, and within [PreviewRules]' limits. Anything else is not decoded.
     */
    fun decodedBoundsOk(headerWidth: Int, headerHeight: Int, outWidth: Int, outHeight: Int): Boolean =
        outWidth > 0 && outHeight > 0 && outWidth <= headerWidth && outHeight <= headerHeight &&
            outWidth <= PreviewRules.MAX_SIDE && outHeight <= PreviewRules.MAX_SIDE &&
            outWidth.toLong() * outHeight.toLong() <= PreviewRules.MAX_PIXELS
}

/** Tiles whose content failed to load or decode during one visit of the screen: not asked again until the next visit. */
class PreviewFailures {
    private val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun add(key: String) { keys.add(key) }
    operator fun contains(key: String): Boolean = key in keys
}

/** The detail screen's facts about one inscription, as label and value; [copy] marks the two that get a Copy button. */
data class DetailLine(val label: String, val value: String, val copy: Boolean = false)

object InscriptionDetail {
    /**
     * The lines for [tile]: id, number (when an indexer gave one), mint block and date, [content] (type and size, once
     * loaded), the coin and its value, [address] (when the position is known), and the source. Unknown parts are left out.
     */
    fun lines(tile: CollectibleTile, coin: Coin, content: String?, address: String?): List<DetailLine> = buildList {
        add(DetailLine("Inscription id", tile.inscriptionId, copy = true))
        if(tile.label.startsWith("#")) add(DetailLine("Number", tile.label))
        add(DetailLine("Minted", if(tile.mintHeight <= 0) "Unconfirmed" else
            "Block ${CollectiblesModel.group(tile.mintHeight.toLong())}" + (if(tile.mintTime > 0) ", ${formatDate(tile.mintTime)}" else "")))
        content?.let { add(DetailLine("Content", it)) }
        add(DetailLine("Coin", tile.outpoint, copy = true))
        tile.value?.let { add(DetailLine("Value", formatAmount(coin, it))) }
        address?.let { add(DetailLine("Address", it)) }
        add(DetailLine("Source", source(tile.source)))
    }

    fun source(source: InscriptionCheck.Source): String = when(source) {
        InscriptionCheck.Source.PUBLIC -> "Public indexer"
        InscriptionCheck.Source.YOUR_OWN -> "Your own indexer"
        InscriptionCheck.Source.BUILT_IN -> "Built-in"
        InscriptionCheck.Source.OWN -> "Built-in"
    }

    /** The coin's address position to derive its address from: receive (0) or change (1) and an unhardened index, else null. */
    fun position(tile: CollectibleTile): Pair<Int, Int>? {
        val chain = tile.chain ?: return null
        val index = tile.index ?: return null
        return if(chain in 0..1 && index >= 0) chain to index else null
    }

    /** "This coin carries N inscriptions." when the coin holds more than one, else null. */
    fun carries(tile: CollectibleTile): String? = if(tile.onCoin > 1) "This coin carries ${tile.onCoin} inscriptions." else null
}

/**
 * Runs preview decodes one at a time, off the main thread: a decode can take tens of megabytes whatever its sample size
 * (a progressive JPEG decodes at full size), so a fling over many tiles must not start one per core. A caller cancelled
 * while it waits for its turn (its tile left the screen) never decodes.
 */
class OneAtATime(private val context: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default) {
    private val turn = Semaphore(1)

    suspend fun <T> run(block: () -> T): T = turn.withPermit {
        currentCoroutineContext().ensureActive()
        withContext(context) { block() }
    }
}
