package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.ChainRules
import com.sparrowwallet.mobile.crypto.track.Piece
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InscriptionChecksTest {
    @Test
    fun settingRoundTrips() {
        assertEquals(InscriptionSetting.Off, InscriptionSetting.parse(null))
        assertEquals(InscriptionSetting.Off, InscriptionSetting.parse("off"))
        assertEquals(InscriptionSetting.Public, InscriptionSetting.parse(InscriptionSetting.Public.stored()))
        val custom = InscriptionSetting.Custom("http://192.168.1.5:8085")
        assertEquals(custom, InscriptionSetting.parse(custom.stored()))
        assertEquals(InscriptionSetting.Off, InscriptionSetting.parse("custom:ftp://x"), "a stored URL that is not http(s) leaves checks off")
    }

    @Test
    fun urlPerCoin() {
        assertEquals("https://ordinalslite.com", InscriptionSetting.Public.url(Coin.LITECOIN, Network.MAINNET))
        assertEquals("https://pepe-mainnet-api.nintondo.io", InscriptionSetting.Public.url(Coin.PEPECOIN, Network.MAINNET))
        assertNull(InscriptionSetting.Public.url(Coin.JUNKCOIN, Network.MAINNET))
        assertNull(InscriptionSetting.Off.url(Coin.LITECOIN, Network.MAINNET))
        assertEquals("http://10.0.0.2:8085", InscriptionSetting.Custom("http://10.0.0.2:8085/").url(Coin.JUNKCOIN, Network.MAINNET))
    }

    /** In a wallet never checked, the indexer's answer decides; without one (off, unknown or unreachable) the local rule holds the coin. */
    @Test
    fun indexerAnswerDecides() {
        fun state(answer: Boolean?, local: Boolean) = coinState(null, indexerCarries(answer), local, hasAnyResults = false)
        assertEquals(CoinState.HELD, state(true, local = false))
        assertEquals(CoinState.CLEAN, state(false, local = true))
        assertEquals(CoinState.HELD, state(null, local = true))
        assertEquals(CoinState.CLEAN, state(null, local = false))
    }

    /** Nintondo indexes inscriptions only: its "clean" cannot clear a coin made by a runestone or dunestone transaction. */
    @Test
    fun inscriptionOnlyIndexerCannotClearTokenCoins() {
        fun state(tokensIndexed: Boolean, writesTokens: Boolean, local: Boolean) =
            coinState(null, indexerCarries(false, tokensIndexed) { writesTokens }, local, hasAnyResults = false)
        assertEquals(CoinState.HELD, state(tokensIndexed = false, writesTokens = true, local = false))
        assertEquals(CoinState.CLEAN, state(tokensIndexed = false, writesTokens = false, local = true))
        assertEquals(CoinState.CLEAN, state(tokensIndexed = true, writesTokens = true, local = true))
    }

    private fun check(verdict: Verdict, runes: Boolean = false, reason: String? = null, blockHash: String? = "ab".repeat(32),
                      open: List<Piece> = emptyList()) =
        InscriptionCheck("11".repeat(32), 0, verdict, InscriptionCheck.Source.BUILT_IN, runes, emptyList(), reason, blockHash, open)

    private val clean = check(Verdict.CLEAN)

    @Test
    fun indexerThatSaysCarriesHolds() {
        assertEquals(CoinState.HELD, coinState(clean, indexerSaysCarries = true, localMayCarry = false, hasAnyResults = true))
        assertEquals(CoinState.HELD, coinState(null, indexerSaysCarries = true, localMayCarry = false, hasAnyResults = false))
    }

    @Test
    fun inscribedCheckHolds() {
        assertEquals(CoinState.HELD, coinState(check(Verdict.INSCRIBED), null, localMayCarry = false, hasAnyResults = true))
        assertEquals(CoinState.HELD, coinState(check(Verdict.INSCRIBED), false, localMayCarry = false, hasAnyResults = true))
        //an Inscribed trace cut short is still held, not merely unchecked
        assertEquals(CoinState.HELD, coinState(check(Verdict.INSCRIBED, reason = "budget"), null, false, true))
    }

    @Test
    fun runesCheckHolds() {
        assertEquals(CoinState.HELD, coinState(check(Verdict.CLEAN, runes = true), false, localMayCarry = false, hasAnyResults = true))
        assertEquals(CoinState.HELD, coinState(check(Verdict.UNCHECKED, runes = true), null, false, true))
        //the built-in check's "may carry runes" (a runestone or dunestone in the creating transaction) holds too
        assertEquals(CoinState.HELD, coinState(check(Verdict.UNCHECKED, reason = CheckPolicy.MAY_CARRY_RUNES), null, false, true))
    }

    /**
     * A proven Clean check frees a postage-valued coin only where the trace sees everything: on a chain without a rune-like
     * protocol (Bellscoin), or where an indexer that indexes runes answered clean for it.
     */
    @Test
    fun cleanCheckFreesAPostageValuedCoin() {
        val bells = ChainRules.hasRuneLikeTokens(Coin.BELLSCOIN)
        assertEquals(CoinState.CLEAN, coinState(clean, indexerSaysCarries = null, localMayCarry = true, hasAnyResults = true, runeLike = bells, runesIndexed = false))
        assertEquals(CoinState.CLEAN, coinState(clean, indexerSaysCarries = false, localMayCarry = true, hasAnyResults = true, runeLike = bells, runesIndexed = false))
        assertEquals(CoinState.CLEAN, coinState(clean, indexerSaysCarries = false, localMayCarry = true, hasAnyResults = true, runeLike = true, runesIndexed = true))
    }

    /**
     * Rule 3 on a chain with a rune-like protocol: runes move to a coin with no runestone in its creating transaction, and
     * the trace cannot see them. A proven Clean frees the coin only when (a) the chain has no rune-like protocol, (b) an
     * indexer that indexes runes answered clean, or (c) the local rule does not flag it; otherwise it is held, whatever
     * an inscriptions-only indexer (Nintondo) said.
     */
    @Test
    fun cleanCheckOnARuneChainNeedsARunesAnswerOrAnUnflaggedCoin() {
        fun state(coin: Coin, answer: Boolean?, runesIndexed: Boolean, local: Boolean) =
            coinState(clean, answer, local, hasAnyResults = true, runeLike = ChainRules.hasRuneLikeTokens(coin), runesIndexed = runesIndexed)
        for(coin in listOf(Coin.LITECOIN, Coin.BELLSCOIN)) {
            val runeLike = coin == Coin.LITECOIN
            //all three false (only (a) for Bellscoin)
            assertEquals(if(runeLike) CoinState.HELD else CoinState.CLEAN, state(coin, null, runesIndexed = false, local = true), "$coin none")
            //(b) a runes-aware indexer said clean
            assertEquals(CoinState.CLEAN, state(coin, false, runesIndexed = true, local = true), "$coin b")
            //an inscriptions-only indexer's clean is not (b)
            assertEquals(if(runeLike) CoinState.HELD else CoinState.CLEAN, state(coin, false, runesIndexed = false, local = true), "$coin nintondo")
            //a runes-aware indexer that gave no answer is not (b) either
            assertEquals(if(runeLike) CoinState.HELD else CoinState.CLEAN, state(coin, null, runesIndexed = true, local = true), "$coin no answer")
            //(c) the local rule does not flag it
            assertEquals(CoinState.CLEAN, state(coin, null, runesIndexed = false, local = false), "$coin c")
            assertEquals(CoinState.CLEAN, state(coin, false, runesIndexed = true, local = false), "$coin b and c")
            //an indexer that says it carries still holds it
            assertEquals(CoinState.HELD, state(coin, true, runesIndexed = true, local = false), "$coin carries")
        }
    }

    /** The safe side by default: a caller that does not say otherwise gets a rune-like chain and no runes answer. */
    @Test
    fun ruleThreeDefaultsToTheRuneChainRule() {
        assertEquals(CoinState.HELD, coinState(clean, null, localMayCarry = true, hasAnyResults = true))
        assertEquals(CoinState.CLEAN, coinState(clean, null, localMayCarry = false, hasAnyResults = true))
    }

    /**
     * Review M1: "May carry runes" is written only over a Clean trace (a runestone or dunestone in the creating
     * transaction). A runes-aware indexer's clean answer settles it: Clean when the row is proven against its block,
     * Unchecked when not. Without such an answer it stays held.
     */
    @Test
    fun mayCarryRunesFollowsARunesAwareCleanAnswer() {
        val runes = check(Verdict.UNCHECKED, reason = CheckPolicy.MAY_CARRY_RUNES)
        assertEquals(CoinState.CLEAN, coinState(runes, false, localMayCarry = true, hasAnyResults = true, runeLike = true, runesIndexed = true))
        assertEquals(CoinState.UNCHECKED, coinState(check(Verdict.UNCHECKED, reason = CheckPolicy.MAY_CARRY_RUNES, blockHash = null), false,
            localMayCarry = true, hasAnyResults = true, runeLike = true, runesIndexed = true))
        assertEquals(CoinState.HELD, coinState(runes, false, localMayCarry = false, hasAnyResults = true, runeLike = true, runesIndexed = false))
        assertEquals(CoinState.HELD, coinState(runes, null, localMayCarry = false, hasAnyResults = true, runeLike = true, runesIndexed = true))
        assertEquals(CoinState.HELD, coinState(runes, true, localMayCarry = false, hasAnyResults = true, runeLike = true, runesIndexed = true))
        //runes the indexer itself reported still hold
        assertEquals(CoinState.HELD, coinState(check(Verdict.CLEAN, runes = true), false, localMayCarry = false, hasAnyResults = true,
            runeLike = true, runesIndexed = true))
    }

    @Test
    fun provisionalCleanIsUnchecked() {
        assertEquals(CoinState.UNCHECKED, coinState(check(Verdict.CLEAN, blockHash = null), null, localMayCarry = false, hasAnyResults = true))
        assertEquals(CoinState.UNCHECKED, coinState(check(Verdict.CLEAN, blockHash = null), false, localMayCarry = false, hasAnyResults = true))
    }

    @Test
    fun incompleteCleanIsUnchecked() {
        assertEquals(CoinState.UNCHECKED, coinState(check(Verdict.CLEAN, reason = "cut short"), null, false, true))
    }

    @Test
    fun uncheckedCheckIsUnchecked() {
        assertEquals(CoinState.UNCHECKED, coinState(check(Verdict.UNCHECKED, reason = "budget"), null, localMayCarry = false, hasAnyResults = true))
        assertEquals(CoinState.UNCHECKED, coinState(check(Verdict.UNCHECKED, reason = "budget"), false, localMayCarry = false, hasAnyResults = true))
    }

    /** A coin newer than the last run: the wallet has results, just none for it. */
    @Test
    fun coinWithoutACheckIsUncheckedWhenTheWalletHasResults() {
        assertEquals(CoinState.UNCHECKED, coinState(null, indexerSaysCarries = null, localMayCarry = false, hasAnyResults = true))
        assertEquals(CoinState.UNCHECKED, coinState(null, indexerSaysCarries = false, localMayCarry = false, hasAnyResults = true))
    }

    @Test
    fun noResultFallsBackToLocalRule() {
        assertEquals(CoinState.HELD, coinState(null, indexerSaysCarries = null, localMayCarry = true, hasAnyResults = false))
        assertEquals(CoinState.CLEAN, coinState(null, indexerSaysCarries = null, localMayCarry = false, hasAnyResults = false))
        //today's rule: the indexer's clean answer wins over the local rule
        assertEquals(CoinState.CLEAN, coinState(null, indexerSaysCarries = false, localMayCarry = true, hasAnyResults = false))
    }

    /** The local guess is asked only when nothing better decides: no indexer answer and no Clean, Inscribed or runes check. */
    @Test
    fun localRuleIsAskedOnlyWhenNothingElseDecides() {
        var asked = 0
        coinState(clean, null, { asked++; true }, hasAnyResults = true, runeLike = false, runesIndexed = false)
        coinState(clean, false, { asked++; true }, hasAnyResults = true, runeLike = true, runesIndexed = true)
        coinState(check(Verdict.INSCRIBED), null, { asked++; true }, hasAnyResults = true)
        coinState(null, true, { asked++; true }, hasAnyResults = true)
        coinState(null, false, { asked++; true }, hasAnyResults = true)
        coinState(check(Verdict.UNCHECKED), false, { asked++; true }, hasAnyResults = true)
        coinState(null, false, { asked++; true }, hasAnyResults = false)
        assertEquals(0, asked)
        coinState(check(Verdict.UNCHECKED), null, { asked++; true }, hasAnyResults = true)
        coinState(null, null, { asked++; true }, hasAnyResults = true)
        coinState(null, null, { asked++; true }, hasAnyResults = false)
        assertEquals(3, asked)
        //on a rune-like chain without a runes-aware clean answer, a proven Clean asks it once, last
        coinState(clean, null, { asked++; true }, hasAnyResults = true, runeLike = true, runesIndexed = false)
        assertEquals(4, asked)
    }

    /** Rule 4: an Unchecked check does not release a coin the local rule flags, unless the indexer cleared it. */
    @Test
    fun uncheckedCoinTheLocalRuleFlagsStaysHeld() {
        assertEquals(CoinState.HELD, coinState(check(Verdict.UNCHECKED, reason = "budget"), null, localMayCarry = true, hasAnyResults = true))
        assertEquals(CoinState.HELD, coinState(check(Verdict.CLEAN, blockHash = null), null, localMayCarry = true, hasAnyResults = true))
    }

    @Test
    fun uncheckedCoinTheIndexerClearedIsUnchecked() {
        assertEquals(CoinState.UNCHECKED, coinState(check(Verdict.UNCHECKED, reason = "budget"), false, localMayCarry = true, hasAnyResults = true))
    }

    /** Rule 5: a coin newer than the last run that the local rule flags stays held until a check clears it. */
    @Test
    fun newCoinTheLocalRuleFlagsStaysHeld() {
        assertEquals(CoinState.HELD, coinState(null, null, localMayCarry = true, hasAnyResults = true))
    }

    @Test
    fun newCoinTheIndexerClearedIsUnchecked() {
        assertEquals(CoinState.UNCHECKED, coinState(null, false, localMayCarry = true, hasAnyResults = true))
    }

    /** What the Send paths decide for a wallet's ready coins, from its stored results (null: no readable file). */
    @Test
    fun aWalletCheckedWhileEmptyCountsAsChecked(): Unit = runBlocking {
        val new = "cc".repeat(32) + ":0"
        //a readable file holding no results: a new coin is Unchecked, or held when the local rule flags it
        assertEquals(CoinState.UNCHECKED, classifyCoins(listOf(new), emptyMap(), covered = true, true, false, { null }, { false }).state(new))
        assertEquals(CoinState.HELD, classifyCoins(listOf(new), emptyMap(), covered = true, true, false, { null }, { true }).state(new))
        //no readable file (never checked, or lost): today's rule
        assertEquals(CoinState.CLEAN, classifyCoins(listOf(new), null, covered = true, true, false, { null }, { false }).state(new))
        assertEquals(CoinState.HELD, classifyCoins(listOf(new), null, covered = true, true, false, { null }, { true }).state(new))
    }

    @Test
    fun classificationReadsResultsByOutpoint(): Unit = runBlocking {
        val a = clean.outpoint
        val b = "dd".repeat(32) + ":1"
        val inscribed = check(Verdict.INSCRIBED).copy(txid = "dd".repeat(32), vout = 1)
        //keyed oddly on purpose: the results are matched by each check's own outpoint
        val results = mapOf("x" to clean, "y" to inscribed)
        val states = classifyCoins(listOf(a, b), results, covered = true, runeLike = false, runesIndexed = false, { null }, { true })
        assertEquals(CoinState.CLEAN, states.state(a))
        assertEquals(CoinState.HELD, states.state(b))
        assertTrue(states.inscribed(b))
        assertFalse(states.inscribed(a))
        //a coin that was not classified (not ready to spend) reads as held, never clean
        assertEquals(CoinState.HELD, states.state("ee".repeat(32) + ":0"))
    }

    /** Luckycoin and Dingocoin have no check: any results are ignored and today's rule decides. */
    @Test
    fun uncoveredCoinKeepsTodaysRule(): Unit = runBlocking {
        val a = clean.outpoint
        val states = classifyCoins(listOf(a), mapOf(a to check(Verdict.INSCRIBED)), covered = false, false, false, { null }, { false })
        assertEquals(CoinState.CLEAN, states.state(a))
        assertFalse(states.inscribed(a))
        assertEquals(CoinState.HELD, classifyCoins(listOf(a), emptyMap(), covered = false, false, false, { true }, { false }).state(a))
    }

    /** The Send paths' classification passes the chain's runes facts on to rule 3. */
    @Test
    fun classificationAppliesTheRuneChainRule(): Unit = runBlocking {
        val a = clean.outpoint
        val results = mapOf(a to clean)
        assertEquals(CoinState.HELD, classifyCoins(listOf(a), results, covered = true, runeLike = true, runesIndexed = false, { null }, { true }).state(a))
        assertEquals(CoinState.CLEAN, classifyCoins(listOf(a), results, covered = true, runeLike = true, runesIndexed = true, { false }, { true }).state(a))
        assertEquals(CoinState.HELD, classifyCoins(listOf(a), results, covered = true, runeLike = true, runesIndexed = false, { false }, { true }).state(a))
        assertEquals(CoinState.CLEAN, classifyCoins(listOf(a), results, covered = true, runeLike = false, runesIndexed = false, { null }, { true }).state(a))
    }

    /** The Nintondo caveat survives a Clean check: an inscriptions-only indexer cannot clear a token coin. */
    @Test
    fun tokenCaveatHoldsDespiteACleanCheck() {
        val carries = indexerCarries(serverAnswer = false, tokensIndexed = false) { true }
        assertEquals(true, carries)
        assertEquals(CoinState.HELD, coinState(clean, carries, localMayCarry = false, hasAnyResults = true))
        assertEquals(false, indexerCarries(serverAnswer = false, tokensIndexed = false) { false })
        assertEquals(false, indexerCarries(serverAnswer = false, tokensIndexed = true) { true })
        assertNull(indexerCarries(serverAnswer = null) { true })
        assertEquals(true, indexerCarries(serverAnswer = true) { false })
    }

    @Test
    fun heldCopyNamesInscriptionsOnlyWhenEveryHeldCoinIsInscribed() {
        assertEquals("1 coin (0.0001 LTC) held back: they carry inscriptions.", heldCopy(1, "0.0001 LTC", inscribed = true, included = false))
        assertEquals("2 coins (0.0002 LTC) held back: they may carry an inscription or tokens.",
            heldCopy(2, "0.0002 LTC", inscribed = false, included = false))
        assertEquals("2 coins (0.0002 LTC) that may carry an inscription or tokens can be spent. Anything they carry could be lost.",
            heldCopy(2, "0.0002 LTC", inscribed = false, included = true))
        assertEquals("1 coin (0.5 LTC) not yet checked for inscriptions is left out.", uncheckedLeftOutCopy(1, "0.5 LTC"))
        assertEquals("3 coins (0.5 LTC) not yet checked for inscriptions are left out.", uncheckedLeftOutCopy(3, "0.5 LTC"))
        assertEquals("This payment uses 2 coins that have not been checked for inscriptions.", uncheckedWarning(2))
        assertEquals("This payment uses 1 coin that has not been checked for inscriptions.", uncheckedWarning(1))
        assertEquals("This payment uses 1 held coin.", heldWarning(1))
        assertEquals("This payment uses 3 held coins.", heldWarning(3))
    }
}
