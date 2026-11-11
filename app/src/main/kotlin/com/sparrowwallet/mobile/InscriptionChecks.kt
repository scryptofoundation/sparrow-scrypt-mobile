package com.sparrowwallet.mobile

import android.content.SharedPreferences
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.electrum.SocksProxy
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.WalletScanner
import com.sparrowwallet.mobile.electrum.indexer.IndexerOutput
import com.sparrowwallet.mobile.electrum.indexer.Indexers
import com.sparrowwallet.mobile.electrum.indexer.InscriptionIndexer
import com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import com.sparrowwallet.mobile.electrum.inscriptions.TrackerRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * Where a coin's inscription checks go, per coin and network like desktop: the coin's public indexer, the user's own,
 * or Built-in (on-chain only, no indexer). An indexer sees the coins it is asked about, so with nothing saved no
 * indexer is asked until the one-time notice ([InscriptionNotice]) is answered.
 */
sealed interface InscriptionSetting {
    /** The indexer to ask, or null on Built-in or when the coin has no public indexer on this network. */
    fun url(coin: Coin, network: Network): String?

    fun stored(): String

    data object Public : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String? = Indexers.publicUrl(coin, network)
        override fun stored() = PUBLIC
    }

    data class YourOwn(val customUrl: String) : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String = customUrl.trimEnd('/')
        override fun stored() = "$CUSTOM_PREFIX$customUrl"
    }

    data object BuiltIn : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String? = null
        override fun stored() = BUILT_IN
    }

    companion object {
        const val PUBLIC = "public"
        const val BUILT_IN = "builtin"
        /** What the old app's Save wrote whether or not the user chose it: counts as nothing saved. */
        private const val OLD_OFF = "off"
        private const val CUSTOM_PREFIX = "custom:"

        /** Whether [stored] counts as a saved choice: absent and the old "off" do not. */
        fun isSaved(stored: String?): Boolean = stored != null && stored != OLD_OFF

        /**
         * A saved value, for a coin and network that has a public indexer ([hasPublic]) or not. Anything unreadable
         * (a Your own URL that [checkYourOwnUrl] refuses included) reads as Built-in; a saved "public" where no public
         * indexer exists does too. With nothing saved (absent, or the old "off"), [defaultChoice] (the notice's answer,
         * null until answered) decides: "public" gives Public where one exists, anything else Built-in, so no indexer
         * is asked before the notice is answered.
         */
        fun parse(stored: String?, hasPublic: Boolean, defaultChoice: String?): InscriptionSetting = when {
            !isSaved(stored) -> if(defaultChoice == PUBLIC && hasPublic) Public else BuiltIn
            stored == PUBLIC -> if(hasPublic) Public else BuiltIn
            stored!!.startsWith(CUSTOM_PREFIX) -> yourOwnSetting(stored.removePrefix(CUSTOM_PREFIX)) ?: BuiltIn
            else -> BuiltIn
        }
    }
}

/**
 * The stored inscription settings: one per coin and network (`inscriptions.<coin>.<network>`), and the notice's answer
 * (`inscriptions.default`, "public" or "builtin", absent until answered) for coins with nothing saved.
 */
internal class InscriptionPrefs(
    private val prefs: SharedPreferences,
    /** Told after each save (the app clears the indexer readers, so the new choice takes effect at once). */
    private val changed: () -> Unit = {}
) {
    private fun stored(coin: Coin, network: Network): String? = prefs.getString(key(coin, network), null)

    private fun hasPublic(coin: Coin, network: Network): Boolean = Indexers.publicUrl(coin, network) != null

    fun setting(coin: Coin, network: Network): InscriptionSetting =
        InscriptionSetting.parse(stored(coin, network), hasPublic(coin, network), defaultChoice())

    fun save(coin: Coin, network: Network, setting: InscriptionSetting) {
        prefs.edit().putString(key(coin, network), setting.stored()).apply()
        changed()
    }

    /** The notice's answer, or null until it is answered (an unknown value counts as unanswered). */
    fun defaultChoice(): String? =
        prefs.getString(DEFAULT_KEY, null)?.takeIf { it == InscriptionSetting.PUBLIC || it == InscriptionSetting.BUILT_IN }

    fun saveDefaultChoice(choice: String) {
        require(choice == InscriptionSetting.PUBLIC || choice == InscriptionSetting.BUILT_IN) { "Unknown choice $choice" }
        prefs.edit().putString(DEFAULT_KEY, choice).apply()
        changed()
    }

    /**
     * Whether Home shows the one-time notice for this coin and network: not [airGapped] (the app-wide mode unless
     * given), nothing saved for it (a stored old "off" counts as nothing), a public indexer exists for it, and the
     * notice has not been answered (for any coin).
     */
    fun noticeDue(coin: Coin, network: Network, airGapped: Boolean = NetworkPolicy.blocked): Boolean =
        !airGapped && !InscriptionSetting.isSaved(stored(coin, network)) && hasPublic(coin, network) && defaultChoice() == null

    companion object {
        const val DEFAULT_KEY = "inscriptions.default"

        fun key(coin: Coin, network: Network) = "inscriptions.${coin.id}.${network.id}"
    }
}

