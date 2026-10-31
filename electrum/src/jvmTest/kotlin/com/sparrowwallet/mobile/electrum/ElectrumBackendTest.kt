package com.sparrowwallet.mobile.electrum

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElectrumBackendTest {
    /** A verbose tx as ElectrumX returns it, with a coinbase input and an exponent-form value. */
    private val verbose = """
        {"txid": "aa", "time": 1761000000, "confirmations": 3,
         "vin": [{"coinbase": "03aabbcc", "sequence": 4294967295},
                 {"txid": "bb", "vout": 1, "sequence": 4294967293}],
         "vout": [{"value": 7.166e-05, "n": 0, "scriptPubKey": {"hex": "0014aabb"}},
                  {"value": 1.5, "n": 1, "scriptPubKey": {"hex": "76a914ccdd88ac"}}]}
    """.trimIndent()

    @Test fun verboseTxMapsToTxDetail() {
        val tx = ElectrumBackend.toTxDetail(Json.parseToJsonElement(verbose).jsonObject)
        assertEquals("aa", tx.txid)
        assertEquals(listOf(TxOut("0014aabb", 7_166), TxOut("76a914ccdd88ac", 150_000_000)), tx.outputs)
        assertNull(tx.inputs[0].prevTxid)
        assertEquals("bb", tx.inputs[1].prevTxid)
        assertEquals(1, tx.inputs[1].prevVout)
        assertNull(tx.inputs[1].prevOut)
        assertEquals(1761000000L, tx.time)
        assertEquals(3, tx.confirmations)
        assertEquals(0, tx.height)
    }

    @Test fun electrumFeeRateIsSatPerVb() {
        assertEquals(10.0, ElectrumBackend.feeRateFromCoinPerKb(0.0001))
        assertNull(ElectrumBackend.feeRateFromCoinPerKb(-1.0))
    }
}
