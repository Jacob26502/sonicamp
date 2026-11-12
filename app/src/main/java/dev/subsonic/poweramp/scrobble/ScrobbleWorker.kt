package dev.subsonic.poweramp.scrobble

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.subsonic.SubsonicApiException
import dev.subsonic.poweramp.subsonic.SubsonicClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sends queued plays, oldest first, whenever there is a network. */
class ScrobbleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SubsonicApp
        val queue = app.scrobbler.queue
        val server = app.credentialStore.server.value ?: app.credentialStore.load()
        queue.removeOtherServers(server?.identity)
        if (server == null) return Result.success()
        val client = SubsonicClient(server.baseUrl, server.auth, app.httpClient)

        var sent = 0
        // Loop until empty: plays may be queued while this runs (KEEP won't start a second worker).
        while (true) {
            val batch = queue.pending()
            if (batch.isEmpty()) break
            for (item in batch) {
                try {
                    client.scrobble(item.songId, item.playedAt, submission = true)
                    queue.remove(item.id)
                    sent++
                } catch (e: IOException) {
                    Log.i(TAG, "Offline; ${queue.count()} plays wait (sent $sent)")
                    return Result.retry()
                } catch (e: SubsonicApiException) {
                    when {
                        e.code == SubsonicApiException.NOT_FOUND -> queue.remove(item.id) // song deleted from the server
                        e.code in RETRY_LATER_CODES -> {
                            // Login problem: keep the plays; sync shows the error. Retried on the next play.
                            Log.w(TAG, "Server refused scrobbles (code ${e.code}); keeping ${queue.count()} plays")
                            return Result.failure()
                        }
                        item.attempts + 1 >= MAX_ATTEMPTS -> queue.remove(item.id)
                        else -> queue.markAttempt(item.id)
                    }
                }
            }
        }
        if (sent > 0) Log.i(TAG, "Sent $sent plays")
        return Result.success()
    }

    companion object {
        private const val TAG = "ScrobbleWorker"
        private const val WORK = "scrobble-flush"
        private const val MAX_ATTEMPTS = 5

        private val RETRY_LATER_CODES = setOf(
            SubsonicApiException.WRONG_CREDENTIALS,
            SubsonicApiException.TOKEN_AUTH_NOT_SUPPORTED,
            SubsonicApiException.AUTH_MECHANISM_NOT_SUPPORTED,
            SubsonicApiException.INVALID_API_KEY,
            SubsonicApiException.NOT_AUTHORIZED,
        )

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScrobbleWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
