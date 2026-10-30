package com.sparrowwallet.mobile.crypto

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NetworkPolicyTest {
    @AfterTest fun reset() { NetworkPolicy.blocked = false }

    @Test fun checkPassesWhenOpen() {
        NetworkPolicy.blocked = false
        NetworkPolicy.check("Electrum")
    }

    @Test fun checkThrowsWhenBlocked() {
        NetworkPolicy.blocked = true
        val e = assertFailsWith<AirGappedException> { NetworkPolicy.check("Electrum") }
        assertEquals("Electrum", e.what)
        assertEquals("Air-gapped mode is on: Electrum is disabled", e.message)
    }

    @Test fun turningOnCutsWhatIsOpen() {
        var cut = 0
        NetworkPolicy.whileOpen { cut++ }
        NetworkPolicy.blocked = true
        assertEquals(1, cut)
    }

    @Test fun releasedConnectionIsNotCut() {
        var cut = 0
        val release = NetworkPolicy.whileOpen { cut++ }
        release()
        NetworkPolicy.blocked = true
        assertEquals(0, cut)
    }

    @Test fun openingWhileBlockedIsCutAtOnce() {
        NetworkPolicy.blocked = true
        var cut = 0
        NetworkPolicy.whileOpen { cut++ }
        assertEquals(1, cut)
    }
}
