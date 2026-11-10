package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.track.ChainRules
import com.sparrowwallet.mobile.crypto.track.TxCache
import com.sparrowwallet.mobile.electrum.BackendChainSource
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.Utxo
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import com.sparrowwallet.mobile.electrum.inscriptions.TrackerRun
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the built-in inscription check over a wallet's unspent coins and keeps the results, in [store] and in memory.
 * The app keeps one per process (Home and the background worker share it), so its transaction caches, their locks and
 * the running-wallet guard are shared too. Nothing here turns a missing or failed answer into Clean: a coin without
 * its confirming block gets a result that does not stand, and a run that fails saves nothing.
 */
class InscriptionChecker(val store: InscriptionStore) {
    /** Transactions the traces read, one cache per coin and network; a cache is used under its lock only. */
    private val caches = ConcurrentHashMap<String, TxCache>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** Stored results by wallet file name, loaded once and replaced after each run. */
    private val memory = ConcurrentHashMap<String, Map<String, InscriptionCheck>>()
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
    fun results(walletName: String): Map<String, InscriptionCheck> =
        memory.computeIfAbsent(key(walletName)) { store.load(stored(walletName)) }

    /** The wallet's results if already in memory, else null; never reads the file, so it is cheap on the main thread. */
    fun cached(walletName: String): Map<String, InscriptionCheck>? = memory[key(walletName)]

    /** Drops the wallet's results from memory, after the wallet is deleted. */
    fun forget(walletName: String) {
        memory.remove(key(walletName))
    }

    /**
     * Checks [utxos], every unspent coin of the wallet, and saves the results, which replace the stored ones (so spent
     * coins drop out). An uncovered coin gets an empty map and no backend call; in air-gapped mode the stored results
     * come back and nothing is asked. Each confirmed coin's block hash is asked once per height; when the server gives
     * none or fails, the coin has no block for this run and its result cannot stand. If the run throws, nothing is saved.
     */
    suspend fun check(
        walletName: String,
        coin: Coin,
        network: Network,
        backend: ChainBackend,
        utxos: List<Utxo>,
        deeper: Set<String> = emptySet(),
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
        return locks.computeIfAbsent(chain) { Mutex() }.withLock {
            val stored = results(walletName)
            if(coins.isNotEmpty()) {
                progress(0, coins.size)
            }
            val checks = TrackerRun.run(
                coin, network, caches.computeIfAbsent(chain) { TxCache() }, BackendChainSource(backend), coins, stored,
                emptyMap(), null, deeper, TrackerRun.RUN_BUDGET, progress
            )
            store.save(stored(walletName), checks)
            memory[key(walletName)] = checks
            checks
        }
    }

    /** The block at [height], or null when the server gives none or the call fails (so nothing stands on it). */
    private suspend fun confirmingBlock(backend: ChainBackend, height: Int): String? = try {
        backend.blockHash(height)
    } catch(e: CancellationException) {
        throw e
    } catch(e: Exception) {
        null
    }

    /**
     * Runs [work] (a wallet's check) unless one is already going for the wallet, which skips it. A failure other than
     * cancellation is swallowed and the stored results stay as they were. Returns what [work] returned, or the stored
     * results when skipped or failed.
     */
    suspend fun guarded(walletName: String, work: suspend () -> Map<String, InscriptionCheck>): Map<String, InscriptionCheck> {
        val key = key(walletName)
        if(!running.add(key)) {
            return results(walletName)
        }
        try {
            return work()
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            return results(walletName)
        } finally {
            running.remove(key)
        }
    }
}
