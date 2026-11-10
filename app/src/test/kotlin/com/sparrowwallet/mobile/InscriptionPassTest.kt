package com.sparrowwallet.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The background worker's inscription pass over its wallets, inside WorkManager's ten-minute window. */
class InscriptionPassTest {
    private val minute = 60_000L

    @Test
    fun noNewWalletStartsAfterFiveMinutes(): Unit = runBlocking {
        var now = 0L
        val ran = ArrayList<String>()
        //each check takes three minutes
        SyncWorker.inscriptionPass(listOf("a", "b", "c"), startedAtMs = 0L, now = { now }) { w, _ -> ran.add(w); now += 3 * minute }
        assertEquals(listOf("a", "b"), ran, "c would start at six minutes")
    }

    @Test
    fun theWorkersOwnTimeBeforeThePassCounts(): Unit = runBlocking {
        val ran = ArrayList<String>()
        SyncWorker.inscriptionPass(listOf("a"), startedAtMs = 0L, now = { 5 * minute }) { w, _ -> ran.add(w) }
        assertTrue(ran.isEmpty())
    }

    /** The run gets a deadline it checks between coins, before the worker's window closes. */
    @Test
    fun eachCheckGetsTheWorkersDeadline(): Unit = runBlocking {
        var now = 0L
        var timeUp: (() -> Boolean)? = null
        SyncWorker.inscriptionPass(listOf("a"), startedAtMs = 0L, now = { now }) { _, t -> timeUp = t }
        assertFalse(timeUp!!())
        now = SyncWorker.CHECK_DEADLINE_MS - 1
        assertFalse(timeUp!!())
        now = SyncWorker.CHECK_DEADLINE_MS
        assertTrue(timeUp!!())
        assertTrue(SyncWorker.CHECK_DEADLINE_MS < 10 * minute)
    }

    /** One wallet's check that is cancelled or fails does not end the pass; a stopped worker does. */
    @Test
    fun aCancelledCheckDoesNotEndThePass(): Unit = runBlocking {
        val ran = ArrayList<String>()
        SyncWorker.inscriptionPass(listOf("a", "b", "c"), startedAtMs = 0L, now = { 0L }) { w, _ ->
            ran.add(w)
            when(w) {
                "a" -> throw CancellationException("a timed out")
                "b" -> throw IllegalStateException("server down")
            }
        }
        assertEquals(listOf("a", "b", "c"), ran)

        val seen = ArrayList<String>()
        val worker = async {
            SyncWorker.inscriptionPass(listOf("a", "b"), startedAtMs = 0L, now = { 0L }) { w, _ -> seen.add(w); awaitCancellation() }
        }
        yield()
        worker.cancel()
        worker.join()
        assertEquals(listOf("a"), seen, "a stopped worker stops the pass")
    }
}