/** The one-time notice on Home before any public indexer is asked; exact copy. */
object InscriptionNotice {
    const val TITLE = "Inscription checks"
    const val USE_PUBLIC = "Use the public indexer"
    const val USE_BUILT_IN = "Use Built-in instead"

    fun body(host: String): String =
        "Sparrow can ask a public indexer ($host) which of this wallet's coins carry inscriptions or runes. That tells " +
            "the indexer which coins are yours. It goes through your Tor proxy when one is set. You can check on-chain " +
            "only instead: that is slower and cannot see runes. Your answer applies to every coin. You can change it " +
            "for each coin under Server."

    /** The public indexer's host for the coin and network, or null when it has none. */
    fun host(coin: Coin, network: Network): String? = Indexers.publicUrl(coin, network)?.let(::indexerHost)

    /** The value saved as the default choice for each button. */
    fun choice(usePublic: Boolean): String = if(usePublic) InscriptionSetting.PUBLIC else InscriptionSetting.BUILT_IN
}

/** An indexer URL's host name, or the URL itself when it has none. */
fun indexerHost(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

/** The Server screen's choices, in this order; Public only where the coin has a public indexer. */
enum class InscriptionChoice {
    PUBLIC, YOUR_OWN, BUILT_IN;

    companion object {
        fun of(setting: InscriptionSetting): InscriptionChoice = when(setting) {
            InscriptionSetting.Public -> PUBLIC
            is InscriptionSetting.YourOwn -> YOUR_OWN
            InscriptionSetting.BuiltIn -> BUILT_IN
        }
    }
}

fun inscriptionChoices(publicUrl: String?): List<InscriptionChoice> =
    listOfNotNull(InscriptionChoice.PUBLIC.takeIf { publicUrl != null }, InscriptionChoice.YOUR_OWN, InscriptionChoice.BUILT_IN)

fun inscriptionChoiceLabel(choice: InscriptionChoice, publicUrl: String?): String = when(choice) {
    InscriptionChoice.PUBLIC -> "Public (${publicUrl?.let(::indexerHost) ?: ""})"
    InscriptionChoice.YOUR_OWN -> "Your own"
    InscriptionChoice.BUILT_IN -> "Built-in"
}

/** A typed or stored Your own URL: [Ok] with the URL to keep, or [Refused] with the reason. */
sealed interface YourOwnUrl {
    data class Ok(val url: String) : YourOwnUrl
    data class Refused(val reason: String) : YourOwnUrl
}

/**
 * Checks a Your own URL: surrounding whitespace trimmed, http or https in any case (kept lowercased), parsed by
 * [URI] with a host and no user name or password.
 */
fun checkYourOwnUrl(raw: String): YourOwnUrl {
    val url = raw.trim()
    val uri = try {
        URI(url)
    } catch(e: java.net.URISyntaxException) {
        return if(url.lowercase().startsWith("http://") || url.lowercase().startsWith("https://")) YourOwnUrl.Refused(NOT_A_URL)
        else YourOwnUrl.Refused(NOT_HTTP)
    }
    val scheme = uri.scheme?.lowercase()
    if(scheme != "http" && scheme != "https") return YourOwnUrl.Refused(NOT_HTTP)
    if(uri.rawUserInfo != null) return YourOwnUrl.Refused("The indexer URL cannot hold a user name or password")
    if(uri.host.isNullOrEmpty()) return YourOwnUrl.Refused(NOT_A_URL)
    return YourOwnUrl.Ok(scheme + url.substring(uri.scheme.length))
}

private const val NOT_HTTP = "The indexer URL must start with http:// or https://"
private const val NOT_A_URL = "Not a valid indexer URL"

/** The Your own setting for a typed URL, or null when [checkYourOwnUrl] refuses it. */
fun yourOwnSetting(url: String): InscriptionSetting.YourOwn? =
    (checkYourOwnUrl(url) as? YourOwnUrl.Ok)?.let { InscriptionSetting.YourOwn(it.url) }

/**
 * The Server screen's Save for the inscription choice. [shown] is the setting the screen opened with (the effective
 * one) and [shownProxy] the proxy saved then; [chosen] and [proxy] are what is being saved.
 * - Unchanged (and, for Your own, through the same proxy): nothing is contacted and nothing is written for it, so a
 *   coin with nothing saved keeps following the notice's answer. [save] gets null.
 * - Public or Built-in: [save] gets it.
 * - A new or changed Your own URL, or a changed proxy: [checkYourOwnIndexer] first; a refusal is returned and [save]
 *   is not called (the server fields are not saved either).
 * [save] saves the server fields, and the inscription setting when it is not null. Returns the refusal or null.
 */
suspend fun saveInscriptionChoice(shown: InscriptionSetting, shownProxy: SocksProxy?, chosen: InscriptionSetting,
                                  proxy: SocksProxy?, airGapped: Boolean, open: suspend (String) -> InscriptionIndexer,
                                  save: (InscriptionSetting?) -> Unit): String? {
    if(chosen == shown && (chosen !is InscriptionSetting.YourOwn || proxy == shownProxy)) {
        save(null)
        return null
    }
    if(chosen is InscriptionSetting.YourOwn) {
        checkYourOwnIndexer(chosen.customUrl.trimEnd('/'), airGapped, open)?.let { return it }
    }
    save(chosen)
    return null
}

/**
 * Save's check of a Your own indexer: opens it with [open] (in the app, [Indexers.open], which checks its chain and kind)
 * and closes the reader. Returns null when it opened, else the reason to show (the exception's message). Air-gapped, it
 * contacts nothing. Cancellation is rethrown.
 */
suspend fun checkYourOwnIndexer(url: String, airGapped: Boolean, open: suspend (String) -> InscriptionIndexer): String? {
    if(airGapped) return "Off in air-gapped mode"
    val reader = try {
        open(url)
    } catch(e: CancellationException) {
        throw e
    } catch(e: Throwable) {
        return e.message ?: "Could not reach the indexer"
    }
    reader.close()
    return null
}

/**
 * What the coin's indexer (today's setting, Public or Custom) says: carries (true), clean (false), or no answer (null:
 * checks off, not indexed, unreachable). An indexer that does not index tokens (Nintondo: inscriptions only) cannot
 * clear a coin whose transaction [writesTokens] (a runestone or dunestone), so that "clean" reads as carries.
 */
inline fun indexerCarries(serverAnswer: Boolean?, tokensIndexed: Boolean = true, writesTokens: () -> Boolean = { false }): Boolean? =
    when(serverAnswer) {
        true -> true
        false -> !tokensIndexed && writesTokens()
        null -> null
    }

/**
 * Whether an open [indexer] counts as runes-aware for [coin] (see [coinState] rule 3 (b)): the reader says its rune
 * index is on (an ord server; Nintondo never says so), and only on Litecoin. ord reads `runes` alone, never dunes or
 * junes, so off Litecoin a Runes index says nothing about the chain's tokens. Until a dunes-aware parse exists, no other
 * coin has one. Read it after the pass's outputs call succeeded: ord's reader re-reads its status on each call.
 */
fun runesAwareIndexer(coin: Coin, indexer: InscriptionIndexer?): Boolean =
    coin == Coin.LITECOIN && indexer != null && indexer.runesAware

/**
 * What Send makes of one indexer [output] for the coin at [outpoint]: true when it carries anything (inscriptions, runes
 * or something unlisted), null when there is no usable answer (none, an entry for another outpoint, or not indexed),
 * false only for an indexed answer that carries nothing. On a chain with rune-like tokens ([runeLike]) a clean that is
 * not [runesAware] is no answer either, so it can never stand in for the postage-value hold (an ord server on Dogecoin,
 * Pepecoin or Junkcoin cannot see dunes, whatever its rune index says).
 */
fun sendAnswer(output: IndexerOutput?, outpoint: String, runesAware: Boolean, runeLike: Boolean): Boolean? = when {
    output == null || output.outpoint != outpoint -> null
    output.carries -> true
    !output.indexed -> null
    runeLike && !runesAware -> null
    else -> false
}

/**
 * The indexer readers Send asks, one per coin, network, URL and proxy, each obtained only through [open] (in the app
 * [Indexers.open], whose chain check, block 0 included, is what makes its answers usable). A reader is opened at most
 * once per pass (under a lock of its own key, so a slow indexer never holds up another) and asked once per pass for all
 * the coins it has no kept answer for, within [passDeadlineMs] overall. Answers for confirmed coins are kept with their
 * reader (an unspent output's answer does not change), and only consulted once the reader is in hand. Any failure (the
 * open, or the reader's call: unreachable, 429, wrong chain, unreadable, closed, past the deadline) is no answer for
 * every coin of the pass, and drops and closes the reader with its kept answers. A failed open is remembered for
 * [FAILED_OPEN_MS], so a dead indexer is not tried again on every Send. Cancellation is rethrown.
 *
 * [clear] (a server or inscription setting changed) closes every reader, the ones lent to a check run included
 * ([lend]): a closed reader refuses every further call, so a pass or a run still going asks nothing more over the old
 * URL or proxy.
 */
internal class IndexerReaders(
    private val now: () -> Long = System::currentTimeMillis,
    private val passDeadlineMs: Long = SEND_PASS_DEADLINE_MS,
    private val open: suspend (String, Coin, Network, SocksProxy?) -> InscriptionIndexer =
        { url, coin, network, proxy -> Indexers.open(url, coin, network, proxy) }
) {
    /**
     * One pass's answers by outpoint (see [sendAnswer]), whether its clean answers are runes-aware, and whether a clean
     * one also rules out tokens written by the coin's creating transaction ([tokensIndexed]: any reader but Nintondo's,
     * as before part B; see [indexerCarries]).
     */
    class Answers(val byOutpoint: Map<String, Boolean?>, val runesAware: Boolean, val tokensIndexed: Boolean = true) {
        fun of(outpoint: String): Boolean? = byOutpoint[outpoint]
    }

    private data class Key(val coin: Coin, val network: Network, val url: String, val proxy: SocksProxy?)

    private val readers = java.util.concurrent.ConcurrentHashMap<Key, InscriptionIndexer>()
    private val kept = java.util.concurrent.ConcurrentHashMap<Key, java.util.concurrent.ConcurrentHashMap<String, IndexerOutput>>()
    private val locks = java.util.concurrent.ConcurrentHashMap<Key, kotlinx.coroutines.sync.Mutex>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<Key, Long>()
    private val lent = java.util.concurrent.ConcurrentHashMap.newKeySet<InscriptionIndexer>()
    /** Bumped by [clear], so a reader opened before a settings change and lent after it is closed at once. */
    @Volatile var generation = 0
        private set

    /**
     * The answers for [coins] under [setting]: none, with nothing contacted, on Built-in, with no indexer for the coin,
     * when [airGapped], or for no coins.
     */
    suspend fun answers(setting: InscriptionSetting, coin: Coin, network: Network, proxy: SocksProxy?,
                        coins: List<com.sparrowwallet.mobile.electrum.Utxo>, airGapped: Boolean = NetworkPolicy.blocked): Answers {
        val outpoints = coins.map { "${it.txHash}:${it.txPos}" }
        val url = setting.url(coin, network)
        val tokensIndexed = url == null || !Indexers.isNintondo(url)
        val none = Answers(outpoints.associateWith { null }, false, tokensIndexed)
        if(url == null || airGapped || coins.isEmpty()) return none
        val key = Key(coin, network, url, proxy)
        val reader = reader(key) ?: return none
        val keptHere = kept.computeIfAbsent(key) { java.util.concurrent.ConcurrentHashMap() }
        val ask = outpoints.distinct().filter { !keptHere.containsKey(it) }
        val outputs = HashMap<String, IndexerOutput>()
        if(ask.isNotEmpty()) {
            val answered = try {
                kotlinx.coroutines.withTimeoutOrNull(passDeadlineMs) { reader.outputs(ask) }
            } catch(e: CancellationException) {
                throw e
            } catch(e: Exception) {
                null
            }
            if(answered == null) {
                drop(key, reader)
                return none
            }
            outputs.putAll(answered)
        }
        val runesAware = runesAwareIndexer(coin, reader)
        val runeLike = com.sparrowwallet.mobile.crypto.track.ChainRules.hasRuneLikeTokens(coin)
        val confirmed = coins.filter { it.height > 0 }.map { "${it.txHash}:${it.txPos}" }.toSet()
        val byOutpoint = LinkedHashMap<String, Boolean?>()
        for(outpoint in outpoints) {
            val output = outputs[outpoint]?.takeIf { it.outpoint == outpoint } ?: keptHere[outpoint]
            if(outpoint in outputs && output != null && output.indexed && outpoint in confirmed) keptHere[outpoint] = output
            byOutpoint[outpoint] = sendAnswer(output, outpoint, runesAware, runeLike)
        }
        return Answers(byOutpoint, runesAware, tokensIndexed)
    }

    /**
     * The open reader for [key], opening it once if needed; null (and nothing kept for it) when it cannot be opened, or
     * when it failed to open less than [FAILED_OPEN_MS] ago.
     */
    private suspend fun reader(key: Key): InscriptionIndexer? = locks.computeIfAbsent(key) { kotlinx.coroutines.sync.Mutex() }.withLock {
        readers[key]?.let { return@withLock it }
        failedAt[key]?.let { if(now() - it < FAILED_OPEN_MS) return@withLock null }
        kept.remove(key)
        val gen = generation
        try {
            val reader = open(key.url, key.coin, key.network, key.proxy)
            failedAt.remove(key)
            if(gen != generation) {
                //the settings changed while it opened
                reader.close()
                return@withLock null
            }
            readers[key] = reader
            reader
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            failedAt[key] = now()
            null
        }
    }

    private fun drop(key: Key, reader: InscriptionIndexer) {
        if(readers.remove(key, reader)) {
            kept.remove(key)
        }
        runCatching { reader.close() }
    }

    /**
     * Registers a check run's own reader, opened when [generation] was [openedAt], so [clear] closes it too. Returns false
     * (and closes it) when the settings changed since it was opened.
     */
    fun lend(reader: InscriptionIndexer, openedAt: Int): Boolean {
        lent.add(reader)
        if(openedAt != generation) {
            lent.remove(reader)
            runCatching { reader.close() }
            return false
        }
        return true
    }

    fun giveBack(reader: InscriptionIndexer) {
        lent.remove(reader)
    }

    /** Closes every reader (the lent ones too) and forgets every answer and failure: a server or inscription setting changed. */
    fun clear() {
        generation++
        val open = ArrayList(readers.values) + ArrayList(lent)
        readers.clear()
        kept.clear()
        failedAt.clear()
        open.forEach { runCatching { it.close() } }
    }

    companion object {
        /** How long a failed open is remembered before the indexer is tried again. */
        const val FAILED_OPEN_MS = 60_000L

        /** The most one Send pass waits for its indexer answers in all (Nintondo is asked one coin at a time). */
        const val SEND_PASS_DEADLINE_MS = 30_000L
    }
}

/** The coin's server proxy, which indexer requests take too; null when none is set. */
fun indexerProxy(server: WalletRepository.ServerSettings?): SocksProxy? =
    server?.takeIf { it.useProxy }?.let { SocksProxy(it.proxyHost, it.proxyPort) }

/**
 * Send's decision for each of the [ready] coins (outpoint to coin): the effective [setting]'s indexer asked once through
 * [readers] (nothing on Built-in or [airGapped]), then [classifyCoins] with the wallet's stored [results] (ignored for a
 * coin the check does not cover). [writesTokens] reads whether the coin's creating transaction writes a runestone or
 * dunestone; [localMayCarry] is the local guess, asked only when nothing else decides.
 */
internal suspend fun classifySend(ready: Map<String, com.sparrowwallet.mobile.electrum.Utxo>, results: Map<String, InscriptionCheck>?,
                                  coin: Coin, network: Network, setting: InscriptionSetting, proxy: SocksProxy?,
                                  readers: IndexerReaders, airGapped: Boolean,
                                  writesTokens: suspend (com.sparrowwallet.mobile.electrum.Utxo) -> Boolean,
                                  localMayCarry: suspend (com.sparrowwallet.mobile.electrum.Utxo) -> Boolean): CoinStates {
    val covered = com.sparrowwallet.mobile.crypto.track.ChainRules.covers(coin)
    val answers = readers.answers(setting, coin, network, proxy, ready.values.toList(), airGapped)
    return classifyCoins(ready.keys.toList(), if(covered) results else null, covered,
        com.sparrowwallet.mobile.crypto.track.ChainRules.hasRuneLikeTokens(coin), answers.runesAware,
        { op -> indexerCarries(answers.of(op), answers.tokensIndexed) { writesTokens(ready.getValue(op)) } }
    ) { op -> localMayCarry(ready.getValue(op)) }
}

/**
 * The reader a check run uses, and the source its answers count as. [reader] null with a [source] means the setting
 * names an indexer that could not be opened: the run falls back to Built-in and says the indexer is unreachable.
 */
class CheckIndexer(val reader: InscriptionIndexer?, val source: InscriptionCheck.Source?)

/**
 * Opens the check run's reader for [setting] with [open] (in the app [Indexers.open]): none on Built-in, with no indexer
 * for the coin, or when [airGapped], contacting nothing. A failed open (unreachable, wrong chain, an unsupported kind,
 * plain http through the proxy, a malformed URL) is no reader, not an error; so is an open still going when [timeUp]
 * says the run's time is up (a reader that arrives after that is closed). Cancellation is rethrown.
 */
suspend fun openCheckIndexer(setting: InscriptionSetting, coin: Coin, network: Network, proxy: SocksProxy?, airGapped: Boolean,
                             timeUp: () -> Boolean = { false },
                             open: suspend (String, Coin, Network, SocksProxy?) -> InscriptionIndexer): CheckIndexer {
    val url = setting.url(coin, network) ?: return CheckIndexer(null, null)
    if(airGapped) return CheckIndexer(null, null)
    val source = if(setting is InscriptionSetting.YourOwn) InscriptionCheck.Source.YOUR_OWN else InscriptionCheck.Source.PUBLIC
    return try {
        CheckIndexer(stopWhenTimeUp(timeUp, late = { it.close() }) { open(url, coin, network, proxy) }, source)
    } catch(e: CancellationException) {
        throw e
    } catch(e: Exception) {
        CheckIndexer(null, source)
    }
}

/**
 * Runs [block] until it ends or [timeUp] says the time is up (asked before it starts and about four times a second), then
 * gives up on it: null, and the block cancelled, or with [late] left to finish and its result handed to [late]. The block runs apart from
 * the caller, so giving up does not wait for a request it is blocked in. Its failure, cancellation included, is rethrown.
 */
internal suspend fun <T> stopWhenTimeUp(timeUp: () -> Boolean, late: ((T) -> Unit)? = null, block: suspend () -> T): T? {
    if(timeUp()) return null
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.currentCoroutineContext().minusKey(kotlinx.coroutines.Job) + kotlinx.coroutines.SupervisorJob())
    val work = scope.async { block() }
    var handedOver = false
    try {
        while(!work.isCompleted) {
            if(timeUp()) {
                if(late != null) {
                    //left to finish, so what it opens is closed rather than lost
                    handedOver = true
                    work.invokeOnCompletion { cause -> if(cause == null) scope.launch { runCatching { late(work.await()) } } }
                }
                return null
            }
            kotlinx.coroutines.withTimeoutOrNull(TIME_UP_POLL_MS) { work.join() }
        }
        return work.await()
    } finally {
        if(!handedOver && !work.isCompleted) work.cancel()
    }
}

