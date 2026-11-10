package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.InscriptionIndex
import com.sparrowwallet.mobile.electrum.WalletScanner
import com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck

/**
 * Where a coin's inscription checks go, per coin and network like desktop: Off (the default; the local rule holds
 * coins that may carry one), the coin's public indexer, or the user's own. A public indexer sees the coins asked about.
 */
sealed interface InscriptionSetting {
    /** The indexer to ask, or null when checks are off or the coin has no public indexer on this network. */
    fun url(coin: Coin, network: Network): String?

    fun stored(): String

    data object Off : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String? = null
        override fun stored() = "off"
    }

    data object Public : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String? = InscriptionIndex.publicUrl(coin.id, network.id)
        override fun stored() = "public"
    }

    data class Custom(val customUrl: String) : InscriptionSetting {
        override fun url(coin: Coin, network: Network): String = customUrl.trimEnd('/')
        override fun stored() = "custom:$customUrl"
    }

    companion object {
        fun parse(stored: String?): InscriptionSetting = when {
            stored == "public" -> Public
            stored != null && stored.startsWith("custom:") -> stored.removePrefix("custom:").let { url ->
                if(url.startsWith("http://") || url.startsWith("https://")) Custom(url) else Off
            }
            else -> Off
        }
    }
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

/** What Send may do with a ready coin: spend it freely, hold it back, or use it last with a warning. */
enum class CoinState { CLEAN, HELD, UNCHECKED }

/**
 * A coin's [CoinState]; the first rule that matches decides.
 * 1. The indexer (today's setting, Public or Custom) says it carries, its tokens caveat included: HELD.
 * 2. Its check is Inscribed, or the indexer reported runes on it: HELD. A "may carry runes" check (a runestone or
 *    dunestone in its creating transaction, over a Clean trace) is HELD unless an indexer that indexes runes
 *    ([runesIndexed]) answered clean: then CLEAN when the check is proven against its block, else UNCHECKED.
 * 3. Its check is Clean, complete and proven against a mined block: CLEAN when (a) the chain has no rune-like protocol
 *    ([runeLike] false), (b) an indexer that indexes runes answered clean, or (c) [localMayCarry] does not flag it;
 *    otherwise HELD. The trace cannot see runes, which reach a coin without a runestone in its creating transaction.
 * 4. Any other check for it (Unchecked, cut short, or a provisional Clean): HELD when no indexer answered and
 *    [localMayCarry] flags it, else UNCHECKED.
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
            if(!runeLike || runesClean || !localMayCarry()) CoinState.CLEAN else CoinState.HELD
        check != null || hasAnyResults -> if(indexerSaysCarries == null && localMayCarry()) CoinState.HELD else CoinState.UNCHECKED
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

private fun coins(count: Int): String = "$count ${if(count == 1) "coin" else "coins"}"

/** The Send screen's line about held coins; [inscribed] when every held coin has an Inscribed check. */
fun heldCopy(count: Int, amount: String, inscribed: Boolean, included: Boolean): String =
    "${coins(count)} ($amount) " + when {
        included && inscribed -> "that carry inscriptions can be spent. Anything they carry could be lost."
        included -> "that may carry an inscription or tokens can be spent. Anything they carry could be lost."
        inscribed -> "held back: they carry inscriptions."
        else -> "held back: they may carry an inscription or tokens."
    }

/** The Send screen's line when a sweep (or a peg-in) leaves the Unchecked coins out. */
fun uncheckedLeftOutCopy(count: Int, amount: String): String =
    "${coins(count)} ($amount) not yet checked for inscriptions ${if(count == 1) "is" else "are"} left out."

/** The review's note for a payment that spends held coins the user included. */
fun heldWarning(count: Int): String = "This payment uses $count held ${if(count == 1) "coin" else "coins"}."

/** The review's warning for a payment that spends Unchecked coins. */
fun uncheckedWarning(count: Int): String =
    "This payment uses ${coins(count)} that ${if(count == 1) "has" else "have"} not been checked for inscriptions."
