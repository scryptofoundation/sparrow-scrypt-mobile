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
}
