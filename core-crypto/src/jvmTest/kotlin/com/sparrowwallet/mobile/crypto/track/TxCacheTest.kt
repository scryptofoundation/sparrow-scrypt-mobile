package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.track.TrackFixtures.hash
import com.sparrowwallet.mobile.crypto.track.TrackFixtures.spendZero
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class TxCacheTest {
    @Test
    fun keepsMinedTransactionsOnly() {
        val cache = TxCache()
        val mined = ChainTx(spendZero(listOf(hash(1)), 1_000), 100, FakeChainSource.heightHash(100), 1_700_000_100L, true)
        val unconfirmed = ChainTx(spendZero(listOf(hash(2)), 2_000), 0, null, 0, true)
        cache.put(mined)
        cache.put(unconfirmed)
        assertSame(mined, cache.get(mined.tx.txid))
        //Its witness can still change until it is mined, so it is read again next time
        assertNull(cache.get(unconfirmed.tx.txid))
    }
}
