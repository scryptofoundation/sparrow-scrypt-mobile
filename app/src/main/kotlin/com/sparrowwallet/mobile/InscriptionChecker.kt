package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.track.ChainRules
import com.sparrowwallet.mobile.crypto.track.TxCache
import com.sparrowwallet.mobile.electrum.BackendChainSource
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.NoRateLimitWait
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.Utxo
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import com.sparrowwallet.mobile.electrum.inscriptions.TrackerRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the built-in inscription check over a wallet's unspent coins and keeps the results, in [store] and in memory.
 * The app keeps one per process (Home and the background worker share it), so its transaction caches, their locks and
 * the running-wallet guard are shared too. Nothing here turns a missing or failed answer into Clean: a coin without
 * its confirming block gets a result that does not stand. A run saves as it goes, so one that stops or fails keeps
 * what it decided; coins it did not reach keep their stored rows.
 */
class InscriptionChecker(val store: InscriptionStore) {
    /** Runs Home starts live here, for the process's lifetime, so leaving the screen does not cancel them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** (done, total) of the wallet's run while one goes, else null; by wallet key. */
    private val progressFlows = ConcurrentHashMap<String, MutableStateFlow<Pair<Int, Int>?>>()
    /** Bumped by [forget], so a run that began before a wallet was deleted never writes its file back. */
    private val epochs = ConcurrentHashMap<String, Int>()
    private val saveLock = Any()
    /** Transactions the traces read, one cache per coin and network; a cache is used under its lock only. */
    private val caches = ConcurrentHashMap<String, TxCache>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** A wallet's results, and whether they come from a readable file or a finished run (so an empty map counts as checked). */
    private class Stored(val checks: Map<String, InscriptionCheck>, val checked: Boolean)

    /** Stored results by wallet file name, loaded once and replaced after each run. */
    private val memory = ConcurrentHashMap<String, Stored>()
    /** Wallets with a run going. */
    private val running = ConcurrentHashMap.newKeySet<String>()

    /**
     * The name a wallet is stored under: its wallet file name, as the session gives it to Home. The worker passes the
     * name its credentials were cached under, which maps to the same one.
     */
    private fun stored(walletName: String) = walletFileName(walletName)

    /** The key of the wallet's file, for memory and the running guard. */
    private fun key(walletName: String) = safeFileName(stored(walletName))

    /** The wallet's stored results, by outpoint (empty when never checked). Reads the file the first time: call it off the main thread. */
    fun results(walletName: String): Map<String, InscriptionCheck> = entry(walletName).checks

    /**
     * The wallet's stored results, possibly empty when a run found nothing to store, or null when the wallet has no
     * readable results file and no finished run (never checked, or the file was lost). Reads the file the first time.
     */
    fun storedResults(walletName: String): Map<String, InscriptionCheck>? = entry(walletName).takeIf { it.checked }?.checks

    private fun entry(walletName: String): Stored = memory.computeIfAbsent(key(walletName)) {
        store.loadOrNull(stored(walletName)).let { Stored(it ?: emptyMap(), it != null) }
    }

    /** The wallet's results if already in memory, else null; never reads the file, so it is cheap on the main thread. */
    fun cached(walletName: String): Map<String, InscriptionCheck>? = memory[key(walletName)]?.checks

    /**
     * Drops the wallet's results from memory and cancels its started run, before the wallet's file is deleted: a run
     * still going (the worker's) saves nothing more for it.
     */
    fun forget(walletName: String) {
        val key = key(walletName)
        synchronized(saveLock) {
            epochs[key] = (epochs[key] ?: 0) + 1
            memory.remove(key)
        }
        jobs[key]?.cancel()
    }

    /** The wallet's run as (done, total) coins while one goes, whoever started it; null otherwise. */
    fun progress(walletName: String): StateFlow<Pair<Int, Int>?> = flow(key(walletName))

    private fun flow(key: String) = progressFlows.computeIfAbsent(key) { MutableStateFlow(null) }