private const val TIME_UP_POLL_MS = 250L

/** What Send may do with a ready coin: spend it freely, hold it back, or use it last with a warning. */
enum class CoinState { CLEAN, HELD, UNCHECKED }

/**
 * A coin's [CoinState]; the first rule that matches decides.
 * 1. The indexer (today's setting, Public or Custom) says it carries, its tokens caveat included: HELD.
 * 2. Its check is Inscribed, or the indexer reported runes on it: HELD. A "may carry runes" check (a runestone or
 *    dunestone in its creating transaction, over a Clean trace) is HELD unless an indexer that indexes runes
 *    ([runesIndexed]) answered clean: then CLEAN when the check is proven against its block, else UNCHECKED.
 * 3. Its check is Clean, complete and proven against a mined block: CLEAN when the coin came straight out of MWEB (its
 *    check's [InscriptionCheck.mwebPegOut]: its creating transaction is a confirmed HogEx, so it has no history to carry
 *    an inscription or runes, whatever its value), (a) the chain has no rune-like protocol ([runeLike] false), (b) an
 *    indexer that indexes runes answered clean, or (c) [localMayCarry] does not flag it; otherwise HELD. The trace
 *    cannot see runes, which reach a coin without a runestone in its creating transaction.
 * 4. Any other check for it (Unchecked, cut short, or a provisional Clean): HELD when [localMayCarry] flags it and either
 *    no indexer answered or, on a rune-like chain, the indexer's clean answer is not runes-aware; else UNCHECKED.
 * 5. No check for it but the wallet has results (a coin newer than the last run): the same as rule 4.
 * 6. The wallet has no results at all (never checked, or a coin the check does not cover): today's rule, the
 *    indexer's clean answer or else [localMayCarry].
 * So a coin the local rule flags stays held until a proven check or the indexer clears it. [localMayCarry] is asked
 * only when nothing else decides. The defaults are the safe side: a rune-like chain and no runes-aware indexer.
 */
