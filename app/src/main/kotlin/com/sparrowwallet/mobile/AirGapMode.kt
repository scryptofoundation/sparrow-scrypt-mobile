package com.sparrowwallet.mobile

import android.content.SharedPreferences
import com.sparrowwallet.mobile.crypto.NetworkPolicy

/**
 * Air-gapped mode: the app-wide switch that keeps the phone offline as a QR signer.
 * The flag lives in the "settings" prefs and [NetworkPolicy] enforces it; [load] must
 * run before any network code, in the activity and in the background worker alike.
 */
object AirGapMode {
    const val PREF_KEY = "airgapped"

    sealed interface LeaveResult {
        data object Left : LeaveResult
        data class WrongPassword(val wallet: String) : LeaveResult
        data class MissingPassword(val wallet: String) : LeaveResult
        /** Companions their parent's password did not open: each needs its own. */
        data class OwnPasswordNeeded(val wallets: List<String>) : LeaveResult
    }

    fun load(prefs: SharedPreferences) {
        NetworkPolicy.blocked = prefs.getBoolean(PREF_KEY, false)
    }

    /** Blocks the network first, then stops what is already running. */
    fun enter(prefs: SharedPreferences, stopMwebd: () -> Unit = MwebdService::stopAll, cancelWork: () -> Unit) {
        NetworkPolicy.blocked = true
        prefs.edit().putBoolean(PREF_KEY, true).commit() // commit: must survive an immediate process death
        cancelWork()
        stopMwebd()
    }

    /**
     * The wallets "Go back online?" asks a password for, in list order: each wallet with its own row on the wallet list,
     * and each companion named in [ownPassword] (one its parent's password did not open, see [leave]). A companion hidden
     * under its parent ([companionParent]) is otherwise opened with its parent's password and gets no field.
     */
    fun passwordFields(wallets: List<WalletRepository.WalletInfo>, ownPassword: Set<String>): List<String> =
        wallets.filter { companionParent(it, wallets) == null || it.name in ownPassword }.map { it.name }

    /**
     * Goes back online only when every one of [wallets] (every wallet on the phone, companions included) has had its own
     * vault opened by [unlock] (which throws on a wrong password) with a password from [passwords]. Nothing changes
     * unless all of them open. The wallets with their own row go first, each with its own password. Then each companion:
     * with its own password when one was typed for it, else with its parent's; one that its parent's password does not
     * open is reported in [LeaveResult.OwnPasswordNeeded] (all such at once) for the dialog to ask for its own.
     */
    fun leave(
        prefs: SharedPreferences,
        wallets: List<WalletRepository.WalletInfo>,
        passwords: Map<String, String>,
        unlock: (String, String) -> Unit
    ): LeaveResult {
        fun opens(wallet: String, password: String): Boolean =
            try { unlock(wallet, password); true } catch(e: Exception) { false }
        val parents = wallets.associate { it.name to companionParent(it, wallets) }
        for(wallet in wallets.filter { parents[it.name] == null }) {
            val password = passwords[wallet.name] ?: return LeaveResult.MissingPassword(wallet.name)
            if(!opens(wallet.name, password)) return LeaveResult.WrongPassword(wallet.name)
        }
        val needed = mutableListOf<String>()
        for(wallet in wallets) {
            val parent = parents[wallet.name] ?: continue
            val own = passwords[wallet.name]
            when {
                own != null -> if(!opens(wallet.name, own)) return LeaveResult.WrongPassword(wallet.name)
                // its parent (never itself a companion) opened above with this password
                passwords[parent.name]?.let { opens(wallet.name, it) } != true -> needed += wallet.name
            }
        }
        if(needed.isNotEmpty()) return LeaveResult.OwnPasswordNeeded(needed)
        prefs.edit().putBoolean(PREF_KEY, false).commit()
        NetworkPolicy.blocked = false
        return LeaveResult.Left
    }
}
