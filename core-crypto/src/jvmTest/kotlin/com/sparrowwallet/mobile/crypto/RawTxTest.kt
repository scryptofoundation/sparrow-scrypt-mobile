package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class RawTxTest {
    @Test
    fun parsesASegwitCoinbase() {
        // Junkcoin mainnet block 1201000's coinbase, as explorer.junk-coin.com serves it
        val hex = "020000000001010000000000000000000000000000000000000000000000000000000000000000ffffffff06036753120101" +
            "ffffffff0380b2e60e000000001976a9146d1a871e6bc73aa23d9c086f6f2703d9e2a8b30788aca0acb9030000000017a914" +
            "ea397f71548cc83149ee542322a3bfac33c7cc99870000000000000000266a24aa21a9ed20ffb1cd59cf929eec6b2d97023b" +
            "b5204037049a1ca4e102acb8987fd43107a50120000000000000000000000000000000000000000000000000000000000000" +
            "000000000000"
        assertEquals("9dbb51e689b5b6d7dcd4efead3cc7d4ad3b4e1f6b45360edeb8766651c105648", RawTx.parse(hex.hexToBytes()).txid)
    }
}
