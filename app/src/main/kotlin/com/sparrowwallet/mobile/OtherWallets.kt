package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.ElectrumSeed
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.storage.VaultData

/**
 * Recovery-phrase restores for the coins' own wallet apps, with the path and any built-in BIP39
 * passphrase each app applies, so a phrase from one of them gives the same addresses here without
 * the user knowing either. The rows are desktop's io/SeedWallets (checked against each app's source,
 * 2026-10-28) plus Cake Wallet (io/CakeWallet). None of these apps uses a change chain; the phone
 * does, as desktop does.
 */
object OtherWallets {
    data class Account(
        val coin: Coin,
        val scriptType: ScriptType,
        val derivationPath: String,
        /** Applied without being shown; an account with one refuses any other passphrase. */
        val hiddenPassphrase: String,
        val label: String
    )

    data class App(
        val name: String,
        val help: String,
        val accounts: List<Account>,
        /** Cake Wallet makes Electrum seeds for Litecoin as well as BIP39 phrases. */
        val electrumSeeds: Boolean = false,
        /** Cake Wallet's Litecoin MWEB balance comes from the same seed at m/1000'. */
        val mwebOption: Boolean = false
    ) {
        fun coins(): List<Coin> = accounts.map { it.coin }.distinct()
        fun accountsFor(coin: Coin): List<Account> = accounts.filter { it.coin == coin }
    }

    /** A vault to seal under [name]; a second one in a list is the first's linked MWEB wallet. */
    data class RestoreVault(val name: String, val data: VaultData) {
        /** The name and the vault's printed form ([VaultData.toString] holds no secret). */
        override fun toString(): String = "RestoreVault(name=$name, data=$data)"
    }

    private const val CHANGE_NOTE = "Change from sends on this phone goes to a change address, which that app does not show."

    val ALL: List<App> = listOf(
        App("Dingo browser wallet", "Restores the Dingo browser wallet's Legacy (D) addresses. $CHANGE_NOTE",
            listOf(Account(Coin.DINGOCOIN, ScriptType.P2PKH, "m/44'/3'/0'", "", "Legacy"))),
        App("JunkieWally", "Adds JunkieWally's built-in passphrase for you and restores its Legacy (7) addresses. $CHANGE_NOTE",
            listOf(Account(Coin.JUNKCOIN, ScriptType.P2PKH, "m/44'/0'/0'", "junk", "Legacy"))),
        App("luckycoinwallet", "Adds luckycoinwallet's built-in passphrase for you and restores its Legacy (L) addresses. $CHANGE_NOTE",
            listOf(Account(Coin.LUCKYCOIN, ScriptType.P2PKH, "m/44'/0'/0'", "lky", "Legacy"))),
        App("MyDoge", "Restores MyDoge's Legacy (D) addresses. $CHANGE_NOTE",
            listOf(Account(Coin.DOGECOIN, ScriptType.P2PKH, "m/44'/3'/0'", "", "Legacy"))),
        //Nintondo derives every address type from the same m/44'/0'/0' keys; Native Segwit is its default
        App("Nintondo", "Adds Nintondo's built-in passphrase for you. Pick the address type the Nintondo wallet uses: " +
            "Native Segwit (bel1q) is its default, Taproot (bel1p) and Legacy (B) the others. $CHANGE_NOTE",
            listOf(Account(Coin.BELLSCOIN, ScriptType.P2WPKH, "m/44'/0'/0'", "bells", "Native Segwit (default)"),
                Account(Coin.BELLSCOIN, ScriptType.P2PKH, "m/44'/0'/0'", "bells", "Legacy"),
                Account(Coin.BELLSCOIN, ScriptType.P2TR, "m/44'/0'/0'", "bells", "Taproot"))),
        App("Peppool", "Restores the first Peppool account's Legacy (P) address. Peppool keeps each further account " +
            "at its own path; only the first is restored here. $CHANGE_NOTE",
            listOf(Account(Coin.PEPECOIN, ScriptType.P2PKH, "m/44'/3434'/0'", "", "Legacy"))),
        App("Cake Wallet", "Restores Cake Wallet's Litecoin (Native Segwit, BIP39 or Electrum seed, with its MWEB " +
            "balance as a linked private wallet) or Dogecoin (Legacy) wallet. $CHANGE_NOTE",
            listOf(Account(Coin.LITECOIN, ScriptType.P2WPKH, "m/84'/2'/0'", "", "Native Segwit"),
                Account(Coin.DOGECOIN, ScriptType.P2PKH, "m/44'/3'/0'", "", "Legacy")),
            electrumSeeds = true, mwebOption = true)
    )

    /**
     * A reason the vaults can't all be saved under their names, checked before any is sealed: a
     * name the 40-character file limit cuts into another's, or a wallet that already exists.
     */
    fun restoreNamesProblem(vaults: List<RestoreVault>, exists: (String) -> Boolean): String? {
        val files = vaults.map { walletFileName(it.name) }
        if(files.any { it.isEmpty() }) return "Give the wallet a name"
        if(files.toSet().size < files.size) return "Choose a shorter name, so the wallet and its private wallet both fit"
        return files.firstOrNull(exists)?.let { "A wallet named \"$it\" already exists" }
    }

    /**
     * The vaults a restore seals: the public wallet, then for Cake Litecoin with [includeMweb] its MWEB
     * wallet from the same seed. Electrum is checked first for Cake Litecoin, as Cake does: a BIP39
     * phrase passes the Electrum check about 1 in 4096 times, an Electrum phrase BIP39's about 1 in 16.
     */
    fun restoreVaults(app: App, account: Account, mnemonic: String, name: String, includeMweb: Boolean,
                      userPassphrase: String = ""): List<RestoreVault> {
        val phrase = mnemonic.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
        require(account.hiddenPassphrase.isEmpty() || userPassphrase.isEmpty()) {
            "${app.name} adds its own passphrase and does not take another one"
        }
        val electrum = app.electrumSeeds && account.coin == Coin.LITECOIN && ElectrumSeed.isValid(phrase)
        val seedType: String
        val derivation: String
        if(electrum) {
            //ElectrumSeed only recognises segwit seeds, whose path is m/0'
            seedType = "ELECTRUM"
            derivation = "m/0'"
        } else {
            require(Bip39.isValid(phrase)) {
                if(ElectrumSeed.isValid(phrase)) "${app.name} makes BIP39 phrases; this is an Electrum seed"
                else "Not a valid recovery phrase"
            }
            seedType = "BIP39"
            derivation = account.derivationPath
        }
        val public = VaultData(
            mnemonic = phrase,
            passphrase = account.hiddenPassphrase.ifEmpty { userPassphrase },
            seedType = seedType,
            derivationPath = derivation,
            network = Network.MAINNET.id,
            scriptType = account.scriptType.name,
            coin = account.coin.id
        )
        val vaults = mutableListOf(RestoreVault(name, public))
        if(includeMweb && app.mwebOption && account.coin == Coin.LITECOIN) {
            //the same seed, passphrase included, as desktop's same-seed MWEB child wallet
            vaults += RestoreVault("$name Private", public.copy(derivationPath = "m/1000'", scriptType = "MWEB"))
        }
        return vaults
    }
}
