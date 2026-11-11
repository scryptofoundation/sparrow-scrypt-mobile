package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.ChainRules
import com.sparrowwallet.mobile.electrum.indexer.IndexerException
import com.sparrowwallet.mobile.electrum.indexer.IndexerInscription
import com.sparrowwallet.mobile.electrum.indexer.IndexerOutput
import com.sparrowwallet.mobile.electrum.indexer.IndexerStatus
import com.sparrowwallet.mobile.electrum.indexer.InscriptionIndexer
import com.sparrowwallet.mobile.electrum.indexer.RuneBalance
import com.sparrowwallet.mobile.crypto.track.Piece
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InscriptionChecksTest {
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

    /**
     * A coin straight out of MWEB (its creating transaction a confirmed, flagged HogEx) has no history to carry an
     * inscription or runes: with a proven complete Clean check it is Clean whatever its value, with no indexer.
     */
    @Test
    fun aProvenCleanMwebPegOutIsCleanWhateverItsValue() {
        val ltc = ChainRules.hasRuneLikeTokens(Coin.LITECOIN)
        assertTrue(ltc)
        val pegOut = clean.copy(mwebPegOut = true)
        var asked = 0
        assertEquals(CoinState.CLEAN, coinState(pegOut, null, { asked++; true }, hasAnyResults = true, runeLike = ltc, runesIndexed = false))
        assertEquals(0, asked, "the postage rule is not consulted")
        //not known to be one: the postage rule holds it, as before
        for(flag in listOf(false, null)) {
            assertEquals(CoinState.HELD, coinState(clean.copy(mwebPegOut = flag), null, localMayCarry = true, hasAnyResults = true,
                runeLike = ltc, runesIndexed = false), "$flag")
        }
        //an indexer that says it carries still holds it (rule 1)
        assertEquals(CoinState.HELD, coinState(pegOut, indexerSaysCarries = true, localMayCarry = false, hasAnyResults = true,
            runeLike = ltc, runesIndexed = true))
    }

    /** The flag frees only a proven complete Clean row: any other row with it follows its own rule as before. */
    @Test
    fun theMwebPegOutFlagFreesOnlyAProvenCompleteCleanRow() {
        val ltc = ChainRules.hasRuneLikeTokens(Coin.LITECOIN)
        fun state(row: InscriptionCheck, answer: Boolean? = null, local: Boolean = true) =
            coinState(row.copy(mwebPegOut = true), answer, local, hasAnyResults = true, runeLike = ltc, runesIndexed = false)
        //rules 4 and 5: Unchecked, provisional or cut-short Clean rows
        assertEquals(CoinState.HELD, state(check(Verdict.UNCHECKED, reason = "budget")))
        assertEquals(CoinState.UNCHECKED, state(check(Verdict.UNCHECKED, reason = "budget"), local = false))
        assertEquals(CoinState.HELD, state(check(Verdict.CLEAN, blockHash = null)))
        assertEquals(CoinState.UNCHECKED, state(check(Verdict.CLEAN, blockHash = null), local = false))
        assertEquals(CoinState.HELD, state(check(Verdict.CLEAN, reason = "cut short")))
        //rule 2: runes on it, or a "may carry runes" row
        assertEquals(CoinState.HELD, state(check(Verdict.CLEAN, runes = true), local = false))
        assertEquals(CoinState.HELD, state(check(Verdict.UNCHECKED, reason = CheckPolicy.MAY_CARRY_RUNES), local = false))
        assertEquals(CoinState.HELD, state(check(Verdict.INSCRIBED), local = false))
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
        coinState(null, false, { asked++; true }, hasAnyResults = true, runeLike = false)
        coinState(check(Verdict.UNCHECKED), false, { asked++; true }, hasAnyResults = true, runeLike = false)
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
        assertEquals(CoinState.UNCHECKED, coinState(check(Verdict.UNCHECKED, reason = "budget"), false, localMayCarry = true, hasAnyResults = true,
            runeLike = false))
    }

    /** Rule 5: a coin newer than the last run that the local rule flags stays held until a check clears it. */
    @Test
    fun newCoinTheLocalRuleFlagsStaysHeld() {
        assertEquals(CoinState.HELD, coinState(null, null, localMayCarry = true, hasAnyResults = true))
    }

    @Test
    fun newCoinTheIndexerClearedIsUnchecked() {
        assertEquals(CoinState.UNCHECKED, coinState(null, false, localMayCarry = true, hasAnyResults = true, runeLike = false))
    }

    /**
     * Residual R1: rules 4 and 5 on a rune-like chain. A clean answer from an indexer that does not index the chain's
     * tokens (Nintondo; or any indexer off Litecoin) does not release a coin the local rule flags: HELD on Dogecoin,
     * UNCHECKED on Bellscoin. A runes-aware clean answer (Litecoin ord) still gives UNCHECKED. Rule 6 is unchanged.
     */
    @Test
    fun anIndexersCleanThatIsNotRunesAwareDoesNotReleaseAFlaggedCoin() {
        val doge = ChainRules.hasRuneLikeTokens(Coin.DOGECOIN)
        val bells = ChainRules.hasRuneLikeTokens(Coin.BELLSCOIN)
        val budget = check(Verdict.UNCHECKED, reason = "budget")
        for(row in listOf(budget, null)) {
            assertEquals(CoinState.HELD, coinState(row, false, localMayCarry = true, hasAnyResults = true, runeLike = doge, runesIndexed = false), "doge $row")
            assertEquals(CoinState.UNCHECKED, coinState(row, false, localMayCarry = true, hasAnyResults = true, runeLike = bells, runesIndexed = false), "bells $row")
            assertEquals(CoinState.UNCHECKED, coinState(row, false, localMayCarry = true, hasAnyResults = true, runeLike = true, runesIndexed = true), "ltc ord $row")
            assertEquals(CoinState.UNCHECKED, coinState(row, false, localMayCarry = false, hasAnyResults = true, runeLike = doge, runesIndexed = false), "unflagged $row")
        }
        //rule 6 (no results file) keeps today's rule: the indexer's clean answer wins
        assertEquals(CoinState.CLEAN, coinState(null, false, localMayCarry = true, hasAnyResults = false, runeLike = doge, runesIndexed = false))
    }

    /**
     * Residual R1: only Litecoin's ord indexer counts as runes-aware. ord's /output reads `runes`, never dunes or junes,
     * so on Dogecoin a custom ord indexer with `rune_index` on does not clear a postage-valued Clean coin or a
     * "may carry runes" row; on Litecoin it still does. Nintondo never counts.
     */
    @Test
    fun onlyLitecoinsOrdIndexerIsRunesAware(): Unit = runBlocking {
        val ord = SendIndexer(runesAware = true) { asked -> asked.associateWith { nothing(it) } }
        assertTrue(runesAwareIndexer(Coin.LITECOIN, ord))
        for(coin in listOf(Coin.DOGECOIN, Coin.PEPECOIN, Coin.JUNKCOIN, Coin.BELLSCOIN)) {
            assertFalse(runesAwareIndexer(coin, ord), coin.toString())
        }
        //Nintondo's reader never says it indexes runes; nor does an ord without its rune index
        assertFalse(runesAwareIndexer(Coin.LITECOIN, SendIndexer(runesAware = false) { emptyMap() }))
        assertFalse(runesAwareIndexer(Coin.LITECOIN, null))

        val a = clean.outpoint
        val runes = check(Verdict.UNCHECKED, reason = CheckPolicy.MAY_CARRY_RUNES)
        //Send's whole path: the reader's answer for the coin, then the classification
        suspend fun state(coin: Coin, row: InscriptionCheck?, local: Boolean = true): CoinState {
            val readers = IndexerReaders { _, _, _, _ -> ord }
            val answers = readers.answers(InscriptionSetting.YourOwn("https://ord.example"), coin, Network.MAINNET, null, listOf(utxo(a)), airGapped = false)
            return classifyCoins(listOf(a), row?.let { mapOf(a to it) }, covered = true, ChainRules.hasRuneLikeTokens(coin),
                answers.runesAware, { answers.of(it) }, { local }).state(a)
        }
        assertEquals(CoinState.HELD, state(Coin.DOGECOIN, clean))
        assertEquals(CoinState.HELD, state(Coin.DOGECOIN, runes))
        assertEquals(CoinState.CLEAN, state(Coin.LITECOIN, clean))
        assertEquals(CoinState.CLEAN, state(Coin.LITECOIN, runes))
        //a never-checked wallet: an ord "clean" off Litecoin does not override the postage-value hold either
        for(coin in listOf(Coin.DOGECOIN, Coin.PEPECOIN, Coin.JUNKCOIN)) {
            assertEquals(CoinState.HELD, state(coin, null), coin.toString())
        }
        assertEquals(CoinState.CLEAN, state(Coin.LITECOIN, null))
    }

    private fun nothing(outpoint: String) = IndexerOutput(outpoint, true, emptyList(), emptyMap(), false)

    private fun utxo(outpoint: String, height: Int = 100) =
        com.sparrowwallet.mobile.electrum.Utxo(outpoint.substringBefore(':'), outpoint.substringAfter(':').toInt(), height, 1_000)

    /** An indexer reader for the Send tests; counts its calls. */
    private class SendIndexer(override var runesAware: Boolean = true, var answer: suspend (List<String>) -> Map<String, IndexerOutput>) : InscriptionIndexer {
        var asked = 0
        var closed = false
        override suspend fun status() = IndexerStatus("mainnet", runesAware, 1)
        override suspend fun outputs(outpoints: List<String>): Map<String, IndexerOutput> {
            if(closed) throw IndexerException("closed")
            asked++
            return answer(outpoints)
        }
        override suspend fun inscription(id: String): IndexerInscription? = error("Send never asks for an inscription")
        override fun close() { closed = true }
    }

    /** The display fields a new results file carries (rune balances, value, address position) decide nothing in Send. */
    @Test
    fun newFieldsDecideNothing() {
        val extras = { row: InscriptionCheck -> row.copy(runeList = emptyMap(), value = 546, chain = 0, index = 3) }
        val rows = listOf(clean, check(Verdict.UNCHECKED, reason = "budget"), check(Verdict.CLEAN, blockHash = null),
            check(Verdict.UNCHECKED, reason = CheckPolicy.MAY_CARRY_RUNES), check(Verdict.INSCRIBED))
        for(row in rows) for(answer in listOf(null, false, true)) for(local in listOf(false, true)) for(runesIndexed in listOf(false, true)) {
            assertEquals(coinState(row, answer, local, true, true, runesIndexed), coinState(extras(row), answer, local, true, true, runesIndexed), "$row")
        }
    }

    /** Send reads an indexer output: carries is true, an unindexed or mismatched one is no answer, and only an indexed nothing is false. */
    @Test
    fun sendAnswerMapping() {
        val a = clean.outpoint
        fun answer(output: IndexerOutput?, runesAware: Boolean = true, runeLike: Boolean = true) = sendAnswer(output, a, runesAware, runeLike)
        assertEquals(true, answer(IndexerOutput(a, true, listOf("ab".repeat(32) + "i0"), emptyMap(), false)))
        assertEquals(true, answer(IndexerOutput(a, true, emptyList(), mapOf("A" to RuneBalance("1", 0, null)), false)))
        assertEquals(true, answer(IndexerOutput(a, true, emptyList(), emptyMap(), true)))
        //carries even when it says it is not indexed: never read as clean
        assertEquals(true, answer(IndexerOutput(a, false, emptyList(), emptyMap(), true)))
        assertNull(answer(IndexerOutput.unknown(a)))
        assertNull(answer(null))
        assertNull(answer(nothing("cc".repeat(32) + ":0")), "an entry for another outpoint")
        assertEquals(false, answer(nothing(a)))
        //a clean that is not runes-aware on a rune-like chain is no answer; on Bellscoin it stands
        assertNull(answer(nothing(a), runesAware = false, runeLike = true))
        assertEquals(false, answer(nothing(a), runesAware = false, runeLike = false))
    }

    /** Built-in (and a setting before the notice is answered, and air-gapped mode) asks no indexer in Send. */
    @Test
    fun sendAsksNoIndexerOnBuiltIn(): Unit = runBlocking {
        var opened = 0
        val readers = IndexerReaders { _, _, _, _ -> opened++; SendIndexer { error("asked") } }
        val a = clean.outpoint
        val unanswered = InscriptionSetting.parse(null, hasPublic = true, defaultChoice = null)
        for(setting in listOf(InscriptionSetting.BuiltIn, unanswered)) {
            val answers = readers.answers(setting, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), airGapped = false)
            assertNull(answers.of(a))
            assertFalse(answers.runesAware)
            //so the classification falls to the results and the local rule
            assertEquals(CoinState.HELD, classifyCoins(listOf(a), null, true, true, answers.runesAware, { answers.of(it) }, { true }).state(a))
        }
        for(setting in listOf(InscriptionSetting.Public, InscriptionSetting.YourOwn("https://ord.example"))) {
            assertNull(readers.answers(setting, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), airGapped = true).of(a))
        }
        assertEquals(0, opened)
    }

    /**
     * Nintondo answers an output it lists nothing for as unknown (indexed false): no answer in Send, so the coin falls to
     * its check and the local rule. On Bellscoin, where an indexer's clean could free a coin, such a coin is not freed.
     */
    @Test
    fun anEmptyNintondoAnswerDoesNotFreeABellscoinCoin(): Unit = runBlocking {
        val a = clean.outpoint
        val nintondo = SendIndexer(runesAware = false) { asked -> asked.associateWith { IndexerOutput.unknown(it) } }
        val readers = IndexerReaders { _, _, _, _ -> nintondo }
        val answers = readers.answers(InscriptionSetting.Public, Coin.BELLSCOIN, Network.MAINNET, null, listOf(utxo(a)), airGapped = false)
        assertEquals(1, nintondo.asked)
        assertNull(answers.of(a))
        val runeLike = ChainRules.hasRuneLikeTokens(Coin.BELLSCOIN)
        assertFalse(runeLike)
        //never checked, flagged by the local rule: held (a "clean" would have freed it)
        assertEquals(CoinState.HELD, classifyCoins(listOf(a), null, true, runeLike, answers.runesAware, { answers.of(it) }, { true }).state(a))
        assertEquals(CoinState.CLEAN, classifyCoins(listOf(a), null, true, runeLike, false, { false }, { true }).state(a), "the contrast")
        //checked but not yet Clean: Unchecked stays Unchecked, flagged stays held
        val budget = check(Verdict.UNCHECKED, reason = "budget")
        assertEquals(CoinState.HELD, classifyCoins(listOf(a), mapOf(a to budget), true, runeLike, answers.runesAware, { answers.of(it) }, { true }).state(a))
    }

    /**
     * A reader that cannot be opened (wrong chain, block 0 mismatch) answers nothing for every coin, and answers cached
     * before a failure are not reused once the reader has failed.
     */
    @Test
    fun aReaderThatFailsToOpenAnswersNothing(): Unit = runBlocking {
        val a = clean.outpoint
        val b = "dd".repeat(32) + ":1"
        val coins = listOf(utxo(a), utxo(b))
        var reader = SendIndexer { asked -> asked.associateWith { if(it == b) IndexerOutput(it, true, listOf("ab".repeat(32) + "i0"), emptyMap(), false) else nothing(it) } }
        var refuse: Exception? = IndexerException("Inscriptions server is for another chain (its block 0 is 0000...), not a Litecoin Mainnet server")
        var clock = 1_000_000L
        val readers = IndexerReaders(now = { clock }) { _, _, _, _ -> refuse?.let { throw it }; reader }
        fun all(answers: IndexerReaders.Answers) = coins.map { answers.of("${it.txHash}:${it.txPos}") }

        assertEquals(listOf(null, null), all(readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, coins, false)))
        refuse = null
        //a failed open is remembered for a minute
        clock += IndexerReaders.FAILED_OPEN_MS + 1
        assertEquals(listOf(false, true), all(readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, coins, false)))
        assertEquals(1, reader.asked)
        //confirmed coins' answers are kept while the reader works
        assertEquals(listOf(false, true), all(readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, coins, false)))
        assertEquals(1, reader.asked)

        //the reader fails for a new coin: no answers at all this pass, and it is dropped and closed
        val old = reader
        reader = SendIndexer { throw IndexerException("Inscriptions server is on regtest, wallet is on Mainnet") }
        old.answer = { throw IndexerException("Inscriptions server is on regtest, wallet is on Mainnet") }
        val withNew = coins + utxo("ee".repeat(32) + ":0")
        assertTrue(readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, withNew, false).byOutpoint.values.all { it == null })
        assertTrue(old.closed)
        //and once it cannot be opened again, nothing cached from before is used
        refuse = IndexerException("Inscriptions server is for another chain")
        assertEquals(listOf(null, null), all(readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, coins, false)))

        //clearing (a server settings change) drops the answers too
        refuse = null
        clock += IndexerReaders.FAILED_OPEN_MS + 1
        reader = SendIndexer { asked -> asked.associateWith { nothing(it) } }
        readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, coins, false)
        readers.clear()
        assertTrue(reader.closed)
        refuse = IndexerException("refused")
        assertEquals(listOf(null, null), all(readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, coins, false)))
    }

    /** A reader is kept per coin, network, URL and proxy: one checked for one coin never answers for another. */
    @Test
    fun readerCacheKeysOnCoinNetworkUrlAndProxy(): Unit = runBlocking {
        val opened = ArrayList<String>()
        val made = ArrayList<SendIndexer>()
        val readers = IndexerReaders { url, coin, network, proxy ->
            opened.add("$url $coin $network $proxy")
            SendIndexer { asked -> asked.associateWith { nothing(it) } }.also { made.add(it) }
        }
        val own = InscriptionSetting.YourOwn("https://ord.example")
        val tor = com.sparrowwallet.mobile.electrum.SocksProxy("127.0.0.1", 9050)
        val coins = listOf(utxo(clean.outpoint, height = 0))
        readers.answers(own, Coin.LITECOIN, Network.MAINNET, null, coins, false)
        readers.answers(own, Coin.LITECOIN, Network.MAINNET, null, coins, false)
        assertEquals(1, opened.size)
        readers.answers(own, Coin.LITECOIN, Network.MAINNET, tor, coins, false)
        readers.answers(own, Coin.DOGECOIN, Network.MAINNET, tor, coins, false)
        readers.answers(own, Coin.LITECOIN, Network.TESTNET, tor, coins, false)
        readers.answers(InscriptionSetting.YourOwn("https://other.example"), Coin.LITECOIN, Network.TESTNET, tor, coins, false)
        assertEquals(5, opened.size)
        assertEquals(5, opened.toSet().size)
        //an unconfirmed coin's answer is asked again each pass
        assertEquals(2, made.first().asked)
        readers.clear()
        assertTrue(made.all { it.closed })
        readers.answers(own, Coin.LITECOIN, Network.MAINNET, null, coins, false)
        assertEquals(6, opened.size)
    }

    /** A URL the reader cannot take is no answer, never a crash; cancellation is not swallowed. */
    @Test
    fun sendSurvivesAnIndexerThatCannotBeUsed(): Unit = runBlocking {
        val a = clean.outpoint
        //the real opener: a malformed Your own URL is refused before any request
        val real = IndexerReaders()
        assertNull(real.answers(InscriptionSetting.YourOwn("http://a b"), Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false).of(a))
        val cancelled = IndexerReaders { _, _, _, _ -> throw kotlinx.coroutines.CancellationException("gone") }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            cancelled.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        }
        val cancelling = IndexerReaders { _, _, _, _ -> SendIndexer { throw kotlinx.coroutines.CancellationException("gone") } }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            cancelling.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        }
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

    /**
     * Review I3: the worker runs a wallet's check only when its scan found something new (due), the wallet has no
     * readable results, or a stored row can be retried. A wallet whose rows all stand costs nothing.
     */
    @Test
    fun theWorkerChecksOnlyWhenNeeded() {
        val deadEnd = check(Verdict.UNCHECKED, reason = com.sparrowwallet.mobile.crypto.track.InscriptionTracer.FEES_NEED_DEEPER)
        val standing = mapOf("a" to clean, "b" to check(Verdict.INSCRIBED), "c" to deadEnd)
        assertFalse(inscriptionCheckNeeded(due = false, stored = standing))
        assertFalse(inscriptionCheckNeeded(due = false, stored = emptyMap()))
        assertTrue(inscriptionCheckNeeded(due = true, stored = standing))
        assertTrue(inscriptionCheckNeeded(due = false, stored = null), "no readable results file")
        for(retry in listOf(
            check(Verdict.UNCHECKED, reason = "Lookup budget used up; use Check deeper"),
            check(Verdict.UNCHECKED, reason = "Server error, will retry next sync: HTTP 429"),
            check(Verdict.UNCHECKED, reason = com.sparrowwallet.mobile.electrum.inscriptions.TrackerRun.TIME_UP),
            check(Verdict.UNCHECKED, reason = com.sparrowwallet.mobile.crypto.track.InscriptionTracer.HEIGHT_UNKNOWN),
            check(Verdict.CLEAN, blockHash = null),
            check(Verdict.UNCHECKED, reason = com.sparrowwallet.mobile.crypto.track.InscriptionTracer.FEES_NEED_DEEPER, blockHash = null),
            //A coin out of MWEB is clean since 2026-11-10, so an old "Came through MWEB" row is retried, block or not
            check(Verdict.UNCHECKED, reason = com.sparrowwallet.mobile.crypto.track.InscriptionTracer.THROUGH_MWEB)
        )) {
            assertTrue(inscriptionCheckNeeded(due = false, stored = standing + ("d" to retry)), retry.toString())
        }
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

    /** For a move into MWEB the held line has no box: it says plainly that held coins are not moved, whatever was ticked. */
    @Test
    fun heldCopyForAMoveIntoMwebSaysHeldCoinsStay() {
        for(included in listOf(false, true)) {
            assertEquals("1 coin (0.0001 LTC) held back: they carry inscriptions. Held coins are not moved into MWEB.",
                heldCopy(1, "0.0001 LTC", inscribed = true, included = included, pegIn = true))
            assertEquals("2 coins (0.0002 LTC) held back: they may carry an inscription or tokens. Held coins are not moved into MWEB.",
                heldCopy(2, "0.0002 LTC", inscribed = false, included = included, pegIn = true))
        }
    }

    private fun ready(vararg outpoints: String) = outpoints.associateWith { utxo(it) }

    private suspend fun send(readers: IndexerReaders, setting: InscriptionSetting, coin: Coin, ready: Map<String, com.sparrowwallet.mobile.electrum.Utxo>,
                             results: Map<String, InscriptionCheck>?, writesTokens: Boolean = false, local: Boolean = true,
                             proxy: com.sparrowwallet.mobile.electrum.SocksProxy? = null, airGapped: Boolean = false): CoinStates =
        classifySend(ready, results, coin, Network.MAINNET, setting, proxy, readers, airGapped, { writesTokens }, { local })

    /**
     * Review finding 2: Bellscoin in Send is as it was at 65b6d30 with an ord indexer of your own (its clean stands, and a
     * runestone in the creating transaction does not turn it into "carries"), except that Nintondo's empty list is now no
     * answer.
     */
    @Test
    fun bellscoinInSendIsAsBefore(): Unit = runBlocking {
        val a = clean.outpoint
        val ord = SendIndexer(runesAware = true) { asked -> asked.associateWith { nothing(it) } }
        val readers = IndexerReaders { _, _, _, _ -> ord }
        val own = InscriptionSetting.YourOwn("https://ord.example")
        assertEquals(CoinState.CLEAN, send(readers, own, Coin.BELLSCOIN, ready(a), null, writesTokens = true).state(a))
        assertEquals(CoinState.HELD, send(readers, own, Coin.BELLSCOIN, ready(a),
            mapOf(a to check(Verdict.UNCHECKED, reason = CheckPolicy.MAY_CARRY_RUNES))).state(a))
        val nintondo = SendIndexer(runesAware = false) { asked -> asked.associateWith { IndexerOutput.unknown(it) } }
        assertEquals(CoinState.HELD, send(IndexerReaders { _, _, _, _ -> nintondo }, InscriptionSetting.Public, Coin.BELLSCOIN, ready(a), null).state(a))
    }

    /** Review finding 3: a settings change in the middle of a Send pass closes its reader: no answer, never Clean. */
    @Test
    fun aSettingsChangeMidPassIsNoAnswer(): Unit = runBlocking {
        val a = clean.outpoint
        val b = "dd".repeat(32) + ":1"
        lateinit var readers: IndexerReaders
        lateinit var reader: SendIndexer
        //one request per outpoint, each refused once the reader is closed, as Nintondo's reader does: the settings change
        //while the first one is out
        reader = SendIndexer { asked ->
            asked.associateWith { op ->
                if(reader.closed) throw IndexerException("closed")
                if(op == a) readers.clear()
                nothing(op)
            }
        }
        readers = IndexerReaders { _, _, _, _ -> reader }
        val answers = readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a), utxo(b)), false)
        assertNull(answers.of(a))
        assertNull(answers.of(b))
        assertTrue(reader.closed)
    }

    /** Review finding 4: a failed open is remembered for a minute, so a dead indexer is not reopened on every Send. */
    @Test
    fun aFailedOpenIsRememberedForAMinute(): Unit = runBlocking {
        val a = clean.outpoint
        var clock = 0L
        var opens = 0
        val readers = IndexerReaders(now = { clock }) { _, _, _, _ -> opens++; throw java.io.IOException("timed out") }
        repeat(3) { assertNull(readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false).of(a)) }
        assertEquals(1, opens)
        clock += IndexerReaders.FAILED_OPEN_MS + 1
        readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        assertEquals(2, opens)
        //a settings change forgets it
        readers.clear()
        readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        assertEquals(3, opens)
    }

    /** Review finding 4: a Send pass has one overall deadline; past it the rest is no answer and the reader is dropped. */
    @Test
    fun aSendPassHasADeadline(): Unit = runBlocking {
        val a = clean.outpoint
        val slow = SendIndexer { kotlinx.coroutines.awaitCancellation() }
        val readers = IndexerReaders(passDeadlineMs = 200) { _, _, _, _ -> slow }
        val answers = kotlinx.coroutines.withTimeout(10_000) {
            readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        }
        assertNull(answers.of(a))
        assertTrue(slow.closed)
    }

    /** Review finding 4: opening one indexer does not hold up another (a lock per reader, not one for all). */
    @Test
    fun readersForDifferentIndexersDoNotWaitOnEachOther(): Unit = runBlocking {
        val a = clean.outpoint
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val readers = IndexerReaders { url, _, _, _ ->
            if(url.contains("slow")) gate.await()
            SendIndexer { asked -> asked.associateWith { nothing(it) } }
        }
        val slow = async(kotlinx.coroutines.Dispatchers.Default) {
            readers.answers(InscriptionSetting.YourOwn("https://slow.example"), Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        }
        kotlinx.coroutines.delay(100)
        val fast = kotlinx.coroutines.withTimeout(10_000) {
            readers.answers(InscriptionSetting.YourOwn("https://fast.example"), Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        }
        assertEquals(false, fast.of(a))
        gate.complete(Unit)
        assertEquals(false, slow.await().of(a))
    }

    /** Review finding 8: Send's path asks no indexer on Built-in, before the notice is answered, or air-gapped. */
    @Test
    fun sendWiringAsksNothingOnBuiltIn(): Unit = runBlocking {
        val a = clean.outpoint
        var opens = 0
        val readers = IndexerReaders { _, _, _, _ -> opens++; SendIndexer { error("asked") } }
        val unanswered = InscriptionSetting.parse(null, hasPublic = true, defaultChoice = null)
        assertEquals(CoinState.HELD, send(readers, InscriptionSetting.BuiltIn, Coin.LITECOIN, ready(a), null).state(a))
        assertEquals(CoinState.HELD, send(readers, unanswered, Coin.LITECOIN, ready(a), null).state(a))
        assertEquals(CoinState.HELD, send(readers, InscriptionSetting.Public, Coin.LITECOIN, ready(a), null, airGapped = true).state(a))
        assertEquals(0, opens)
    }

    /** Review finding 8: Send opens its reader with the coin's proxy, which comes from the coin's server settings. */
    @Test
    fun sendUsesTheCoinsProxy(): Unit = runBlocking {
        val a = clean.outpoint
        val tor = com.sparrowwallet.mobile.electrum.SocksProxy("127.0.0.1", 9050)
        val seen = ArrayList<com.sparrowwallet.mobile.electrum.SocksProxy?>()
        val readers = IndexerReaders { _, _, _, proxy -> seen.add(proxy); SendIndexer { asked -> asked.associateWith { nothing(it) } } }
        send(readers, InscriptionSetting.Public, Coin.LITECOIN, ready(a), null, proxy = tor)
        assertEquals(listOf<com.sparrowwallet.mobile.electrum.SocksProxy?>(tor), seen)
        val server = WalletRepository.ServerSettings("h", 1, true, false, true, "127.0.0.1", 9050)
        assertEquals(tor, indexerProxy(server))
        assertNull(indexerProxy(server.copy(useProxy = false)))
        assertNull(indexerProxy(null))
    }

    /** Review finding 8: saving the inscription setting, the notice's answer or a coin's server settings clears the readers. */
    @Test
    fun settingChangesClearTheReaders(): Unit = runBlocking {
        val a = clean.outpoint
        val made = ArrayList<SendIndexer>()
        val readers = IndexerReaders { _, _, _, _ -> SendIndexer { asked -> asked.associateWith { nothing(it) } }.also { made.add(it) } }
        suspend fun open() = readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        val prefs = InscriptionPrefs(FakePrefs()) { readers.clear() }
        open()
        prefs.save(Coin.LITECOIN, Network.MAINNET, InscriptionSetting.BuiltIn)
        assertTrue(made.last().closed, "inscription setting")
        open()
        prefs.saveDefaultChoice(InscriptionSetting.PUBLIC)
        assertTrue(made.last().closed, "notice answer")
        open()
        val raw = FakePrefs()
        writeServerSettings(raw, Coin.LITECOIN, Network.MAINNET, WalletRepository.ServerSettings("h", 1, true, false, true, "127.0.0.1", 9050)) { readers.clear() }
        assertTrue(made.last().closed, "server settings")
        assertEquals(true, raw.getBoolean("${ServerKeys.prefix(Coin.LITECOIN, Network.MAINNET)}.useProxy", false))
        assertEquals(3, made.size)
    }

    /**
     * Re-review M1: a pass that read the setting before a settings change never opens or asks the old indexer after it:
     * one pass opening (and holding its key's lock) and one waiting on that lock both give no answer, the reader opened
     * across the change is closed and not kept, and nothing is asked.
     */
    @Test
    fun aPassThatStartedBeforeASettingsChangeAsksNothingAfterIt(): Unit = runBlocking {
        val a = clean.outpoint
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val made = ArrayList<SendIndexer>()
        val readers = IndexerReaders { _, _, _, _ ->
            entered.complete(Unit)
            gate.await()
            SendIndexer { asked -> asked.associateWith { nothing(it) } }.also { made.add(it) }
        }
        suspend fun pass() = readers.answers(InscriptionSetting.Public, Coin.LITECOIN, Network.MAINNET, null, listOf(utxo(a)), false)
        val opening = async(kotlinx.coroutines.Dispatchers.Default) { pass() }
        entered.await()
        val waiting = async(kotlinx.coroutines.Dispatchers.Default) { pass() }
        kotlinx.coroutines.delay(100)
        readers.clear()
        gate.complete(Unit)
        assertNull(opening.await().of(a))
        assertNull(waiting.await().of(a))
        assertEquals(1, made.size, "the waiting pass opened nothing")
        assertTrue(made.single().closed)
        assertEquals(0, made.single().asked)
        //the next pass, after the change, opens afresh
        assertEquals(false, pass().of(a))
        assertEquals(2, made.size)
    }

    /** Re-review M1: a check run's reader opened before a settings change is not lent: it is closed at once. */
    @Test
    fun aReaderOpenedBeforeASettingsChangeIsNotLent() {
        val readers = IndexerReaders()
        val before = readers.generation
        val reader = SendIndexer { emptyMap() }
        readers.clear()
        assertFalse(readers.lend(reader, before))
        assertTrue(reader.closed)
        val fresh = SendIndexer { emptyMap() }
        assertTrue(readers.lend(fresh, readers.generation))
        readers.clear()
        assertTrue(fresh.closed, "a lent reader is closed by the next change")
    }

    /**
     * Re-review M2: the Send pass deadline is real and covers the open. An indexer that accepts and never replies (to the
     * open's status request, or to the outputs request) gives no answer a little after the deadline, not at the socket
     * timeout.
     */
    @Test
    fun aHungIndexerEndsTheSendPassAtItsDeadline(): Unit = runBlocking {
        val a = clean.outpoint
        //accepts connections (the backlog) and never replies
        java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            val readers = IndexerReaders(passDeadlineMs = 1_000)
            val started = System.nanoTime()
            val answers = readers.answers(InscriptionSetting.YourOwn("http://127.0.0.1:${silent.localPort}"), Coin.LITECOIN,
                Network.MAINNET, null, listOf(utxo(a)), false)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertNull(answers.of(a))
            assertTrue(tookMs < 6_000, "open: took $tookMs ms")
        }

        //answers its status and block 0, then never replies to the outputs request
        val server = TinyHttp.ord { _, _, _ -> null }
        try {
            val readers = IndexerReaders(passDeadlineMs = 1_000)
            val started = System.nanoTime()
            val answers = readers.answers(InscriptionSetting.YourOwn(server.url), Coin.LITECOIN,
                Network.MAINNET, null, listOf(utxo(a)), false)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertNull(answers.of(a))
            assertTrue(tookMs < 6_000, "outputs: took $tookMs ms")
        } finally {
            server.close()
        }
    }
}
