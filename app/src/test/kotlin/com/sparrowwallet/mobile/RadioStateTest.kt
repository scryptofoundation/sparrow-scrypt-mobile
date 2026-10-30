package com.sparrowwallet.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioStateTest {
    @Test fun quietWhenFullyOffline() = assertNull(radioWarning(Radios(true, emptySet(), false)))

    @Test fun unknownBluetoothIsNotReported() = assertNull(radioWarning(Radios(true, emptySet(), null)))

    @Test fun listsEverythingOn() = assertEquals("Radios on: Airplane mode off, WiFi, Bluetooth",
        radioWarning(Radios(false, setOf("WiFi"), true)))

    @Test fun transportsInFixedOrder() = assertEquals("Radios on: WiFi, Mobile data, Ethernet, VPN, Network",
        radioWarning(Radios(true, setOf("Network", "VPN", "Mobile data", "Ethernet", "WiFi"), false)))
}
