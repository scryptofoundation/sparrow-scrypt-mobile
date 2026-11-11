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

    /** A wallet with a Private and a Taproot wallet, shown on its Taproot layer. */
    private val companionHome = Screen.Home(
        WalletFamily(Member("w", VaultData(mnemonic = "abandon")),
            Member("w Private", VaultData(mnemonic = "abandon", scriptType = "MWEB")),
            Member("w Taproot", VaultData(mnemonic = "abandon", scriptType = "P2TR"))),
        Layer.TAPROOT)

    @Test
    fun backFromACompanionsWalletScreenReturnsToTheCompanionsHome() {
        for(screen in listOf(Screen.Server(companionHome), Screen.Send(companionHome), Screen.Details(companionHome),
            Screen.Receive(companionHome, 0), Screen.SignPsbt(companionHome), Screen.Collectibles(companionHome))) {
            val back = backTarget(screen)
            assertEquals(companionHome, back)
            assertEquals(Layer.TAPROOT, (back as Screen.Home).layer)
            assertEquals("w Taproot", back.name)
        }
        assertEquals(Screen.Home(companionHome.family, Layer.PRIVATE),
            backTarget(Screen.Send(Screen.Home(companionHome.family, Layer.PRIVATE))))
    }

    @Test
    fun backFromAnyLayersHomeReturnsToTheList() {
        for(layer in companionHome.family.layers) assertEquals(Screen.WalletList, backTarget(Screen.Home(companionHome.family, layer)))
    }

    @Test
    fun theCollectiblesVisitBelongsToTheShownWallet() {
        val tile = CollectibleTile("${"a".repeat(64)}i0", "${"b".repeat(64)}:0", "#1", "a".repeat(64), 100, 1_000L, 1, 10_000L,
            com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck.Source.BUILT_IN)
        assertEquals("w Taproot", collectiblesVisit(Screen.Collectibles(companionHome)))
        assertEquals("w Taproot", collectiblesVisit(Screen.Inscription(companionHome, tile)))
        assertEquals(Screen.Collectibles(companionHome), backTarget(Screen.Inscription(companionHome, tile)))
        assertNull(collectiblesVisit(companionHome))
    }

    @Test
    fun backOnTheListLeavesTheApp() {
        assertNull(backTarget(Screen.WalletList))
    }
}
