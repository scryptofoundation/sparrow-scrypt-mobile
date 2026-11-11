package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.track.ChainRules
import com.sparrowwallet.mobile.crypto.track.TxCache
import com.sparrowwallet.mobile.crypto.track.Verdict
import com.sparrowwallet.mobile.electrum.BackendChainSource
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.NoRateLimitWait
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.Utxo
import com.sparrowwallet.mobile.electrum.indexer.IndexerInscription
import com.sparrowwallet.mobile.electrum.indexer.IndexerOutput
import com.sparrowwallet.mobile.electrum.indexer.Indexers
import com.sparrowwallet.mobile.electrum.SocksProxy
import com.sparrowwallet.mobile.electrum.WalletScanner
import com.sparrowwallet.mobile.electrum.indexer.InscriptionIndexer
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
 * A wallet's inscription check run: [done] of [total] coins, whether the indexer the setting names could not answer
 * ([indexerUnreachable]: the run fell back to the built-in trace), and whether it is still [running].
 */
data class CheckStatus(val done: Int, val total: Int, val indexerUnreachable: Boolean, val running: Boolean, val indexerPartial: Boolean = false)

/**
 * Runs the built-in inscription check over a wallet's unspent coins and keeps the results, in [store] and in memory.
 * The app keeps one per process (Home and the background worker share it), so its transaction caches, their locks and
 * the running-wallet guard are shared too. Nothing here turns a missing or failed answer into Clean: a coin without
 * its confirming block gets a result that does not stand. A run saves as it goes, so one that stops or fails keeps
 * what it decided; coins it did not reach keep their stored rows.
 */
