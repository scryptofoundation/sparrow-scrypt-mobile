package com.sparrowwallet.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PendingPsbtsTest {
    private fun unsigned(to: String) = WalletRepository.UnsignedSendPreview(to, 1_000, 226, 1, null, "cHNidP8B")

    @Test
    fun keepsTheLatestUnsignedPsbtPerWallet() {
        val pending = PendingPsbts()
        val first = unsigned("a1")
        val other = unsigned("b1")
        pending.keep("alpha", first)
        pending.keep("beta", other)
        assertSame(first, pending["alpha"])
        val second = unsigned("a2")
        pending.keep("alpha", second)
        assertSame(second, pending["alpha"])
        assertSame(other, pending["beta"])
    }

    @Test
    fun dropForgetsOnlyThatWallet() {
        val pending = PendingPsbts()
        pending.keep("alpha", unsigned("a1"))
        pending.keep("beta", unsigned("b1"))
        pending.drop("alpha")
        assertNull(pending["alpha"])
        assertEquals("b1", pending["beta"]?.toAddress)
    }
}
