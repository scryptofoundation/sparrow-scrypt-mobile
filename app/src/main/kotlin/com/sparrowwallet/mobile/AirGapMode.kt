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
     * Goes back online only when every wallet's password unlocks it ([unlock] throws on a
     * wrong one). Nothing changes unless all of them succeed.
     */
    fun leave(
        prefs: SharedPreferences,
        wallets: List<String>,
        passwords: Map<String, String>,
        unlock: (String, String) -> Unit
    ): LeaveResult {
        for(wallet in wallets) {
            val password = passwords[wallet] ?: return LeaveResult.MissingPassword(wallet)
            try {
                unlock(wallet, password)
            } catch(e: Exception) {
                return LeaveResult.WrongPassword(wallet)
            }
        }
        prefs.edit().putBoolean(PREF_KEY, false).commit()
        NetworkPolicy.blocked = false
        return LeaveResult.Left
    }
}