inline fun coinState(check: InscriptionCheck?, indexerSaysCarries: Boolean?, localMayCarry: () -> Boolean,
                     hasAnyResults: Boolean, runeLike: Boolean = true, runesIndexed: Boolean = false): CoinState {
    val runesClean = runesIndexed && indexerSaysCarries == false
    return when {
        indexerSaysCarries == true -> CoinState.HELD
        check != null && (check.verdict == Verdict.INSCRIBED || check.runes) -> CoinState.HELD
        //CheckPolicy writes this reason only over a Clean trace, so with the runes question answered it is that trace's verdict
        check != null && check.reason == CheckPolicy.MAY_CARRY_RUNES ->
            if(!runesClean) CoinState.HELD else if(check.proven && check.found.isEmpty()) CoinState.CLEAN else CoinState.UNCHECKED
        check != null && check.verdict == Verdict.CLEAN && CheckPolicy.complete(check) && check.proven ->
            if(check.mwebPegOut == true || !runeLike || runesClean || !localMayCarry()) CoinState.CLEAN else CoinState.HELD
        check != null || hasAnyResults ->
            if((indexerSaysCarries == null || (runeLike && !runesClean)) && localMayCarry()) CoinState.HELD else CoinState.UNCHECKED
        indexerSaysCarries == false -> CoinState.CLEAN
        localMayCarry() -> CoinState.HELD
        else -> CoinState.CLEAN
    }
}

