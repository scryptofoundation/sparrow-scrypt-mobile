package com.sparrowwallet.mobile

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncBaselineTest {
    private fun creds(baselined: Boolean = false, knownTxids: List<String> = emptyList(), knownOutputIds: List<String> = emptyList()) =
        SyncCredentials(name = "w", network = "testnet", scriptType = "P2WPKH", baselined = baselined,
            knownTxids = knownTxids, knownOutputIds = knownOutputIds)

    @Test
    fun firstRunOnlyRecordsTheBaseline() {
        assertEquals(0, SyncBaseline.newlyReceived(creds(), listOf("a" to 500L)))
    }

    @Test
    fun firstPaymentIntoAWalletThatWasEmptyAtBaselineIsReported() {
        assertEquals(100_000, SyncBaseline.newlyReceived(creds(baselined = true), listOf("a" to 100_000L)))
    }

    @Test
    fun knownAndOutgoingTransactionsAreNotReported() {
        val history = listOf("old" to 500L, "spend" to -300L, "new" to 200L)
        assertEquals(200, SyncBaseline.newlyReceived(creds(baselined = true, knownTxids = listOf("old")), history))
    }

    @Test
    fun cacheFilesFromBeforeTheFlagCountAsBaselinedWhenTheyHaveHistory() {
        //Saved by an older version: no baselined field, but a recorded history means a baseline was taken
        assertEquals(200, SyncBaseline.newlyReceived(creds(knownTxids = listOf("old")), listOf("old" to 500L, "new" to 200L)))
        assertEquals(300, SyncBaseline.newlyReceivedPrivate(creds(knownOutputIds = listOf("o1")), listOf("o1" to 1L, "o2" to 300L)))
    }

    @Test
    fun privateOutputsFollowTheSameRules() {
        assertEquals(0, SyncBaseline.newlyReceivedPrivate(creds(), listOf("o1" to 700L)))
        assertEquals(700, SyncBaseline.newlyReceivedPrivate(creds(baselined = true), listOf("o1" to 700L)))
    }

    @Test
    fun credentialsWithoutCoinReadAsLitecoin() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(SyncCredentials.serializer(),
            """{"name":"w","network":"testnet","scriptType":"P2WPKH","xpub":"tpub"}""")
        assertEquals("litecoin", old.coin)
    }
}
