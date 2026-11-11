package com.sparrowwallet.mobile

import android.content.SharedPreferences
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.electrum.SocksProxy
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.WalletScanner
import com.sparrowwallet.mobile.electrum.indexer.Indexers
import com.sparrowwallet.mobile.electrum.indexer.InscriptionIndexer
import com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import com.sparrowwallet.mobile.electrum.inscriptions.TrackerRun
import kotlinx.coroutines.CancellationException
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
internal class InscriptionPrefs(private val prefs: SharedPreferences) {
    private fun stored(coin: Coin, network: Network): String? = prefs.getString(key(coin, network), null)

    private fun hasPublic(coin: Coin, network: Network): Boolean = Indexers.publicUrl(coin, network) != null

    fun setting(coin: Coin, network: Network): InscriptionSetting =
        InscriptionSetting.parse(stored(coin, network), hasPublic(coin, network), defaultChoice())

    fun save(coin: Coin, network: Network, setting: InscriptionSetting) {
        prefs.edit().putString(key(coin, network), setting.stored()).apply()
    }

    /** The notice's answer, or null until it is answered (an unknown value counts as unanswered). */
    fun defaultChoice(): String? =
        prefs.getString(DEFAULT_KEY, null)?.takeIf { it == InscriptionSetting.PUBLIC || it == InscriptionSetting.BUILT_IN }

    fun saveDefaultChoice(choice: String) {
        require(choice == InscriptionSetting.PUBLIC || choice == InscriptionSetting.BUILT_IN) { "Unknown choice $choice" }
        prefs.edit().putString(DEFAULT_KEY, choice).apply()
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
 * Whether the indexer at [url] counts as runes-aware for [coin] (see [coinState] rule 3 (b)): an ord indexer, and only
 * on Litecoin. ord's /output answers clean only with its rune index on, but it reads `runes` alone, never dunes or
 * junes, so off Litecoin a Runes index says nothing about the chain's tokens. Nintondo indexes inscriptions only.
 * Until a dunes-aware parse exists, no other coin has one.
 */
fun runesAwareIndexer(coin: Coin, url: String?): Boolean =
    url != null && !Indexers.isNintondo(url) && coin == Coin.LITECOIN

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