class InscriptionChecker(
    val store: InscriptionStore,
    /** How long a run's inscription number lookups may take in all (see [fillNumbers]). */
    private val numberFillDeadlineMs: Long = NUMBER_FILL_DEADLINE_MS,
    /** The wall clock, in milliseconds, for how long a kept mark has been missing from the listings. */
    private val now: () -> Long = System::currentTimeMillis,
    /** How long a run waits for its indexer's outputs answer (see [indexerOutputs]). */
    private val indexerDeadlineMs: Long = INDEXER_DEADLINE_MS
) {
    /** Runs Home starts live here, for the process's lifetime, so leaving the screen does not cancel them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** (done, total) of the wallet's run while one goes, else null; by wallet key. */
    private val progressFlows = ConcurrentHashMap<String, MutableStateFlow<Pair<Int, Int>?>>()
    private val statusFlows = ConcurrentHashMap<String, MutableStateFlow<CheckStatus?>>()
    /** Bumped by [forget], so a run that began before a wallet was deleted never writes its file back. */
    private val epochs = ConcurrentHashMap<String, Int>()
    /** The epoch when a run was claimed ([start], [tryRun]), so a wallet deleted while its coins are listed is noticed. */
    private val claimedEpochs = ConcurrentHashMap<String, Int>()
    private val saveLock = Any()
    /** Transactions the traces read, one cache per coin and network; a cache is used under its lock only. */
    private val caches = ConcurrentHashMap<String, TxCache>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    /**
     * A wallet's results, and whether they come from a readable file or a finished run (so an empty map counts as checked),
     * with the kept block hashes and the kept indexer marks of outpoints the last listing left out (not coins).
     */
    private data class Stored(val checks: Map<String, InscriptionCheck>, val checked: Boolean, val blocks: Map<Int, String> = emptyMap(),
                              val marks: Map<String, KeptMark> = emptyMap(), val asked: Map<String, Long> = emptyMap(),
                              val lost: Boolean = false, val lastUnreachable: Boolean? = null, val lastRunAt: Long? = null,
                              val lostCoins: Set<String> = emptySet(), val askedFor: String? = null, val lastPartial: Boolean? = null,
                              val unavailable: Boolean = false)

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

    /**
     * The wallet's stored results, read once. A file that cannot be read is set aside and the wallet counts as checked
     * with no rows (see [InscriptionStore.loadOrSetAside]). The last run's reach, kept with the results, seeds [status]
     * after a restart, so the header does not say Up to date when that run could not reach its indexer.
     */
    private fun entry(walletName: String): Stored {
        val key = key(walletName)
        memory[key]?.let { return it }
        val load = store.loadOrSetAside(stored(walletName))
        if(load.unavailable) {
            //Read error: nothing kept in memory, so the next read tries again. Meanwhile the wallet counts as checked with
            //no rows (Send: every coin Unchecked, never the old local rule) and a check run is refused (see [check])
            return Stored(emptyMap(), true, unavailable = true)
        }
        val file = load.file
        val loaded = Stored(file?.checks ?: emptyMap(), file != null, file?.blocks ?: emptyMap(), file?.marks ?: emptyMap(),
            file?.asked ?: emptyMap(), file?.lost ?: false, file?.lastUnreachable, file?.lastRunAt, file?.lostCoins ?: emptySet(),
            file?.askedFor, file?.lastPartial)
        loaded.lastUnreachable?.let {
            statusFlow(key).compareAndSet(null, CheckStatus(loaded.checks.size, loaded.checks.size, it, running = false,
                indexerPartial = loaded.lastPartial == true))
        }
        return memory.putIfAbsent(key, loaded) ?: loaded
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
        //Its Check deeper requests go too, before its run is cancelled, so no follow-up starts for it
        synchronized(deeperLock) {
            deeperQueues.remove(key)
            reruns.remove(key)
            publishDeeper(key)
        }
        jobs[key]?.cancel()
    }

    /** The wallet's run as (done, total) coins while one goes, whoever started it; null otherwise. */
    fun progress(walletName: String): StateFlow<Pair<Int, Int>?> = flow(key(walletName))

    private fun flow(key: String) = progressFlows.computeIfAbsent(key) { MutableStateFlow(null) }

    /**
     * The wallet's last or current run (see [CheckStatus]): null until a run has begun in this process. Unlike
     * [progress] it stays after the run ends, with [CheckStatus.running] false, so a screen can say whether that run
     * could reach its indexer.
     */
    fun status(walletName: String): StateFlow<CheckStatus?> = statusFlow(key(walletName))

    private fun statusFlow(key: String) = statusFlows.computeIfAbsent(key) { MutableStateFlow(null) }

    /**
     * Starts [work] (a wallet's check) in the checker's own scope unless one is already going for the wallet; returns
     * its job, or null when skipped. Not tied to any screen: it runs to its end unless [forget] cancels it. A failure
     * is swallowed (what the run decided is already saved).
     *
     * Skipped because a Check deeper run holds the wallet (one that checks only what it was asked to and lists coins
     * before Home's sync saw new ones), [work] is kept and run once when that run ends: one rerun however many times it
     * was skipped (the latest work is kept), through the same guard. Skipped by any other run, nothing is kept: that run
     * checks the wallet's coins itself.
     */
    fun start(walletName: String, work: suspend () -> Map<String, InscriptionCheck>): Job? {
        val key = key(walletName)
        synchronized(deeperLock) {
            if(!running.add(key)) {
                if(deeperQueues[key]?.inFlight?.isNotEmpty() == true) {
                    reruns[key] = work
                }
                return null
            }
        }
        return launchClaimed(walletName, key, {}, work)
    }

    /** Checks skipped by a Check deeper run, to run once it ends; by wallet key. Guarded by [deeperLock]. */
    private val reruns = HashMap<String, suspend () -> Map<String, InscriptionCheck>>()

    private fun startRerun(walletName: String) {
        val work = synchronized(deeperLock) { reruns.remove(key(walletName)) } ?: return
        //Taken again by a deeper run that started first, it is kept again for that run's end
        start(walletName, work)
    }

    /**
     * Launches [work] for a wallet whose guard the caller has just taken. When it ends, however it ends, [onEnd] runs and
     * the guard is let go (together, so [start] sees either both or neither), then any Check deeper waiting for the
     * wallet starts (see [requestDeeper]), then a check a deeper run displaced (see [start]).
     */
    private fun launchClaimed(walletName: String, key: String, onEnd: () -> Unit, work: suspend () -> Map<String, InscriptionCheck>): Job {
        claim(key)
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
            claimedEpochs.remove(key)
            synchronized(deeperLock) {
                onEnd()
                running.remove(key)
            }
            startDeeper(walletName)
            startRerun(walletName)
        }
        job.start()
        return job
    }

    /**
     * Check deeper requests for one wallet: [waiting] for a run, and [inFlight], taken by the run now going. [run] starts
     * a check with a deeper set; the latest request's is kept.
     */
    private class DeeperQueue(var run: (suspend (Set<String>) -> Map<String, InscriptionCheck>)? = null) {
        val waiting = LinkedHashSet<String>()
        var inFlight: Set<String> = emptySet()
    }

    private val deeperLock = Any()
    /** Guarded by [deeperLock]. In memory only: a request does not outlive the process. */
    private val deeperQueues = HashMap<String, DeeperQueue>()
    private val deeperFlows = ConcurrentHashMap<String, MutableStateFlow<Set<String>>>()

    private fun deeperFlow(key: String) = deeperFlows.computeIfAbsent(key) { MutableStateFlow(emptySet()) }

    /** Call under [deeperLock]. */
    private fun publishDeeper(key: String) {
        val queue = deeperQueues[key]
        deeperFlow(key).value = if(queue == null) emptySet() else LinkedHashSet(queue.waiting).apply { addAll(queue.inFlight) }
    }

    /**
     * The outpoints a Check deeper was asked for that have not had their run yet or are in it now: the Collectibles
     * screen shows them as Queued.
     */
    fun deeperQueued(walletName: String): StateFlow<Set<String>> = deeperFlow(key(walletName))

    /** Whether the wallet's queue holds a runner (and what it captured): only while something waits or runs. */
    @androidx.annotation.VisibleForTesting
    internal fun holdsDeeperRunner(walletName: String): Boolean = synchronized(deeperLock) { deeperQueues[key(walletName)]?.run != null }

    /** Test hook: runs after a deeper run has taken its outpoints and before it is launched. Not settable outside the module. */
    @androidx.annotation.VisibleForTesting
    @Volatile internal var beforeDeeperLaunch: () -> Unit = {}

    /**
     * Asks for [outpoint] to be checked deeper. [run] is the wallet's check with a deeper set (in the app, the same path
     * as Home's: the listing, then [checkWithIndexer] with the setting, proxy and air-gap rules read when it runs). With
     * no run going for the wallet, one starts now with every waiting outpoint as deeper, and its job is returned. With
     * one going (Home's, the worker's, or another deeper run), the request waits and null is returned: when that run
     * ends, however it ends (finished, failed, cancelled, out of time), a follow-up run starts with what is waiting.
     *
     * One attempt per request: the outpoints a run takes leave the queue when it ends, whatever its outcome, and are never
     * put back, so a coin that keeps failing does not loop; asking again is a new attempt. A request for an outpoint
     * already waiting or in a run is dropped. A taken outpoint that is no longer among the wallet's stored rows (the
     * coins the last run listed: spent since) is dropped, and with none left no run is made. [forget] empties the queue.
     */
    fun requestDeeper(walletName: String, outpoint: String, run: suspend (Set<String>) -> Map<String, InscriptionCheck>): Job? {
        val key = key(walletName)
        synchronized(deeperLock) {
            val queue = deeperQueues.getOrPut(key) { DeeperQueue() }
            queue.run = run
            if(outpoint in queue.inFlight || !queue.waiting.add(outpoint)) {
                return null
            }
            publishDeeper(key)
        }
        return startDeeper(walletName)
    }

    /** Starts a run with the wallet's waiting outpoints when there are some and its guard is free; returns its job, or null. */
    private fun startDeeper(walletName: String): Job? {
        val key = key(walletName)
        val taken: Set<String>
        val run: suspend (Set<String>) -> Map<String, InscriptionCheck>
        synchronized(deeperLock) {
            val queue = deeperQueues[key] ?: return null
            run = queue.run ?: return null
            if(queue.waiting.isEmpty() || !running.add(key)) {
                //Nothing waiting, or a run holds the guard: its end calls here again
                return null
            }
            taken = LinkedHashSet(queue.waiting)
            queue.waiting.clear()
            queue.inFlight = taken
            publishDeeper(key)
        }
        //Called under deeperLock. A drained queue is dropped, runner and all
        val onEnd = {
            val queue = deeperQueues[key]
            if(queue != null && queue.inFlight === taken) {
                queue.inFlight = emptySet()
                if(queue.waiting.isEmpty()) {
                    deeperQueues.remove(key)
                }
                publishDeeper(key)
            }
        }
        beforeDeeperLaunch()
        return launchClaimed(walletName, key, onEnd) {
            //Forgotten (deleted) since its outpoints were taken: nothing is checked or written for it
            if(synchronized(deeperLock) { deeperQueues[key]?.inFlight !== taken }) {
                return@launchClaimed emptyMap()
            }
            val unspent = taken.filterTo(LinkedHashSet()) { it in results(walletName) }
            if(unspent.isEmpty()) emptyMap() else run(unspent)
        }
    }

    /**
     * Checks [utxos], every unspent coin of the wallet, and saves the results, which replace the stored ones (so spent
     * coins drop out). An uncovered coin gets an empty map and no backend call; in air-gapped mode the stored results
     * come back and nothing is asked. Each confirmed coin's block hash is asked once per height; when the server gives
     * none or fails, the coin has no block for this run and its result cannot stand. While it runs, each coin's new
     * result is saved over the stored rows as soon as it is decided (when it differs from what the file holds), so a
     * run that is cancelled or fails keeps what it decided and the rows of the coins it did not reach. [timeUp] stops it
     * between coins (see [TrackerRun.TIME_UP]). [progress] is told (done, total) as it goes, and so is the wallet's
     * [InscriptionChecker.progress] flow, which goes back to null when the run ends, and its [status] flow, which stays.
     *
     * [indexer], when given (with its [indexerSource]), is asked once, before the run, about every coin. Any failure but
     * cancellation (unreachable, a 429, an error, an answer missing a coin or naming another) means no answers for the
     * whole run: the rows are the built-in trace's and [CheckStatus.indexerUnreachable] is set. A source with no
     * reader (the setting names an indexer that could not be opened) counts as unreachable too. The answers are checked
     * against the chain by [TrackerRun.run]; an answer naming more than [MAX_INDEXER_IDS] inscriptions for a coin is cut
     * to that many and the coin counted as carrying. After the run, Inscribed rows without a number get it from the
     * indexer, at most [MAX_NUMBER_CALLS] calls a run. Each row keeps the coin's value, its address position from
     * [positions] (outpoint to chain and index) and the indexer's rune balances for it ([InscriptionCheck.runeList]).
     */
    suspend fun check(
        walletName: String,
        coin: Coin,
        network: Network,
        backend: ChainBackend,
        utxos: List<Utxo>,
        deeper: Set<String> = emptySet(),
        timeUp: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
        indexer: InscriptionIndexer? = null,
        indexerSource: InscriptionCheck.Source? = null,
        positions: Map<String, Pair<Int, Int>> = emptyMap(),
        indexerId: String? = null
    ): Map<String, InscriptionCheck> {
        if(!ChainRules.covers(coin)) {
            return emptyMap()
        }
        if(NetworkPolicy.blocked) {
            return results(walletName)
        }
        require(indexer == null || indexerSource != null) { "An indexer needs its source" }
        if(entry(walletName).unavailable) {
            //The stored results cannot be read just now: a run would write over them, so none is made (the worker retries)
            throw java.io.IOException("The wallet's inscription results cannot be read just now")
        }
        val key = key(walletName)
        //Read before any server call: a run claimed earlier keeps the epoch of its claim
        val epoch = claimedEpochs[key] ?: synchronized(saveLock) { epochs[key] ?: 0 }
        val status = statusFlow(key)
        var unreachable = indexerSource != null && indexer == null
        status.value = CheckStatus(0, utxos.size, unreachable, running = true)
        try {
            //Block hashes kept from earlier runs answer heights deep below the tip at no cost; others are asked once each
            val kept = entry(walletName).blocks
            val blocks = HashMap<Int, String?>()
            val asked = HashMap<Int, String>()
            val coins = utxos.map { utxo ->
                val block = if(utxo.height <= 0) {
                    null
                } else if(blocks.containsKey(utxo.height)) {
                    blocks[utxo.height]
                } else {
                    (kept[utxo.height] ?: confirmingBlock(backend, utxo.height)?.also { asked[utxo.height] = it }).also { blocks[utxo.height] = it }
                }
                TrackerRun.Coin(utxo.txHash, utxo.txPos, block)
            }
            val keep = keptBlocks(backend, kept, asked, utxos.map { it.height }.toSet())

            //The indexer, once and within the run's time. A reader that can only add holds is asked only about the coins the
            //stored results leave undecided (see [toAsk]); the others have no answer, exactly as if it had said "unknown".
            val before = entry(walletName)
            val holdsOnly = indexer != null && !runesAwareIndexer(coin, indexer)
            //"Unknown" times belong to the indexer that gave them: another one is asked as if never asked
            val askedBefore = if(before.askedFor == indexerId) before.asked else emptyMap()
            val ask = if(indexer == null) emptyList() else toAsk(coins.map { it.outpoint }, before, askedBefore, holdsOnly, deeper)
            val got = when {
                indexer == null -> null
                ask.isEmpty() -> emptyMap()
                else -> indexerOutputs(indexer, ask, timeUp, holdsOnly)
            }
            val outputs = got?.let { usable(it, coin, indexer!!) }
            if(indexer != null && ask.isNotEmpty() && got == null) {
                unreachable = true
            }
            //Cut short with only some answers: not unreachable, but said (the coins not reached keep their own rows)
            val partial = got != null && got.size < ask.size
            status.value = CheckStatus(0, coins.size, unreachable, running = true, indexerPartial = partial)
            val overCap = outputs.orEmpty().filterValues { it.inscriptions.size > MAX_INDEXER_IDS }.keys
            val answers = outputs.orEmpty().mapValues { (_, output) -> capped(output).toAnswer() }
            //The source goes to the run only when some answer can be used (see [stamped] for the rows it was not used for)
            val answeredBy = if(outputs.orEmpty().values.any { it.indexed }) indexerSource else null
            val listed = utxos.associateBy { "${it.txHash}:${it.txPos}" }
            //The coins a loss of the stored results leaves unresolved (see [lostCoinsAfter]): those still open, plus every
            //coin listed while any is (all of them in the first run after the loss)
            //(a row written with the lost reason before the set was kept counts as unresolved too)
            val openBefore = before.lostCoins + before.checks.values.filter { it.reason == LOST_REASON }.map { it.outpoint }
            val lostBefore = if(before.lost || openBefore.isNotEmpty()) openBefore + listed.keys else emptySet()
            val runeLike = ChainRules.hasRuneLikeTokens(coin)
            val lostApplies = { op: String -> op in lostBefore && !resolvesLost(op, outputs, deeper, indexerSource, runeLike) }

            val chain = "${coin.id}:${network.id}"
            val shown = flow(key)
            var marks: Map<String, KeptMark> = emptyMap()
            var unknownAt: Map<String, Long> = emptyMap()
            var lostCoins: Set<String> = emptySet()
            var finalState: Stored? = null
            val checks = locks.computeIfAbsent(chain) { Mutex() }.withLock {
                val storedEntry = entry(walletName)
                val stored = storedEntry.checks
                val storedMarks = storedEntry.marks
                val stamp = { check: InscriptionCheck ->
                    stamped(check, stored[check.outpoint], storedMarks[check.outpoint], listed, positions, outputs, overCap, lostApplies)
                }
                //What the file holds: the stored rows, each replaced by the coin's new result once decided
                val saved = LinkedHashMap(stored)
                val report = { done: Int, total: Int ->
                    shown.value = done to total
                    status.value = CheckStatus(done, total, unreachable, running = true, indexerPartial = partial)
                    progress(done, total)
                }
                try {
                    if(coins.isNotEmpty()) {
                        report(0, coins.size)
                    }
                    val run = TrackerRun.run(
                        coin, network, caches.computeIfAbsent(chain) { TxCache() }, BackendChainSource(backend), coins, stored,
                        answers, answeredBy, deeper, TrackerRun.RUN_BUDGET, timeUp, { outpoint, decided ->
                            val check = stamp(decided)
                            if(saved[outpoint] != check) {
                                saved[outpoint] = check
                                persist(walletName, key, epoch, storedEntry.copy(checks = HashMap(saved), checked = true, blocks = keep))
                            }
                        }, report
                    )
                    val checks: Map<String, InscriptionCheck> = run.mapValuesTo(LinkedHashMap()) { (_, decided) -> stamp(decided) }
                    //The final save drops spent coins, keeping the indexer marks of the ones that were only left out, when a
                    //holds-only indexer answered "unknown" for the listed coins, and whether this run could reach its indexer
                    marks = marksToKeep(stored, storedMarks, listed.keys, now())
                    unknownAt = askedToKeep(if(holdsOnly) askedBefore else storedEntry.asked, listed.keys, if(holdsOnly) got else null, now())
                    val askedFor = if(holdsOnly) indexerId else storedEntry.askedFor
                    lostCoins = lostCoinsAfter(lostBefore, checks, outputs, deeper, indexerSource, runeLike)
                    val inMemory = memory[key]
                    if(checks != saved || inMemory?.checked != true || inMemory.blocks != keep || inMemory.marks != marks ||
                        inMemory.asked != unknownAt || inMemory.askedFor != askedFor || inMemory.lost || inMemory.lostCoins != lostCoins ||
                        inMemory.lastUnreachable != unreachable || (inMemory.lastPartial == true) != partial) {
                        finalState = Stored(checks, true, keep, marks, unknownAt, false, unreachable, now(), lostCoins, askedFor, partial)
                        persist(walletName, key, epoch, finalState!!)
                    } else {
                        finalState = inMemory
                    }
                    checks
                } finally {
                    shown.value = null
                }
            }
            //Numbers are display only: asked outside the chain's lock, so another wallet's check is not held up
            if(indexer == null || outputs == null) {
                return checks
            }
            val filled = fillNumbers(indexer, checks, timeUp)
            if(filled != checks) {
                persist(walletName, key, epoch, finalState!!.copy(checks = filled, lastRunAt = now()))
            }
            return filled
        } finally {
            status.value = status.value?.copy(running = false)
        }
    }

    /**
     * The coins to ask a reader about. A reader that can vouch for "nothing" (runes-aware, see [runesAwareIndexer]) is
     * asked about every coin. One that can only add holds (Nintondo, or an ord server off Litecoin) is asked only about
     * coins the stored results leave undecided for it: not a coin found Inscribed or already held by an indexer's mark
     * (its row's or a kept one), nor one it answered "unknown" for less than [ASKED_FRESH_MS] ago. A coin is asked again
     * when it is new, when that window has passed, or when it is checked deeper. An unasked coin has no answer, exactly as
     * an unknown one: nothing is ever made Clean by not asking.
     */
    private fun toAsk(outpoints: List<String>, before: Stored, asked: Map<String, Long>, holdsOnly: Boolean, deeper: Set<String>): List<String> {
        if(!holdsOnly) return outpoints
        val freshFrom = now() - ASKED_FRESH_MS
        return outpoints.filter { op ->
            val row = before.checks[op]
            val decided = row?.verdict == Verdict.INSCRIBED || (row != null && markOf(row, 0) != null) || op in before.marks
            op in deeper || (!decided && (asked[op]?.let { it < freshFrom } ?: true))
        }
    }

    /**
     * Whether this run resolves the loss of the stored results for [op]: an indexer gave an indexed answer for it; or, on a
     * chain without rune-like tokens (Bellscoin), where a trace is enough to free a coin under Send's rules, it was checked
     * deeper or the setting is Built-in. On a chain with rune-like tokens a trace cannot see what the lost marks held, so
     * only an indexer answer resolves it (Check deeper and Built-in do not).
     */
    private fun resolvesLost(op: String, outputs: Map<String, IndexerOutput>?, deeper: Set<String>, indexerSource: InscriptionCheck.Source?,
                             runeLike: Boolean): Boolean =
        outputs?.get(op)?.indexed == true || (!runeLike && (op in deeper || indexerSource == null))

    /**
     * The coins the loss still leaves unresolved after this run: of [lostBefore] (every coin listed in the first run after
     * the loss, and every coin listed while any is unresolved), those still listed that this run neither resolved (see
     * [resolvesLost]) nor found Inscribed. Empty once each is resolved: a coin first listed after that is an ordinary coin.
     */
    private fun lostCoinsAfter(lostBefore: Set<String>, checks: Map<String, InscriptionCheck>, outputs: Map<String, IndexerOutput>?,
                               deeper: Set<String>, indexerSource: InscriptionCheck.Source?, runeLike: Boolean): Set<String> =
        lostBefore.filterTo(LinkedHashSet()) { op ->
            val row = checks[op]
            row != null && row.verdict != Verdict.INSCRIBED && !resolvesLost(op, outputs, deeper, indexerSource, runeLike)
        }

    /**
     * The indexer's answers for [outpoints], or null for none at all (any failure but cancellation, or an answer that
     * leaves out an outpoint or gives one under another's key: half an answer set is never mixed into a run), within the
     * run's time ([timeUp]) and [indexerDeadlineMs].
     *
     * A runes-aware reader ([holdsOnly] false) is asked in one pass, and a pass cut short gives no answers at all. A reader
     * that can only add holds is asked in pieces (a coin at a time when it [InscriptionIndexer.asksOneAtATime]), and a
     * pass cut short by the time limit or by an error keeps the CARRYING answers it received before the cut (a hold is
     * always safe);
     * every other answer it received counts as unknown (an indexed "nothing" from a cut-short pass is dropped), and the
     * coins not reached have none. A cut with nothing received is no answers at all (the indexer is unreachable).
     */
    private suspend fun indexerOutputs(indexer: InscriptionIndexer, outpoints: List<String>, timeUp: () -> Boolean,
                                       holdsOnly: Boolean): Map<String, IndexerOutput>? {
        val asked = outpoints.distinct()
        val deadline = System.nanoTime() + indexerDeadlineMs * 1_000_000L
        val expired = { timeUp() || System.nanoTime() >= deadline }
        val received = LinkedHashMap<String, IndexerOutput>()
        val pieces = if(!holdsOnly) listOf(asked) else asked.chunked(if(indexer.asksOneAtATime) 1 else HOLDS_PIECE)
        //What a pass cut short (the time limit, or an error: a failed request, a reader closed by a settings change, an
        //answer for other outpoints) keeps from a holds-only reader: its carrying answers, the rest as unknown
        val cutShort = { if(!holdsOnly || received.isEmpty()) null else received.mapValues { (op, output) -> if(output.carries) output else IndexerOutput.unknown(op) } }
        for(piece in pieces) {
            val answered = try {
                stopWhenTimeUp(expired) { indexer.outputs(piece) }
            } catch(e: CancellationException) {
                throw e
            } catch(e: Exception) {
                return cutShort()
            }
            if(answered == null) {
                //Given up: closing the reader ends a request still blocked on its socket
                indexer.close()
                return cutShort()
            }
            if(piece.any { answered[it]?.outpoint != it }) {
                return cutShort()
            }
            for(op in piece) received[op] = answered.getValue(op)
        }
        return received
    }

    /**
     * When a holds-only indexer last answered "unknown" for each listed coin: the stored times of coins still listed,
     * with this run's [answers] (from a holds-only reader, else null) applied: an unknown answer is timed [now], a carrying
     * one is no longer "unknown".
     */
    private fun askedToKeep(stored: Map<String, Long>, listed: Set<String>, answers: Map<String, IndexerOutput>?, now: Long): Map<String, Long> {
        val asked = HashMap(stored.filterKeys { it in listed })
        for((op, output) in answers.orEmpty()) {
            if(output.carries) asked.remove(op) else asked[op] = now
        }
        return asked
    }

    /**
     * The answers as the run may use them (rule 2 of the Send rules, applied to the check too): an indexed answer that
     * carries nothing counts only from a runes-aware reader ([runesAwareIndexer]: Litecoin's ord with its rune index).
     * Elsewhere (an ord server on Dogecoin, Pepecoin, Junkcoin or Bellscoin cannot see dunes or other tokens, nor vouch for
     * a "may carry runes" row) it goes in as not indexed, so it never clears a "may carry runes" row, an indexer's mark,
     * or stands over a trace cut short; the trace decides. Answers that carry something always go in. Read after the
     * reader's outputs call, which refreshes its rune index flag.
     */
    private fun usable(outputs: Map<String, IndexerOutput>, coin: Coin, indexer: InscriptionIndexer): Map<String, IndexerOutput> {
        if(runesAwareIndexer(coin, indexer)) {
            return outputs
        }
        return outputs.mapValues { (outpoint, output) -> if(output.indexed && !output.carries) IndexerOutput.unknown(outpoint) else output }
    }

    /** An output naming more inscriptions than [MAX_INDEXER_IDS]: the first that many, and the rest counted as unlisted. */
    private fun capped(output: IndexerOutput): IndexerOutput =
        if(output.inscriptions.size <= MAX_INDEXER_IDS) output
        else output.copy(inscriptions = output.inscriptions.take(MAX_INDEXER_IDS), unlisted = true)

    /**
     * A row with this run's facts about its coin: its value and address position from the coin list, and the indexer's
     * rune balances. [before] is the coin's stored row from before this run.
     *
     * An indexer's mark that the coin carries something ([InscriptionCheck.runes], which Send holds; or an Inscribed row an
     * indexer decided) stays on the coin until an indexed answer for it says otherwise: with no usable indexed answer
     * this run (indexer down, Built-in, a run cut short, an answer that cannot vouch) the stored mark and rune balances
     * are carried over for the same coin (its block unchanged, or not known on either side). Only an indexed "nothing"
     * that may vouch (see [usable]) clears it; off Litecoin no answer may, so the mark stays until the coin is spent. An
     * indexed answer that carries marks a row that is neither Inscribed nor marked (an indexer and chain disagreement
     * over inscriptions only), and so does an answer over the inscription cap.
     *
     * The row names an indexer as its source only when an answer was used for it: a new row decided with no usable answer
     * (a held row of a run cut short) is Built-in. A stored row standing unchanged keeps its source.
     */
    private fun stamped(check: InscriptionCheck, before: InscriptionCheck?, kept: KeptMark?, listed: Map<String, Utxo>,
                        positions: Map<String, Pair<Int, Int>>, outputs: Map<String, IndexerOutput>?, overCap: Set<String>,
                        lostApplies: (String) -> Boolean): InscriptionCheck {
        val outpoint = check.outpoint
        val position = positions[outpoint]
        val answer = outputs?.get(outpoint)?.takeIf { it.indexed }
        //The txid fixes what the coin carries, whatever block it is in (or a server says it is in): the mark does not
        //depend on the block
        val mark = before?.let { markOf(it, 0) } ?: kept
        var row = check.copy(
            runeList = answer?.runes ?: before?.runeList ?: mark?.runeList,
            value = listed[outpoint]?.value,
            chain = position?.first,
            index = position?.second
        )
        val cleared = answer != null && !answer.carries
        if(!row.runes && !cleared && mark != null && (mark.runes || row.verdict != Verdict.INSCRIBED)) {
            row = row.copy(runes = true)
        }
        if(!row.runes && answer != null && answer.carries && row.verdict != Verdict.INSCRIBED) {
            row = row.copy(runes = true)
        }
        if(!row.runes && outpoint in overCap) {
            row = row.copy(runes = true)
        }
        val unchanged = before != null && check.copy(mwebPegOut = before.mwebPegOut) == before
        if(answer == null && row.source in INDEXER_SOURCES && !unchanged) {
            row = row.copy(source = InscriptionCheck.Source.BUILT_IN)
        }
        //The wallet's earlier results were lost (and with them any indexer mark): a coin the loss leaves unresolved (see
        //[lostCoinsAfter]) is never written Clean, whatever produced its row, until the loss is resolved for it
        if(lostApplies(outpoint) && row.verdict == Verdict.CLEAN) {
            row = row.copy(verdict = Verdict.UNCHECKED, source = InscriptionCheck.Source.BUILT_IN, found = emptyList(), reason = LOST_REASON,
                open = emptyList(), mwebPegOut = null)
        }
        return row
    }

    /**
     * The indexer's mark a row carries, if any, as a [KeptMark] missing since [since]: its runes mark (taken over by the
     * coin's next row whatever it says), or an Inscribed result an indexer gave (taken over only by a row that is no
     * longer Inscribed: an Inscribed row is held as it is, and marking it would keep it from ever standing).
     */
    private fun markOf(row: InscriptionCheck, since: Long): KeptMark? = when {
        row.runes -> KeptMark(true, row.runeList, since)
        row.verdict == Verdict.INSCRIBED && row.source in INDEXER_SOURCES -> KeptMark(false, row.runeList, since)
        else -> null
    }

    /**
     * The indexer marks to keep for outpoints this finished run did not list ([listed]): those already kept and still
     * missing (one more run missed), plus the marks of stored rows whose coins are missing now (since [now], one run). A
     * coin can drop out of one listing and come back (an Electrum server leaves out outputs spent in its mempool, and that
     * spend can be evicted or replaced), so a mark is dropped only by its bound: missing for more than [MARK_KEPT_MS] AND
     * for at least [MIN_MISSING_RUNS] runs (so a clock jump alone drops nothing), or past the newest [MAX_KEPT_MARKS]
     * (ties in outpoint order, so the same marks stay every time).
     */
    private fun marksToKeep(stored: Map<String, InscriptionCheck>, kept: Map<String, KeptMark>, listed: Set<String>, now: Long): Map<String, KeptMark> {
        val marks = HashMap<String, KeptMark>()
        for((outpoint, mark) in kept) {
            if(outpoint !in listed) marks[outpoint] = mark.copy(missingRuns = mark.missingRuns + 1)
        }
        for(row in stored.values) {
            if(row.outpoint !in listed && row.outpoint !in marks) markOf(row, now)?.let { marks[row.outpoint] = it.copy(missingRuns = 1) }
        }
        return marks.entries.filter { now - it.value.since <= MARK_KEPT_MS || it.value.missingRuns < MIN_MISSING_RUNS }
            .sortedWith(compareByDescending<Map.Entry<String, KeptMark>> { it.value.since }.thenBy { it.key })
            .take(MAX_KEPT_MARKS).associate { it.key to it.value }
    }

    /** What Send reads of a wallet's results: its rows (null when never checked) and its kept marks, from one read. */
    class SendSnapshot(val rows: Map<String, InscriptionCheck>?, val marks: Map<String, KeptMark>)

    /** The wallet's rows and kept marks for Send, from one read of its stored results. Reads the file the first time. */
    fun sendSnapshot(walletName: String): SendSnapshot = entry(walletName).let { SendSnapshot(it.checks.takeIf { _ -> it.checked }, it.marks) }

    /** The indexer marks kept for the wallet's outpoints its last listing left out (not coins). Reads the file the first time. */
    fun keptMarks(walletName: String): Map<String, KeptMark> = entry(walletName).marks

    /** One inscription lookup's answer, so a lookup that gives up at the deadline is told from one that found nothing. */
    private class Looked(val inscription: IndexerInscription?)

    /**
     * Fills the indexer's number into Inscribed rows' finds that have none, at most [MAX_NUMBER_CALLS] calls and within
     * [numberFillDeadlineMs] in all; an answer for another id or without a number leaves it unset. The first failure, the
     * deadline, or [timeUp], ends the fills for this run. Cancellation is rethrown. Only the number changes: nothing here
     * decides a coin.
     */
    private suspend fun fillNumbers(indexer: InscriptionIndexer, checks: Map<String, InscriptionCheck>,
                                    timeUp: () -> Boolean): Map<String, InscriptionCheck> {
        val filled = LinkedHashMap(checks)
        val deadline = System.nanoTime() + numberFillDeadlineMs * 1_000_000L
        var calls = 0
        var stopped = false
        for((outpoint, check) in checks) {
            if(stopped) break
            if(check.verdict != Verdict.INSCRIBED || check.found.none { it.number == null }) continue
            val found = check.found.toMutableList()
            for((i, f) in check.found.withIndex()) {
                if(f.number != null || !INSCRIPTION_ID.matches(f.inscriptionId)) continue
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if(calls >= MAX_NUMBER_CALLS || left <= 0 || timeUp()) {
                    stopped = true
                    break
                }
                calls++
                val looked = try {
                    stopWhenTimeUp({ System.nanoTime() >= deadline || timeUp() }) { Looked(indexer.inscription(f.inscriptionId)) }
                } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    null
                }
                if(looked == null) {
                    //Failed, or given up at the deadline: the reader is not used again this run, and closing it ends a
                    //request still blocked on its socket
                    indexer.close()
                    stopped = true
                    break
                }
                val answer = looked.inscription
                if(answer != null && answer.id == f.inscriptionId && answer.number != null) {
                    found[i] = f.copy(number = answer.number)
                }
            }
            if(found != check.found) {
                filled[outpoint] = check.copy(found = found)
            }
        }
        return filled
    }

    /**
     * Writes the wallet's file, then its copy in memory, unless the wallet was forgotten (deleted) since the run began.
     * Each write is atomic (see [InscriptionStore.save]).
     */
    private fun persist(walletName: String, key: String, epoch: Int, state: Stored) {
        synchronized(saveLock) {
            if((epochs[key] ?: 0) != epoch) {
                return
            }
            store.save(stored(walletName), StoredFile(state.checks, state.blocks, state.marks, state.asked, state.lost,
                state.lastUnreachable, state.lastRunAt, state.lostCoins, state.askedFor, state.lastPartial))
            memory[key] = state
        }
    }

    /**
     * The block hashes to keep with the results: those of [heights] still in use that were kept before, plus the ones
     * [asked] this run at heights more than [DEEP_BLOCKS] below the tip (a reorg that deep is not expected). The tip is
     * read only when there is a newly asked height to judge; if it cannot be read none is added. A rate limit there
     * ends the run like any other.
     */
    private suspend fun keptBlocks(backend: ChainBackend, kept: Map<Int, String>, asked: Map<Int, String>, heights: Set<Int>): Map<Int, String> {
        val keep = HashMap(kept.filterKeys { it in heights })
        if(asked.isEmpty()) {
            return keep
        }
        val tip = try {
            withContext(NoRateLimitWait) { backend.tipHeight() }
        } catch(e: CancellationException) {
            throw e
        } catch(e: RateLimitedException) {
            throw e
        } catch(e: Exception) {
            return keep
        }
        for((height, hash) in asked) {
            if(height < tip - DEEP_BLOCKS) {
                keep[height] = hash
            }
        }
        return keep
    }

    companion object {
        /** How far below the tip a block must be for its hash to be kept with the results. */
        const val DEEP_BLOCKS = 100

        /**
         * How long a holds-only indexer's "unknown" for a coin stands before the coin is asked again: 6 hours (a run every
         * sync and a Send pass every preview would otherwise disclose the whole coin set each time).
         */
        const val ASKED_FRESH_MS = 6L * 60 * 60 * 1000

        /** How many coins a holds-only reader is asked about in one request when it takes batches. */
        private const val HOLDS_PIECE = 100

        /** The reason on a coin whose earlier results were lost and that no indexer has answered for since. */
        const val LOST_REASON = "Earlier results were lost; needs the indexer or Check deeper"

        /**
         * The most inscription ids taken from one indexer answer for one coin, as the per-coin trace budget
         * ([com.sparrowwallet.mobile.crypto.track.InscriptionTracer.DEFAULT_BUDGET]); more and the coin counts as carrying.
         */
        const val MAX_INDEXER_IDS = 100

        /** The most inscription number lookups one run makes. */
        const val MAX_NUMBER_CALLS = 20

        /** How long one run's number lookups may take in all. */
        const val NUMBER_FILL_DEADLINE_MS = 30_000L

        /** How long an indexer's mark is kept for an outpoint the listings leave out. */
        const val MARK_KEPT_MS = 30L * 24 * 60 * 60 * 1000

        /** The fewest finished runs a kept mark must have been missing from before [MARK_KEPT_MS] may drop it. */
        const val MIN_MISSING_RUNS = 10

        /** The most indexer marks kept for outpoints the listings leave out (the newest). */
        const val MAX_KEPT_MARKS = 1_000

        /** The sources that name an indexer. */
        private val INDEXER_SOURCES = setOf(InscriptionCheck.Source.PUBLIC, InscriptionCheck.Source.YOUR_OWN)

        /** An ord inscription id, as the indexer readers take it; any other (an unnamed "?" find) is not looked up. */
        private val INSCRIPTION_ID = Regex("[0-9a-f]{64}i[0-9]{1,10}")
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
        claim(key)
        try {
            return work()
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            return null
        } finally {
            claimedEpochs.remove(key)
            running.remove(key)
            startDeeper(walletName)
        }
    }

    private fun claim(key: String) {
        synchronized(saveLock) {
            claimedEpochs[key] = epochs[key] ?: 0
        }
    }
}

