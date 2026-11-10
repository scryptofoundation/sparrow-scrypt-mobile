package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/**
 * Periodic background check for new funds, using only the Keystore-cached view keys
 * (see [SyncStore]) — no wallet password, no spend capability. Public wallets are
 * polled over Electrum from the cached xpub; MWEB wallets through the on-device
 * scanner from the cached scan key. The first run per wallet records a baseline;
 * later runs notify on anything new.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // WorkManager can wake the process without the activity, so read the flag here too
        AirGapMode.load(applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE))
        runChecks(applicationContext)
        return Result.success()
    }

    companion object {
        const val CHANNEL = "wallet-activity"
        const val WORK_NAME = "wallet-sync"
        private const val NOTIFICATION_ID = 1001

        /** One pass over every cached wallet; false when nothing ran. */
        internal suspend fun runChecks(context: Context): Boolean {
            if(NetworkPolicy.blocked) return false
            val startedAt = System.currentTimeMillis()
            val store = SyncStore(context)
            val repo = WalletRepository(context)
            val received = mutableListOf<String>()
            val scanned = HashSet<String>() // public wallets whose history scan succeeded this pass

            for(creds in store.loadAll()) {
                try {
                    val network = Network.fromId(creds.network)
                    if(creds.scriptType == "MWEB") {
                        val scanSecret = creds.scanSecretHex ?: continue
                        // give the scanner a bounded window to finish its catch-up pass
                        var snap = repo.mwebSnapshotFromScanKey(network, scanSecret, creds.birthHeight)
                        val deadline = System.currentTimeMillis() + 6 * 60_000
                        while(!snap.initialScanComplete && System.currentTimeMillis() < deadline) {
                            delay(10_000)
                            snap = repo.mwebSnapshotFromScanKey(network, scanSecret, creds.birthHeight)
                        }
                        if(!snap.initialScanComplete) continue // not caught up — next period retries
                        val unspent = snap.utxos.filter { !it.spent }
                        val fresh = SyncBaseline.newlyReceivedPrivate(creds, unspent.map { it.outputId to it.value })
                        if(fresh > 0) received += "${formatAmount(Coin.LITECOIN, fresh)} → ${creds.name} (private)"
                        store.save(creds.copy(baselined = true, knownOutputIds = unspent.map { it.outputId }))
                    } else {
                        if(creds.xpub == null) continue
                        val snap = repo.scanXpub(creds)
                        val fresh = SyncBaseline.newlyReceived(creds, snap.history.map { it.txid to it.delta })
                        if(fresh > 0) received += "${formatAmount(Coin.fromId(creds.coin), fresh)} → ${creds.name}"
                        //read before the baseline below moves on: a transaction it lacks makes the inscription check due
                        val due = SyncBaseline.inscriptionCheckDue(creds, snap.history.map { it.txid })
                        store.save(creds.copy(baselined = true, knownTxids = snap.history.map { it.txid }, inscriptionCheckDue = due))
                        scanned += creds.name
                    }
                } catch(t: Throwable) {
                    // per-wallet best effort — a dead server or offline scanner retries next period
                }
            }

            if(received.isNotEmpty() && store.notifyEnabled) {
                notifyReceived(context, received)
            }

            // Then the inscription check of each public wallet whose scan just succeeded, after the funds notice so a
            // long check cannot hold it back. No notification; a failed check leaves what it saved and never fails or
            // retries the worker. The pass keeps inside WorkManager's ten-minute window (see inscriptionPass). A wallet
            // runs its check only when needed: something new, no results yet, or a row worth retrying.
            val wallets = store.loadAll().filter {
                it.scriptType != "MWEB" && it.xpub != null && it.name in scanned &&
                    inscriptionCheckNeeded(it.inscriptionCheckDue, repo.storedInscriptionResults(it.name))
            }
            inscriptionPass(wallets, startedAt, System::currentTimeMillis) { creds, timeUp ->
                if(repo.checkInscriptionsFromXpub(creds, timeUp) != null) {
                    store.load(creds.name)?.takeIf { it.inscriptionCheckDue }?.let { store.save(it.copy(inscriptionCheckDue = false)) }
                }
            }
            return true
        }

        /** No new wallet's inscription check starts once the worker has run this long. */
        internal const val START_LIMIT_MS = 5 * 60_000L

        /** A running check stops between coins (saving what it decided) once the worker has run this long. */
        internal const val CHECK_DEADLINE_MS = 8 * 60_000L

        /**
         * Runs [check] for each wallet in turn, from a worker that started at [startedAtMs]: none starts after
         * [START_LIMIT_MS], and each is handed a timeUp that turns true at [CHECK_DEADLINE_MS]. One wallet's check that
         * fails or is cancelled (a timeout inside it) moves on to the next; a cancelled worker stops the pass.
         */
        internal suspend fun <T> inscriptionPass(wallets: List<T>, startedAtMs: Long, now: () -> Long,
                                                 check: suspend (T, timeUp: () -> Boolean) -> Unit) {
            val timeUp = { now() - startedAtMs >= CHECK_DEADLINE_MS }
            for(wallet in wallets) {
                if(now() - startedAtMs >= START_LIMIT_MS) break
                try {
                    check(wallet, timeUp)
                } catch(e: kotlinx.coroutines.CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch(t: Throwable) {
                    // checkInscriptionsFromXpub already swallows failures; this keeps the worker safe regardless
                }
            }
        }

        private fun notifyReceived(context: Context, lines: List<String>) {
            if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Wallet activity", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(if(lines.size == 1) "Litecoin received" else "Litecoin received (${lines.size})")
                .setContentText(lines.joinToString("; "))
                .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        /** (Re)schedules the periodic check; 0 minutes cancels it. Android's floor is 15. */
        fun schedule(context: Context, minutes: Int) {
            if(NetworkPolicy.blocked) return
            val workManager = WorkManager.getInstance(context)
            if(minutes <= 0) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context) {
            if(NetworkPolicy.blocked) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build()
            )
        }
    }
}
