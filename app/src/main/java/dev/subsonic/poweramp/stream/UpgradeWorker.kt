package dev.subsonic.poweramp.stream

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.library.track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Replaces cached transcodes with the original files: tracks played at a lower bitrate (e.g. on
 * mobile data) end up in full quality without replaying them. Runs only on unmetered networks while
 * charging, one track at a time, and never evicts other music to make room.
 */
class UpgradeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as SubsonicApp
        if (!app.cacheSettings.settings.value.autoUpgrade) return@withContext Result.success()
        val server = app.credentialStore.server.value ?: app.credentialStore.load() ?: return@withContext Result.success()
        val cache = app.audioCache
        val sessions = app.streamSessions
        val music = app.musicServers.create(server)

        val trackIds = cache.entries()
            .filter { it.serverIdentity == server.identity && it.variant != StreamSessions.RAW }
            .map { it.trackId }
            .distinct()
        var upgraded = 0
        for (trackId in trackIds) {
            if (isStopped) return@withContext Result.retry()
            val track = app.libraryDatabase.track(trackId)
            val size = track?.sizeBytes?.takeIf { it > 0 }
            if (size == null) {
                // No longer in the library: its copies are dead weight.
                cache.dropOtherVariants(server.identity, trackId, keepVariant = "", inUse = sessions.inUse())
                continue
            }
            val rawFile = cache.fileFor(server.identity, trackId, size, StreamSessions.RAW)
            if (!cache.isComplete(rawFile)) {
                if (cache.totalBytes() + size > app.cacheSettings.settings.value.maxBytes) {
                    Log.i(TAG, "Cache full; stopping after $upgraded upgrades")
                    break
                }
                val source = sessions.originalSource(size) { quality -> music.streamUrl(trackId, quality) }
                val session = sessions.acquire(server, trackId, source)
                val done = try {
                    session.awaitComplete(TRACK_TIMEOUT_MS)
                } finally {
                    sessions.release(session)
                }
                if (!done) {
                    Log.i(TAG, "Upgrade of $trackId didn't finish; will retry")
                    return@withContext Result.retry()
                }
            }
            cache.dropOtherVariants(server.identity, trackId, StreamSessions.RAW, sessions.inUse())
            upgraded++
        }
        if (upgraded > 0) Log.i(TAG, "Upgraded $upgraded tracks to original quality")
        Result.success()
    }

    companion object {
        private const val TAG = "SubsonicUpgrade"
        private const val WORK = "audio-upgrade"
        private const val TRACK_TIMEOUT_MS = 10 * 60_000L

        /** Queues an upgrade run for when the phone is on Wi-Fi and charging. Repeated calls coalesce. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpgradeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