fun coinState(check: InscriptionCheck?, indexerSaysCarries: Boolean?, localMayCarry: Boolean, hasAnyResults: Boolean,
              runeLike: Boolean = true, runesIndexed: Boolean = false): CoinState =
    coinState(check, indexerSaysCarries, { localMayCarry }, hasAnyResults, runeLike, runesIndexed)

/** What Send may do with each ready coin, by outpoint, from [classifyCoins]. */
internal class CoinStates(private val states: Map<String, CoinState>, private val inscribed: Set<String>) {
    /** A coin that was not classified (not ready to spend) reads as held, never as clean. */
    fun state(outpoint: String): CoinState = states[outpoint] ?: CoinState.HELD

    fun state(u: WalletScanner.ChainUtxo): CoinState = state(outpoint(u))

    /** Whether the coin's check found inscriptions on it. */
    fun inscribed(outpoint: String): Boolean = outpoint in inscribed

    fun inscribed(u: WalletScanner.ChainUtxo): Boolean = inscribed(outpoint(u))
}

/**
 * Decides each of the ready [coins] (outpoints) by [coinState]. [results] are the wallet's stored checks, or null when it
 * has no readable results file (never checked): an empty map still counts as checked. A coin the check does not
 * [covered] (Luckycoin, Dingocoin) ignores any results and keeps today's rule. [runeLike] is
 * [com.sparrowwallet.mobile.crypto.track.ChainRules.hasRuneLikeTokens] of the coin, [runesIndexed] whether the indexer
 * (if it answers) indexes runes. [indexer] is the old setting's answer (see [indexerCarries]); [localMayCarry] the local
 * guess, asked only when nothing else decides.
 */
