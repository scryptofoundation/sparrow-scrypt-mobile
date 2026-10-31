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
