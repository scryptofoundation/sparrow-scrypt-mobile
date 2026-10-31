package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Addresses
import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.ElectrumSeed
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.storage.VaultData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** First receive addresses are desktop's SeedWalletsTest vectors (computed outside drongo) for "abandon ... about". */
class OtherWalletsTest {
    private val abandon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val electrumPhrase = "coach fan denial rifle frost rival join install one wasp cool antique"

    private fun app(name: String) = OtherWallets.ALL.first { it.name == name }

    private fun vaults(appName: String, coin: Coin, index: Int = 0, phrase: String = abandon, includeMweb: Boolean = false,
                       userPassphrase: String = ""): List<OtherWallets.RestoreVault> {
        val app = app(appName)
        return OtherWallets.restoreVaults(app, app.accountsFor(coin)[index], phrase, "W", includeMweb, userPassphrase)
    }

    private fun receive0(data: VaultData): String {
        val seed = if(data.seedType == "ELECTRUM") ElectrumSeed.toSeed(data.mnemonic, data.passphrase) else Bip39.seed(data.mnemonic, data.passphrase)
        val key = HDKey.fromSeed(seed).derivePath(data.derivationPath).deriveChild(0).deriveChild(0)
        return Addresses.forKey(key.pubKey, walletScriptType(data.scriptType), ChainParams.of(Coin.fromId(data.coin), Network.fromId(data.network)))
    }

    private fun first(appName: String, coin: Coin, index: Int = 0) = receive0(vaults(appName, coin, index).first().data)

    @Test
    fun firstAddressesMatchDesktop() {
        assertEquals("bel1qh07eu8daq4xvgefnq567dfrv8dssxm0ruw2q2a", first("Nintondo", Coin.BELLSCOIN, 0))
        assertEquals("BMb5oFUiW5cYqA4zfEtyQiZgW884vg1vKT", first("Nintondo", Coin.BELLSCOIN, 1))
        assertEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", first("MyDoge", Coin.DOGECOIN))
        assertEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", first("Dingo browser wallet", Coin.DINGOCOIN))
        assertEquals("PsGyoVVSD9VTYoDZGp5FmRB2BSoE69f9ZV", first("Peppool", Coin.PEPECOIN))
        assertEquals("7p8H8sXPri7bdWG1N9YVuLjnXJ8og7tWmr", first("JunkieWally", Coin.JUNKCOIN))
        assertEquals("LACv4L1XCLC6yw56YMFaZA8fVH7iw66bH6", first("luckycoinwallet", Coin.LUCKYCOIN))
        assertEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", first("Cake Wallet", Coin.DOGECOIN))
        assertEquals("ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh", first("Cake Wallet", Coin.LITECOIN))
    }

    @Test
    fun hiddenPassphraseIsAppliedNotShown() {
        assertEquals("junk", vaults("JunkieWally", Coin.JUNKCOIN).first().data.passphrase)
        assertEquals("bells", vaults("Nintondo", Coin.BELLSCOIN).first().data.passphrase)
        assertEquals("lky", vaults("luckycoinwallet", Coin.LUCKYCOIN).first().data.passphrase)
        assertEquals("", vaults("MyDoge", Coin.DOGECOIN).first().data.passphrase)
        //the screen shows each app's help; it names that a passphrase is added, never the passphrase
        for(app in OtherWallets.ALL) for(account in app.accounts.filter { it.hiddenPassphrase.isNotEmpty() }) {
            assertTrue("\"${account.hiddenPassphrase}\"" !in app.help, app.name)
        }
    }

    @Test
    fun hiddenPassphraseRefusesAnother() {
        assertFailsWith<IllegalArgumentException> { vaults("luckycoinwallet", Coin.LUCKYCOIN, userPassphrase = "mine") }
    }

    @Test
    fun cakeChecksElectrumFirst() {
        val data = vaults("Cake Wallet", Coin.LITECOIN, phrase = electrumPhrase).first().data
        assertEquals("ELECTRUM", data.seedType)
        assertEquals("m/0'", data.derivationPath)
        assertEquals("P2WPKH", data.scriptType)
    }

    @Test
    fun otherAppsRefuseElectrumPhrases() {
        assertFailsWith<IllegalArgumentException> { vaults("MyDoge", Coin.DOGECOIN, phrase = electrumPhrase) }
        assertFailsWith<IllegalArgumentException> { vaults("Cake Wallet", Coin.DOGECOIN, phrase = electrumPhrase) }
        assertFailsWith<IllegalArgumentException> { vaults("MyDoge", Coin.DOGECOIN, phrase = "abandon abandon abandon") }
    }

    @Test
    fun cakeLitecoinAddsALinkedMwebVault() {
        val pair = vaults("Cake Wallet", Coin.LITECOIN, includeMweb = true)
        assertEquals(2, pair.size)
        assertEquals("W", pair[0].name)
        assertEquals("W Private", pair[1].name)
        val mweb = pair[1].data
        assertEquals("MWEB", mweb.scriptType)
        assertEquals("m/1000'", mweb.derivationPath)
        assertEquals("litecoin", mweb.coin)
        assertEquals("", mweb.passphrase)
        assertEquals(pair[0].data.mnemonic, mweb.mnemonic)
        assertEquals(pair[0].data.seedType, mweb.seedType)
        assertEquals(1, vaults("Cake Wallet", Coin.LITECOIN, includeMweb = false).size)
        assertEquals(1, vaults("Cake Wallet", Coin.DOGECOIN, includeMweb = true).size)
    }

    @Test
    fun everyRowIsSupportedByItsCoin() {
        for(app in OtherWallets.ALL) for(account in app.accounts) {
            assertTrue(account.coin.supports(account.scriptType), "${app.name} ${account.coin}")
        }
    }

    @Test
    fun vaultsCarryTheirCoin() {
        for(app in OtherWallets.ALL) for(account in app.accounts) {
            val data = OtherWallets.restoreVaults(app, account, abandon, "W", includeMweb = false).first().data
            assertEquals(account.coin.id, data.coin)
            assertEquals("mainnet", data.network)
            assertEquals(account.derivationPath, data.derivationPath)
        }
    }
}