    /**
     * Starts [work] (a wallet's check) in the checker's own scope unless one is already going for the wallet; returns
     * its job, or null when skipped. Not tied to any screen: it runs to its end unless [forget] cancels it. A failure
     * is swallowed (what the run decided is already saved).
     */
    fun start(walletName: String, work: suspend () -> Map<String, InscriptionCheck>): Job? {
        val key = key(walletName)
        if(!running.add(key)) {
            return null
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } catch(e: CancellationException) {
                throw e
            } catch(e: Exception) {
                //the stored results stay as the run left them
            }
        }
        jobs[key] = job
        job.invokeOnCompletion {
            jobs.remove(key, job)
            running.remove(key)
        }
        job.start()
        return job
    }

    /**
     * Checks [utxos], every unspent coin of the wallet, and saves the results, which replace the stored ones (so spent
     * coins drop out). An uncovered coin gets an empty map and no backend call; in air-gapped mode the stored results
     * come back and nothing is asked. Each confirmed coin's block hash is asked once per height; when the server gives
     * none or fails, the coin has no block for this run and its result cannot stand. While it runs, each coin's new
     * result is saved over the stored rows as soon as it is decided (when it differs from what the file holds), so a
     * run that is cancelled or fails keeps what it decided and the rows of the coins it did not reach. [timeUp] stops it
     * between coins (see [TrackerRun.TIME_UP]). [progress] is told (done, total) as it goes, and so is the wallet's
     * [InscriptionChecker.progress] flow, which goes back to null when the run ends.
     */
    suspend fun check(
        walletName: String,
        coin: Coin,
        network: Network,
        backend: ChainBackend,
        utxos: List<Utxo>,
        deeper: Set<String> = emptySet(),
        timeUp: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> }
    ): Map<String, InscriptionCheck> {
        if(!ChainRules.covers(coin)) {
            return emptyMap()
        }
        if(NetworkPolicy.blocked) {
            return results(walletName)
        }
        val blocks = HashMap<Int, String?>()
        val coins = utxos.map { utxo ->
            val block = if(utxo.height <= 0) {
                null
            } else if(blocks.containsKey(utxo.height)) {
                blocks[utxo.height]
            } else {
                confirmingBlock(backend, utxo.height).also { blocks[utxo.height] = it }
            }
            TrackerRun.Coin(utxo.txHash, utxo.txPos, block)
        }

        val chain = "${coin.id}:${network.id}"
        val key = key(walletName)
        val shown = flow(key)
        val epoch = epochs[key] ?: 0
        return locks.computeIfAbsent(chain) { Mutex() }.withLock {
            val stored = results(walletName)
            //What the file holds: the stored rows, each replaced by the coin's new result once decided
            val saved = LinkedHashMap(stored)
            val report = { done: Int, total: Int -> shown.value = done to total; progress(done, total) }
            try {
                if(coins.isNotEmpty()) {
                    report(0, coins.size)
                }
                val checks = TrackerRun.run(
                    coin, network, caches.computeIfAbsent(chain) { TxCache() }, BackendChainSource(backend), coins, stored,
                    emptyMap(), null, deeper, TrackerRun.RUN_BUDGET, timeUp, { outpoint, check ->
                        if(saved[outpoint] != check) {
                            saved[outpoint] = check
                            persist(walletName, key, epoch, HashMap(saved))
                        }
                    }, report
                )
                //The final save drops spent coins
                if(checks != saved || memory[key]?.checked != true) {
                    persist(walletName, key, epoch, checks)
                }
                checks
            } finally {
                shown.value = null
            }
        }
    }

    /**
     * Writes the wallet's file, then its copy in memory, unless the wallet was forgotten (deleted) since the run began.
     * Each write is atomic (see [InscriptionStore.save]).
     */
    private fun persist(walletName: String, key: String, epoch: Int, checks: Map<String, InscriptionCheck>) {
        synchronized(saveLock) {
            if((epochs[key] ?: 0) != epoch) {
                return
            }
            store.save(stored(walletName), checks)
            memory[key] = Stored(checks, true)
        }
    }

    /**
     * The block at [height], or null when the server gives none or the call fails (so nothing stands on it). A rate
     * limit is not read as "no block" (that would drop every proven row as if reorged and trace them all again on a
     * server already refusing): it throws, ending the run before anything is saved.
     */
    private suspend fun confirmingBlock(backend: ChainBackend, height: Int): String? = try {
        withContext(NoRateLimitWait) { backend.blockHash(height) }
    } catch(e: CancellationException) {
        throw e
    } catch(e: RateLimitedException) {
        throw e
    } catch(e: Exception) {
        null
    }

    /**
     * Runs [work] (a wallet's check) unless one is already going for the wallet, which skips it. A failure other than
     * cancellation is swallowed and the stored results stay as they were. Returns what [work] returned, or the stored
     * results when skipped or failed.
     */
    suspend fun guarded(walletName: String, work: suspend () -> Map<String, InscriptionCheck>): Map<String, InscriptionCheck> =
        tryRun(walletName, work) ?: results(walletName)

    /** As [guarded], but null when skipped or failed, so the caller (the worker) knows whether the check ran. */
    suspend fun tryRun(walletName: String, work: suspend () -> Map<String, InscriptionCheck>): Map<String, InscriptionCheck>? {
        val key = key(walletName)
        if(!running.add(key)) {
            return null
        }
        try {
            return work()
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            return null
        } finally {
            running.remove(key)
        }
    }
}
