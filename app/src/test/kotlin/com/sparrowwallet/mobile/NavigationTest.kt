package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.storage.VaultData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationTest {
    private val home = Screen.Home("w", VaultData(mnemonic = "abandon"))

    @Test
    fun backFromAWalletScreenReturnsToItsHome() {
        assertEquals(home, backTarget(Screen.Server(home)))
        assertEquals(home, backTarget(Screen.Send(home)))
        assertEquals(home, backTarget(Screen.Details(home)))
        assertEquals(home, backTarget(Screen.Receive(home, 0)))
    }

    @Test
    fun backFromAnInscriptionReturnsToCollectiblesAndFromCollectiblesToHome() {
        val tile = CollectibleTile("${"a".repeat(64)}i0", "${"b".repeat(64)}:0", "#1", "a".repeat(64), 100, 1_000L, 1, 10_000L,
            com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck.Source.BUILT_IN)
        assertEquals(home, backTarget(Screen.Collectibles(home)))
        assertEquals(Screen.Collectibles(home), backTarget(Screen.Inscription(home, tile)))
    }

    /** The Collectibles visit (grid position, failed previews) lasts across the detail screen, and ends at Home. */
    @Test
    fun theCollectiblesVisitLastsAcrossTheDetailOnly() {
        val tile = CollectibleTile("${"a".repeat(64)}i0", "${"b".repeat(64)}:0", "#1", "a".repeat(64), 100, 1_000L, 1, 10_000L,
            com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck.Source.BUILT_IN)
        assertEquals("w", collectiblesVisit(Screen.Collectibles(home)))
        assertEquals("w", collectiblesVisit(Screen.Inscription(home, tile)))
        assertNull(collectiblesVisit(home))
        assertNull(collectiblesVisit(Screen.Send(home)))
        assertNull(collectiblesVisit(Screen.WalletList))
    }

    @Test
    fun backFromHomeAndSetupScreensReturnsToTheList() {
        assertEquals(Screen.WalletList, backTarget(home))
        assertEquals(Screen.WalletList, backTarget(Screen.Unlock("w")))
        assertEquals(Screen.WalletList, backTarget(Screen.Restore))
        assertEquals(Screen.WalletList, backTarget(Screen.RestoreFromApp))
    }

    @Test
    fun backOnTheListLeavesTheApp() {
        assertNull(backTarget(Screen.WalletList))
    }
}
