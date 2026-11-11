package com.sparrowwallet.mobile.storage

import com.sparrowwallet.mobile.crypto.Coin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What holds seed or key material prints none of it. */
class PrintedFormTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val passphrase = "my-secret-passphrase"
    private val xprv = "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"

    private fun assertClean(printed: String) {
        for(secret in listOf("abandon", "about", passphrase, xprv, "lunch-label", "deadbeef")) {
            assertFalse(secret in printed, "\"$secret\" in $printed")
        }
    }

    @Test fun vaultDataPrintsItsKindCoinNetworkAndPathOnly() {
        val data = VaultData(words, passphrase, "BIP39", "m/86'/2'/0'", "testnet", "P2TR",
            listOf(MwebHistoryEntry("deadbeef", 1, 2, 3, "lunch-label")), mapOf("deadbeef" to "lunch-label"), "litecoin")
        val printed = data.toString()
        assertClean(printed)
        assertEquals("VaultData(scriptType=P2TR, coin=litecoin, network=testnet, derivationPath=m/86'/2'/0')", printed)
    }

    @Test fun vaultDataKeepsItsEqualityAndStoredForm() {
        val data = VaultData(words, passphrase, txLabels = mapOf("deadbeef" to "lunch-label"))
        assertEquals(data, data.copy())
        assertFalse(data == data.copy(passphrase = ""))
        assertEquals(data, WalletVault.open(WalletVault.seal(data, "pw", iterations = 1), "pw"))
    }

    @Test fun importedWalletPrintsNoSeed() {
        val imported = SparrowImport.ImportedWallet("w", "mainnet", "P2WPKH", "m/84'/2'/0'", "deadbeef", xprv,
            "BIP39", words, false, emptyList(), mapOf("deadbeef" to "lunch-label"), Coin.LITECOIN)
        assertClean(imported.toString())
        assertTrue("P2WPKH" in imported.toString())
    }

    @Test fun pairingPrintsNoSessionKey() {
        val key = ByteArray(32) { 0x5a }
        val printed = SparrowLink.Pairing("push", listOf("192.168.1.2"), 4567, key).toString()
        assertFalse(key.contentToString() in printed)
        assertFalse("90" in printed) // 0x5a, as a data class prints a ByteArray
        assertEquals("Pairing(mode=push, hosts=[192.168.1.2], port=4567)", printed)
    }
}
