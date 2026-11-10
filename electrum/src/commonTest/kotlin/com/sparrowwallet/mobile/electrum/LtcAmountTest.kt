package com.sparrowwallet.mobile.electrum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Decimal-LTC → litoshi parsing used for verbose-transaction history deltas. */
class LtcAmountTest {
    @Test
    fun parsesVerboseTxValues() {
        assertEquals(5_000_000L, ElectrumProtocol.ltcToLitoshis("0.05"))
        assertEquals(123_456_789L, ElectrumProtocol.ltcToLitoshis("1.23456789"))
        assertEquals(100_000_000L, ElectrumProtocol.ltcToLitoshis("1"))
        assertEquals(1L, ElectrumProtocol.ltcToLitoshis("0.00000001"))
        assertEquals(0L, ElectrumProtocol.ltcToLitoshis("0.00000000"))
        assertEquals(8_400_000_000_000_000L, ElectrumProtocol.ltcToLitoshis("84000000"))
        assertEquals(-5_000_000L, ElectrumProtocol.ltcToLitoshis("-0.05"))
        assertEquals(50_000_000L, ElectrumProtocol.ltcToLitoshis(".5"))
    }

    /** ElectrumX on Python writes small floats in exponent form (seen from electrum1.cipig.net, 2026-10-31). */
    @Test
    fun parsesExponentFormExactly() {
        assertEquals(7_166L, ElectrumProtocol.ltcToLitoshis("7.166e-05"))
        assertEquals(1_000L, ElectrumProtocol.ltcToLitoshis("1e-05"))
        assertEquals(1L, ElectrumProtocol.ltcToLitoshis("1E-8"))
        assertEquals(15_000_000_000L, ElectrumProtocol.ltcToLitoshis("1.5e2"))
        assertEquals(-7_166L, ElectrumProtocol.ltcToLitoshis("-7.166e-05"))
        assertEquals(100_000_000L, ElectrumProtocol.ltcToLitoshis("1e+0"))
    }

    /** Fulcrum on LTC testnet printed 0.0009971800000000001 for 99718 litoshis (float arithmetic noise); sync failed on it. */
    @Test
    fun roundsFloatNoiseBeyondTheEighthDecimal() {
        assertEquals(99_718L, ElectrumProtocol.ltcToLitoshis("0.0009971800000000001"))
        assertEquals(99_718L, ElectrumProtocol.ltcToLitoshis("0.00099717999999999"))
        assertEquals(-99_718L, ElectrumProtocol.ltcToLitoshis("-0.0009971800000000001"))
        assertEquals(100_000_000L, ElectrumProtocol.ltcToLitoshis("0.99999999999999999"))
        //a real sub-litoshi amount is still refused
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("0.000000015") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("0.000000001") }
    }

    @Test
    fun rejectsMalformedValues() {
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("1e-09") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("1e") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("e5") }
        // a hostile server must not make the parser build a huge string
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("1e999999999") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("1e-999999999") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("1e2147483647") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("0.123456789") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("1.2.3") }
    }
}
