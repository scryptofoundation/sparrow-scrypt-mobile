package com.sparrowwallet.mobile

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PsbtReviewsTest {
    @Test fun typingBurstUnlocksOnce(): Unit = runBlocking {
        var unlocks = 0
        val out = psbtReviews(flowOf("cHN", "cHNi", "cHNidP8="), pauseMs = 50) { unlocks++; it.size }.toList()
        assertEquals(1, unlocks)
        val last = out.single()!!
        assertEquals("cHNidP8=", last.text)
        assertContentEquals(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()), last.result.getOrThrow().first)
        assertEquals(5, last.result.getOrThrow().second)
    }

    @Test fun badBase64NeverUnlocks(): Unit = runBlocking {
        var unlocks = 0
        val out = psbtReviews(flowOf("not base64!"), pauseMs = 0) { unlocks++ }.toList().single()!!
        assertEquals(0, unlocks)
        assertTrue(out.result.isFailure)
    }

    @Test fun emptyInputHasNoReview(): Unit = runBlocking {
        assertNull(psbtReviews(flowOf("  "), pauseMs = 0) { error("no unlock") }.toList().single())
    }

    @Test fun reviewIsCurrentOnlyForTheTextItReviewed() {
        val reviewed = ReviewedPsbt("cHNidP8=", Result.success(byteArrayOf() to Unit))
        assertTrue(reviewed.isFor(" cHNidP8= "))
        assertTrue(!reviewed.isFor("cHNidP8=x"))
    }
}
