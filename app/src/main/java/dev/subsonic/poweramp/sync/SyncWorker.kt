package dev.subsonic.poweramp.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.server.ServerException
import dev.subsonic.poweramp.ui.describeConnectionError
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Runs one library sync. Scheduled periodically, and once on demand ("Sync now", after login). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SubsonicApp
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val server = app.savedServer() ?: return Result.success() // nothing configured

        // After a rejected login, only a manual sync (or saving new credentials) tries again.
        if (app.syncStatus.status.value.authFailed && !manual) return Result.success()

        app.syncStatus.markRunning()
        return try {
            val result = app.librarySync.sync(server)
            Log.i(TAG, "Sync done: ${result.summary()}")
            app.syncStatus.markSuccess(System.currentTimeMillis(), result.summary())
            SyncNotifications.cancelAuthFailed(applicationContext)
            if (result.changed) app.notifyProviderRootsChanged()
            app.powerampRescans.onSyncFinished(result.changed)
            Result.success()
        } catch (e: CancellationException) {
            app.syncStatus.markError("Sync was interrupted. Will retry.", authFailed = false)
            throw e
        } catch (e: ServerException) {
            val authProblem = e.kind.isLoginProblem
            Log.w(TAG, "Sync failed: server refused (${e.kind})", e)
            app.syncStatus.markError(describeConnectionError(e), authFailed = authProblem)
            if (authProblem) {
                SyncNotifications.showAuthFailed(applicationContext)
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (e: IOException) {
            // Network trouble, server busy scanning, suspicious empty result: try again with backoff.
            Log.w(TAG, "Sync failed, will retry", e)
            app.syncStatus.markError(describeConnectionError(e), authFailed = false)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            app.syncStatus.markError("Sync failed: ${e.message ?: e.javaClass.simpleName}", authFailed = false)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val KEY_MANUAL = "manual"
        private const val PERIODIC_WORK = "library-sync-periodic"
        private const val ONE_TIME_WORK = "library-sync-now"

        private fun constraints(settings: SyncSettings) = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        /**
         * (Re)schedules the periodic sync; replaces the existing schedule if settings changed.
         * The first run is delayed by one interval: WorkManager would otherwise run it immediately,
         * duplicating the [syncNow] that always accompanies a new login.
         */
        fun schedule(context: Context, settings: SyncSettings) {
            val interval = settings.intervalHours.toLong()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.HOURS)
                .setInitialDelay(interval, TimeUnit.HOURS)
                .setConstraints(constraints(settings))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Runs a sync as soon as the network allows. A sync already queued is kept, not duplicated. */
        fun syncNow(context: Context, settings: SyncSettings) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(settings))
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC_WORK)
                cancelUniqueWork(ONE_TIME_WORK)
            }
        }
    }
}