internal suspend fun classifyCoins(coins: List<String>, results: Map<String, InscriptionCheck>?, covered: Boolean,
                                   runeLike: Boolean, runesIndexed: Boolean,
                                   indexer: suspend (String) -> Boolean?, localMayCarry: suspend (String) -> Boolean): CoinStates {
    val checks = if(covered) results?.values?.associateBy { it.outpoint } else null
    val states = HashMap<String, CoinState>()
    for(coin in coins) {
        states[coin] = coinState(checks?.get(coin), indexer(coin), { localMayCarry(coin) }, checks != null, runeLike, runesIndexed)
    }
    return CoinStates(states, checks.orEmpty().values.filter { it.verdict == Verdict.INSCRIBED }.map { it.outpoint }.toSet())
}

/**
 * Whether a stored row is worth a new trace: it has no block (so it cannot stand), or it is Unchecked and not a dead
 * end (budget, server error, time, unknown height). Clean, Inscribed and dead-end rows with their block stand.
 */
fun retryable(check: InscriptionCheck): Boolean =
    check.blockHash == null || (check.verdict == Verdict.UNCHECKED && !TrackerRun.deadEnd(check))

/**
 * Whether the background worker runs a wallet's inscription check: [due] (its scan saw something new, see
 * [SyncBaseline.inscriptionCheckDue]), no readable results file yet ([stored] null), or a [retryable] row. Otherwise
 * it skips the check entirely: no second address walk, no block-hash calls.
 */
