package com.sparrowwallet.mobile

import android.content.SharedPreferences
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.storage.VaultException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AirGapModeTest {
    private val prefs = FakePrefs()

    @AfterTest fun reset() { NetworkPolicy.blocked = false }

    private val okOnly: (String, String) -> Unit = { _, pw -> if(pw != "ok") throw VaultException("wrong") }

    /** Wallets on their own, without links. */
    private fun ws(vararg names: String) = names.map { info(it, "P2WPKH") }

    private fun info(name: String, scriptType: String, links: WalletLinks = WalletLinks()) =
        WalletRepository.WalletInfo(name, Network.MAINNET, scriptType, links, Coin.LITECOIN)

    /** Main with its Private and Taproot wallets linked both ways, and Solo on its own. */
    private val family = listOf(
        info("Main", "P2WPKH", WalletLinks(mweb = "Main Private", taproot = "Main Taproot")),
        info("Main Private", "MWEB", WalletLinks(parent = "Main")),
        info("Main Taproot", "P2TR", WalletLinks(parent = "Main")),
        info("Solo", "P2PKH")
    )

    /** An unlock that opens a vault only with its own password from [vaultPasswords], recording what it opened. */
    private class Vaults(val vaultPasswords: Map<String, String>) {
        val opened = mutableSetOf<String>()
        val unlock: (String, String) -> Unit = { name, pw ->
            if(vaultPasswords[name] != pw) throw VaultException("wrong")
            opened += name
        }
    }

    private val allOk = mapOf("Main" to "ok", "Main Private" to "ok", "Main Taproot" to "ok", "Solo" to "ok")

    @Test fun companionsGetNoFieldUntilTheirParentsPasswordFails() {
        assertEquals(listOf("Main", "Solo"), AirGapMode.passwordFields(family, emptySet()))
        assertEquals(listOf("Main", "Main Taproot", "Solo"), AirGapMode.passwordFields(family, setOf("Main Taproot")))
        // a wallet with its own row always has its field
        assertEquals(listOf("Main", "Solo"), AirGapMode.passwordFields(family, setOf("Solo")))
    }

    @Test fun companionsOpenWithTheirParentsPassword() {
        AirGapMode.enter(prefs, {}, {})
        val vaults = Vaults(allOk)
        val r = AirGapMode.leave(prefs, family, mapOf("Main" to "ok", "Solo" to "ok"), vaults.unlock)
        assertEquals(AirGapMode.LeaveResult.Left, r)
        // every wallet on the phone had its own vault opened, companions included
        assertEquals(family.map { it.name }.toSet(), vaults.opened)
        assertFalse(NetworkPolicy.blocked)
    }

    @Test fun aCompanionWithItsOwnPasswordIsAskedForByName() {
        AirGapMode.enter(prefs, {}, {})
        val vaults = Vaults(allOk + ("Main Taproot" to "tap"))
        val typed = mapOf("Main" to "ok", "Solo" to "ok")
        assertEquals(AirGapMode.LeaveResult.OwnPasswordNeeded(listOf("Main Taproot")), AirGapMode.leave(prefs, family, typed, vaults.unlock))
        assertTrue(NetworkPolicy.blocked)
        assertTrue(prefs.getBoolean(AirGapMode.PREF_KEY, false))

        assertEquals(AirGapMode.LeaveResult.WrongPassword("Main Taproot"),
            AirGapMode.leave(prefs, family, typed + ("Main Taproot" to "ok"), vaults.unlock))
        assertTrue(NetworkPolicy.blocked)

        vaults.opened.clear()
        assertEquals(AirGapMode.LeaveResult.Left, AirGapMode.leave(prefs, family, typed + ("Main Taproot" to "tap"), vaults.unlock))
        assertEquals(family.map { it.name }.toSet(), vaults.opened)
        assertFalse(NetworkPolicy.blocked)
    }

    @Test fun everyCompanionItsParentsPasswordMissesIsListedAtOnce() {
        AirGapMode.enter(prefs, {}, {})
        val vaults = Vaults(mapOf("Main" to "ok", "Main Private" to "p", "Main Taproot" to "t", "Solo" to "ok"))
        assertEquals(AirGapMode.LeaveResult.OwnPasswordNeeded(listOf("Main Private", "Main Taproot")),
            AirGapMode.leave(prefs, family, mapOf("Main" to "ok", "Solo" to "ok"), vaults.unlock))
        assertTrue(NetworkPolicy.blocked)
    }

    @Test fun aWrongParentPasswordReportsTheParent() {
        AirGapMode.enter(prefs, {}, {})
        val r = AirGapMode.leave(prefs, family, mapOf("Main" to "bad", "Solo" to "ok"), Vaults(allOk).unlock)
        assertEquals(AirGapMode.LeaveResult.WrongPassword("Main"), r)
        assertTrue(NetworkPolicy.blocked)
        assertEquals(AirGapMode.LeaveResult.MissingPassword("Main"),
            AirGapMode.leave(prefs, family, mapOf("Solo" to "ok"), Vaults(allOk).unlock))
    }

    @Test fun aOneSidedTaprootLinkIsAskedOnItsOwn() {
        // the parent does not name it: it has its own row, so its own field, and never the parent's password
        val wallets = listOf(
            info("Main", "P2WPKH"),
            info("Main Taproot", "P2TR", WalletLinks(parent = "Main"))
        )
        assertEquals(listOf("Main", "Main Taproot"), AirGapMode.passwordFields(wallets, emptySet()))
        AirGapMode.enter(prefs, {}, {})
        val vaults = Vaults(mapOf("Main" to "ok", "Main Taproot" to "ok"))
        assertEquals(AirGapMode.LeaveResult.MissingPassword("Main Taproot"),
            AirGapMode.leave(prefs, wallets, mapOf("Main" to "ok"), vaults.unlock))
        assertTrue(NetworkPolicy.blocked)
    }

    @Test fun aCompanionWhoseParentIsGoneIsAskedOnItsOwn() {
        val wallets = listOf(info("Main Private", "MWEB", WalletLinks(parent = "Main")), info("Solo", "P2PKH"))
        assertEquals(listOf("Main Private", "Solo"), AirGapMode.passwordFields(wallets, emptySet()))
        AirGapMode.enter(prefs, {}, {})
        assertEquals(AirGapMode.LeaveResult.MissingPassword("Main Private"),
            AirGapMode.leave(prefs, wallets, mapOf("Solo" to "ok"), Vaults(allOk).unlock))
    }

    @Test fun anOlderPrivatePairsMwebWalletOpensWithItsParentsPassword() {
        // written on the public side only: still the parent's companion, as the list and Home treat it
        val wallets = listOf(info("Main", "P2WPKH", WalletLinks(mweb = "Main Private")), info("Main Private", "MWEB"))
        assertEquals(listOf("Main"), AirGapMode.passwordFields(wallets, emptySet()))
        AirGapMode.enter(prefs, {}, {})
        val vaults = Vaults(allOk)
        assertEquals(AirGapMode.LeaveResult.Left, AirGapMode.leave(prefs, wallets, mapOf("Main" to "ok"), vaults.unlock))
        assertEquals(setOf("Main", "Main Private"), vaults.opened)
    }

    @Test fun loadAppliesPersistedFlag() {
        prefs.edit().putBoolean(AirGapMode.PREF_KEY, true).commit()
        AirGapMode.load(prefs)
        assertTrue(NetworkPolicy.blocked)
        prefs.edit().putBoolean(AirGapMode.PREF_KEY, false).commit()
        AirGapMode.load(prefs)
        assertFalse(NetworkPolicy.blocked)
    }

    @Test fun enterStopsMwebdAndCancelsWork() {
        val calls = mutableListOf<String>()
        AirGapMode.enter(prefs, stopMwebd = { calls += "mwebd" }, cancelWork = { calls += "work" })
        assertTrue(NetworkPolicy.blocked)
        assertTrue(prefs.getBoolean(AirGapMode.PREF_KEY, false))
        assertEquals(listOf("work", "mwebd"), calls)
    }

    @Test fun leaveRefusesOnAnyWrongPassword() {
        AirGapMode.enter(prefs, {}, {})
        val r = AirGapMode.leave(prefs, ws("A", "B"), mapOf("A" to "ok", "B" to "bad"), okOnly)
        assertEquals(AirGapMode.LeaveResult.WrongPassword("B"), r)
        assertTrue(NetworkPolicy.blocked)
        assertTrue(prefs.getBoolean(AirGapMode.PREF_KEY, false))
    }

    @Test fun leaveWithAllPasswordsClearsMode() {
        AirGapMode.enter(prefs, {}, {})
        val r = AirGapMode.leave(prefs, ws("A", "B"), mapOf("A" to "ok", "B" to "ok"), okOnly)
        assertEquals(AirGapMode.LeaveResult.Left, r)
        assertFalse(NetworkPolicy.blocked)
        assertFalse(prefs.getBoolean(AirGapMode.PREF_KEY, true))
    }

    @Test fun leaveWithNoWalletsNeedsNoPassword() {
        AirGapMode.enter(prefs, {}, {})
        assertEquals(AirGapMode.LeaveResult.Left, AirGapMode.leave(prefs, emptyList(), emptyMap(), okOnly))
        assertFalse(NetworkPolicy.blocked)
    }

    @Test fun leaveReportsMissingPassword() {
        AirGapMode.enter(prefs, {}, {})
        val r = AirGapMode.leave(prefs, ws("A", "B"), mapOf("A" to "ok"), okOnly)
        assertEquals(AirGapMode.LeaveResult.MissingPassword("B"), r)
        assertTrue(NetworkPolicy.blocked)
        assertTrue(prefs.getBoolean(AirGapMode.PREF_KEY, false))
    }
}

/** Minimal in-memory SharedPreferences: only what AirGapMode and InscriptionPrefs use. */
internal class FakePrefs : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { map.clear() }
        override fun commit(): Boolean { removed.forEach { map.remove(it) }; map.putAll(pending); return true }
        override fun apply() { commit() }
    }
}
