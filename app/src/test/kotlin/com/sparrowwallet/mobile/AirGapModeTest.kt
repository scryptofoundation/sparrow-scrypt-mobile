package com.sparrowwallet.mobile

import android.content.SharedPreferences
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
        val r = AirGapMode.leave(prefs, listOf("A", "B"), mapOf("A" to "ok", "B" to "bad"), okOnly)
        assertEquals(AirGapMode.LeaveResult.WrongPassword("B"), r)
        assertTrue(NetworkPolicy.blocked)
        assertTrue(prefs.getBoolean(AirGapMode.PREF_KEY, false))
    }

    @Test fun leaveWithAllPasswordsClearsMode() {
        AirGapMode.enter(prefs, {}, {})
        val r = AirGapMode.leave(prefs, listOf("A", "B"), mapOf("A" to "ok", "B" to "ok"), okOnly)
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
        val r = AirGapMode.leave(prefs, listOf("A", "B"), mapOf("A" to "ok"), okOnly)
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