fun inscriptionCheckNeeded(due: Boolean, stored: Map<String, InscriptionCheck>?): Boolean =
    due || stored == null || stored.values.any { retryable(it) }

private fun coins(count: Int): String = "$count ${if(count == 1) "coin" else "coins"}"

/**
 * The Send screen's line about held coins; [inscribed] when every held coin has an Inscribed check. For a move into MWEB
 * ([pegIn]) there is no box: held coins are never moved there, whatever [included] says.
 */
fun heldCopy(count: Int, amount: String, inscribed: Boolean, included: Boolean, pegIn: Boolean = false): String =
    "${coins(count)} ($amount) " + when {
        pegIn && inscribed -> "held back: they carry inscriptions. Held coins are not moved into MWEB."
        pegIn -> "held back: they may carry an inscription or tokens. Held coins are not moved into MWEB."
        included && inscribed -> "that carry inscriptions can be spent. Anything they carry could be lost."
        included -> "that may carry an inscription or tokens can be spent. Anything they carry could be lost."
        inscribed -> "held back: they carry inscriptions."
        else -> "held back: they may carry an inscription or tokens."
    }

/** The Send screen's line when a sweep (a Send max, peg-ins included) leaves the Unchecked coins out. */
fun uncheckedLeftOutCopy(count: Int, amount: String): String =
    "${coins(count)} ($amount) not yet checked for inscriptions ${if(count == 1) "is" else "are"} left out."

/** The review's note for a payment that spends held coins the user included. */
fun heldWarning(count: Int): String = "This payment uses $count held ${if(count == 1) "coin" else "coins"}."

/** The review's warning for a payment that spends Unchecked coins. */
fun uncheckedWarning(count: Int): String =
    "This payment uses ${coins(count)} that ${if(count == 1) "has" else "have"} not been checked for inscriptions."
