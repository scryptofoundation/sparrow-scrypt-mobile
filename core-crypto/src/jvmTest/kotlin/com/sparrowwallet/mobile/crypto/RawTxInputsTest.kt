package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawTxInputsTest {
    @Test
    fun parsedCarriesOutpoints() {
        assertTrue(CoinVectors.file.signedTxs.isNotEmpty())
        for(vector in CoinVectors.file.signedTxs) {
            val parsed = RawTx.parse(vector.txHex.hexToBytes())
            assertEquals(vector.inputs.map { it.prevTxid }, parsed.inputs.map { it.prevTxid }, "${vector.coin} ${vector.scriptType} txids")
            assertEquals(vector.inputs.map { it.vout }, parsed.inputs.map { it.prevVout }, "${vector.coin} ${vector.scriptType} vouts")
            assertEquals(vector.inputs.map { it.sequence }, parsed.inputs.map { it.sequence }, "${vector.coin} ${vector.scriptType} sequences")
            assertTrue(parsed.inputs.none { it.isCoinbase })
            assertFalse(parsed.mweb)
        }
    }

    @Test
    fun coinbaseInputIsRecognized() {
        // Junkcoin mainnet block 1201000's coinbase (see RawTxTest)
        val hex = "020000000001010000000000000000000000000000000000000000000000000000000000000000ffffffff06036753120101" +
            "ffffffff0380b2e60e000000001976a9146d1a871e6bc73aa23d9c086f6f2703d9e2a8b30788aca0acb9030000000017a914" +
            "ea397f71548cc83149ee542322a3bfac33c7cc99870000000000000000266a24aa21a9ed20ffb1cd59cf929eec6b2d97023b" +
            "b5204037049a1ca4e102acb8987fd43107a50120000000000000000000000000000000000000000000000000000000000000" +
            "000000000000"
        val parsed = RawTx.parse(hex.hexToBytes())
        assertEquals(1, parsed.inputs.size)
        assertTrue(parsed.inputs[0].isCoinbase)
        assertEquals("0".repeat(64), parsed.inputs[0].prevTxid)
        assertEquals(0xFFFFFFFFL, parsed.inputs[0].prevVout)
        assertEquals(2, parsed.version)
        assertEquals(0L, parsed.locktime)
        assertFalse(parsed.mweb)
    }

    @Test
    fun mwebFlagIsExposedAndExtensionStaysOutOfTheTxid() {
        // one input (outpoint 11..11 : 7, sequence fffffffe), one output of 5000 sats, locktime 99
        val body = "01" + "11".repeat(32) + "07000000" + "00" + "feffffff" +
            "01" + "8813000000000000" + "03" + "51" + "5151"
        val legacy = RawTx.parse(("02000000" + body + "63000000").hexToBytes())
        // same transaction with flag 0x08: marker 00, flag 08, then a made-up MWEB extension before the locktime
        val mweb = RawTx.parse(("02000000" + "0008" + body + "01aabbcc" + "63000000").hexToBytes())
        assertFalse(legacy.mweb)
        assertTrue(mweb.mweb)
        assertEquals(legacy.txid, mweb.txid)
        assertEquals(99L, mweb.locktime)
        assertEquals("11".repeat(32), mweb.inputs[0].prevTxid)
        assertEquals(7L, mweb.inputs[0].prevVout)
        assertEquals(0xFFFFFFFEL, mweb.inputs[0].sequence)
    }
}