/**
 * A wallet's check run with the indexer the [setting] names: lists [listed]'s coins with their address positions, opens
 * the reader with [open] (in the app [Indexers.open], through the coin's [proxy]; none on Built-in, before the notice is
 * answered, or [airGapped]; within the run's time), lends it to [readers] so a settings change closes it mid-open or
 * mid-run, runs [InscriptionChecker.check], and closes it. [generation] is the readers' generation the caller took before
 * reading [setting] and [proxy]. Home's runs and the background worker's both come here.
 */
internal suspend fun checkWithIndexer(checker: InscriptionChecker, walletName: String, coin: Coin, network: Network, backend: ChainBackend,
                                      listed: List<WalletScanner.ChainUtxo>, setting: InscriptionSetting, proxy: SocksProxy?,
                                      airGapped: Boolean, readers: IndexerReaders, deeper: Set<String> = emptySet(),
                                      timeUp: () -> Boolean = { false },
                                      generation: Int = readers.generation,
                                      open: suspend (String, Coin, Network, SocksProxy?) -> InscriptionIndexer =
                                          { url, c, n, p -> Indexers.open(url, c, n, p) }): Map<String, InscriptionCheck> {
    val utxos = listed.map { it.utxo }
    val positions = listed.associate { "${it.utxo.txHash}:${it.utxo.txPos}" to (it.chain to it.index) }
    val picked = when {
        utxos.isEmpty() || !ChainRules.covers(coin) -> CheckIndexer(null, null)
        //The setting and proxy were read before a settings change ([generation] is the one taken before reading them):
        //open nothing over them, and count the indexer as unreachable for this run
        generation != readers.generation -> CheckIndexer(null, checkSource(setting, coin, network, airGapped))
        else -> openCheckIndexer(setting, coin, network, proxy, airGapped, timeUp, readers.opening, open)
    }
    //A reader opened across a settings change is closed at once: the run goes on as if it could not be reached
    val reader = picked.reader?.takeIf { readers.lend(it, generation) }
    try {
        return checker.check(walletName, coin, network, backend, utxos, deeper, timeUp, indexer = reader,
            indexerSource = picked.source, positions = positions,
            indexerId = setting.url(coin, network)?.let { "${coin.id}:${network.id}:$it" })
    } finally {
        reader?.let {
            readers.giveBack(it)
            it.close()
        }
    }
}

/**
 * The Check deeper gate the repository uses: an MWEB wallet, a coin the check does not cover, or air-gapped mode queue
 * nothing (no row is left showing Queued for a run that never comes). Otherwise asks [checker] (see
 * [InscriptionChecker.requestDeeper]); returns whether it was asked.
 */
internal fun requestDeeperIfAllowed(checker: InscriptionChecker, walletName: String, coin: Coin, scriptType: String, airGapped: Boolean,
                                    outpoint: String, run: suspend (Set<String>) -> Map<String, InscriptionCheck>): Boolean {
    if(!deeperCheckAllowed(coin, scriptType, airGapped)) {
        return false
    }
    checker.requestDeeper(walletName, outpoint, run)
    return true
}

/** Whether a Check deeper may be asked for: a public wallet on a covered coin, not air-gapped. */
internal fun deeperCheckAllowed(coin: Coin, scriptType: String, airGapped: Boolean): Boolean =
    scriptType != "MWEB" && ChainRules.covers(coin) && !airGapped
