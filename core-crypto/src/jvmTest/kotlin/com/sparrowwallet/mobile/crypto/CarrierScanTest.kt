package com.sparrowwallet.mobile.crypto

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarrierScanTest {
    // Litecoin mainnet d61bf79134486f169c0e719fca6b76a8b34bb9413d54ebfdbff605289cb12fa5, a plain 1-in 2-out payment
    private val plainLtc = "02000000000101878a9c672c7b4d9ca186c3cbf6d3b045b592e8a1f7d3c5650d394291cd02fad30100000000fdffffff02141607" +
        "0400000000160014248050c08b32ad37711a73e3b237e4af914a71bcf45303a304000000160014accd53bf99da0a8241a53f570f570395b8b4146d0247" +
        "3044022018d94b3d4367acccf4bd8ab6965289ebd0b209d15ed6585ae974093e0dba1ca902206be66fc371eff5ecc273984c7d75731b2778ead7f818be" +
        "494ee5395fbfad0e3a012102a709654305b6593a2b92c6430365c9b12e29410a69c2763b47fbf79a6c16cffa0dec3000"

    /** The same real transactions drongo's CarrierScanTest reads, so both apps flag the same coins. */
    @Test
    fun mainnetVectorsMarkTheirOutputs() {
        val vectors = File("../vectors/carrier-vectors.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(" ") }
        assertEquals(10, vectors.size)
        for((coin, kind, txid, marks, hex) in vectors) {
            val tx = RawTx.parse(hex.hexToBytes())
            assertEquals(txid, tx.txid, "$coin $kind")
            assertEquals(marks.toBoolean(), CarrierScan.marksOutputs(tx), "$coin $kind")
            if(marks.toBoolean()) {
                assertTrue(CarrierScan.mayCarry(Coin.valueOf(coin), tx, 0, tx.outputs[0].value) || tx.outputs[0].script[0] == 0x6a.toByte(), "$coin $kind")
            }
        }
    }

    @Test
    fun plainPaymentIsClean() {
        val tx = RawTx.parse(plainLtc.hexToBytes())
        assertFalse(CarrierScan.marksOutputs(tx))
        tx.outputs.forEachIndexed { i, out -> assertFalse(CarrierScan.mayCarry(Coin.LITECOIN, tx, i, out.value)) }
    }

    @Test
    fun opReturnOutputNeverCarries() {
        val runes = File("../vectors/carrier-vectors.txt").readLines().first { it.startsWith("LITECOIN runestone") }.split(" ")[4]
        val tx = RawTx.parse(runes.hexToBytes())
        assertFalse(CarrierScan.mayCarry(Coin.LITECOIN, tx, 0, 0))
        assertTrue(CarrierScan.mayCarry(Coin.LITECOIN, tx, 1, tx.outputs[1].value))
        assertTrue(CarrierScan.mayCarry(Coin.LITECOIN, tx, 2, tx.outputs[2].value))
    }

    /** An inscription passed on by others leaves no trace in the delivering transaction: its usual value is the hint. */
    @Test
    fun postageValues() {
        assertTrue(CarrierScan.mayCarry(Coin.LITECOIN, null, 0, 10_000))
        assertTrue(CarrierScan.mayCarry(Coin.DOGECOIN, null, 0, 100_000))
        assertTrue(CarrierScan.mayCarry(Coin.PEPECOIN, null, 0, 100_000))
        assertTrue(CarrierScan.mayCarry(Coin.JUNKCOIN, null, 0, 100_000))
        assertTrue(CarrierScan.mayCarry(Coin.BELLSCOIN, null, 0, 1_000))
        assertTrue(CarrierScan.mayCarry(Coin.BELLSCOIN, null, 0, 100_000))
        assertFalse(CarrierScan.mayCarry(Coin.LITECOIN, null, 0, 100_000))
        assertFalse(CarrierScan.mayCarry(Coin.LUCKYCOIN, null, 0, 100_000))
        assertFalse(CarrierScan.mayCarry(Coin.DINGOCOIN, null, 0, 100_000_000))
    }
}
