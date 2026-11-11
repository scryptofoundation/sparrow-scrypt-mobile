package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.storage.VaultData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** What holds seed or key material prints none of it. */
class PrintedFormTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val passphrase = "my-secret-passphrase"
    private val scanSecret = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val xpub = "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"

    private fun assertClean(printed: String) {
        for(secret in listOf("abandon", "about", passphrase, scanSecret, xpub, "lunch-label")) {
            assertFalse(secret in printed, "\"$secret\" in $printed")
        }
    }

    private val vault = VaultData(words, passphrase, txLabels = mapOf("tx" to "lunch-label"))

    @Test fun restoreVaultPrintsNoSeed() {
        assertClean(OtherWallets.RestoreVault("Cake", vault).toString())
        assertEquals(OtherWallets.RestoreVault("Cake", vault), OtherWallets.RestoreVault("Cake", vault.copy()))
    }

    @Test fun syncCredentialsPrintNoViewKeys() {
        val creds = SyncCredentials("w", "mainnet", "MWEB", xpub = xpub, scanSecretHex = scanSecret, knownTxids = listOf("lunch-label"))
        assertClean(creds.toString())
        assertEquals("SyncCredentials(name=w, scriptType=MWEB, coin=litecoin, network=mainnet)", creds.toString())
    }

    @Test fun mwebInfoPrintsNoScanSecret() {
        assertClean(WalletRepository.MwebInfo("ltcmweb1addr", scanSecret, "02abcdef").toString())
    }

    @Test fun createScreenPrintsNoSeedWords() {
        assertClean(Screen.Create(words).toString())
        assertEquals(Screen.Create(words), Screen.Create(words))
    }

    @Test fun membersAndHomesPrintNoSeed() {
        val home = Screen.Home("w", vault, "the-password")
        assertClean(home.toString())
        assertFalse("the-password" in home.toString())
        assertClean(Screen.Send(home).toString())
    }
}
