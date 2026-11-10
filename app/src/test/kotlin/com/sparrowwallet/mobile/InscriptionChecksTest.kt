package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.track.Piece
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.inscriptions.CheckPolicy
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
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

    /** The indexer's answer decides; without one (off, unknown or unreachable) the local rule holds the coin. */
    @Test
    fun indexerAnswerDecides() {
        assertTrue(heldFrom(serverAnswer = true) { false })
        assertFalse(heldFrom(serverAnswer = false) { true })
        assertTrue(heldFrom(serverAnswer = null) { true })
        assertFalse(heldFrom(serverAnswer = null) { false })
    }

    /** Nintondo indexes inscriptions only: its "clean" cannot clear a coin made by a runestone or dunestone transaction. */
    @Test
    fun inscriptionOnlyIndexerCannotClearTokenCoins() {
        assertTrue(heldFrom(serverAnswer = false, tokensIndexed = false, writesTokens = { true }) { false })
        assertFalse(heldFrom(serverAnswer = false, tokensIndexed = false, writesTokens = { false }) { true })
        assertFalse(heldFrom(serverAnswer = false, tokensIndexed = true, writesTokens = { true }) { true })
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

    @Test
    fun cleanCheckFreesAPostageValuedCoin() {
        assertEquals(CoinState.CLEAN, coinState(clean, indexerSaysCarries = null, localMayCarry = true, hasAnyResults = true))
        assertEquals(CoinState.CLEAN, coinState(clean, indexerSaysCarries = false, localMayCarry = true, hasAnyResults = true))
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

    @Test
    fun localRuleIsAskedOnlyWithoutResults() {
        var asked = 0
        coinState(clean, null, { asked++; true }, hasAnyResults = true)
        coinState(null, null, { asked++; true }, hasAnyResults = true)
        coinState(null, false, { asked++; true }, hasAnyResults = false)
        assertEquals(0, asked)
        coinState(null, null, { asked++; true }, hasAnyResults = false)
        assertEquals(1, asked)
    }

    /** heldFrom's Nintondo caveat survives a Clean check: an inscriptions-only indexer cannot clear a token coin. */
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
    }
}
