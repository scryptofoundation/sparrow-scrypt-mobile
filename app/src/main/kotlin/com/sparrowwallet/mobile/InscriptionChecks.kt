package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.InscriptionIndex
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
 * Whether a coin is held: the indexer's answer when it has one, otherwise the local rule. An indexer that does not
 * index tokens (Nintondo: inscriptions only) cannot clear a coin whose transaction [writesTokens] (a runestone or
 * dunestone).
 */
inline fun heldFrom(serverAnswer: Boolean?, tokensIndexed: Boolean = true, writesTokens: () -> Boolean = { false },
                    localMayCarry: () -> Boolean): Boolean =
    indexerCarries(serverAnswer, tokensIndexed, writesTokens) ?: localMayCarry()

/**
 * The indexer's part of [heldFrom]: carries (true, also for a coin it says is clean but cannot vouch for because it does
 * not index the tokens its transaction [writesTokens]), clean (false), or no answer (null).
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
 * 2. Its check is Inscribed, or it may carry runes: HELD.
 * 3. Its check is Clean, complete and proven against a mined block: CLEAN, whatever the local rule says.
 * 4. Any other check for it (Unchecked, cut short, or a provisional Clean): UNCHECKED.
 * 5. No check for it but the wallet has results (a coin newer than the last run): UNCHECKED.
 * 6. The wallet has no results at all (never checked, or a coin the check does not cover): today's rule, the
 *    indexer's clean answer or else [localMayCarry], asked only here.
 */
inline fun coinState(check: InscriptionCheck?, indexerSaysCarries: Boolean?, localMayCarry: () -> Boolean,
                     hasAnyResults: Boolean): CoinState = when {
    indexerSaysCarries == true -> CoinState.HELD
    check != null && (check.verdict == Verdict.INSCRIBED || check.runes || check.reason == CheckPolicy.MAY_CARRY_RUNES) -> CoinState.HELD
    check != null && check.verdict == Verdict.CLEAN && CheckPolicy.complete(check) && check.proven -> CoinState.CLEAN
    check != null -> CoinState.UNCHECKED
    hasAnyResults -> CoinState.UNCHECKED
    indexerSaysCarries == false -> CoinState.CLEAN
    localMayCarry() -> CoinState.HELD
    else -> CoinState.CLEAN
}

fun coinState(check: InscriptionCheck?, indexerSaysCarries: Boolean?, localMayCarry: Boolean, hasAnyResults: Boolean): CoinState =
    coinState(check, indexerSaysCarries, { localMayCarry }, hasAnyResults)

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

/** The review's warning for a payment that spends Unchecked coins. */
fun uncheckedWarning(count: Int): String =
    "This payment uses ${coins(count)} that ${if(count == 1) "has" else "have"} not been checked for inscriptions."
