package dev.subsonic.poweramp.stream

import android.net.ConnectivityManager
import android.content.Context
import android.util.Log
import dev.subsonic.poweramp.settings.SavedServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One [StreamSession] per playing track, shared by all of Poweramp's descriptors for it and kept for
 * a short while after the last one closes (Poweramp closes and reopens a track several times as it
 * starts playing).
 */
class StreamSessions(
    private val context: Context,
    private val http: OkHttpClient,
    val cache: AudioCache,
    private val settings: () -> CacheSettings,
) {
    private class Entry(val session: StreamSession, val file: File) {
        var refs = 0
        var closer: ScheduledFuture<*>? = null
    }

    private val sessions = HashMap<String, Entry>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "stream-sessions").apply { isDaemon = true } }

    /** Returns the session for a track, creating it if needed. Pair every call with [release]. */
    @Synchronized
    fun acquire(server: SavedServer, trackId: String, size: Long, url: HttpUrl): StreamSession {
        val file = cache.fileFor(server.identity, trackId, size)
        val key = file.name
        val entry = sessions[key] ?: run {
            cache.touch(file)
            val session = StreamSession(
                trackId = trackId,
                url = url,
                size = size,
                file = SparseFile(file, size),
                http = http,
                readAhead = ::readAheadBytes,
                log = { Log.i(TAG, it) },
            )
            Entry(session, file).also { sessions[key] = it }
        }
        entry.closer?.cancel(false)
        entry.closer = null
        entry.refs++
        return entry.session
    }

    @Synchronized
    fun release(session: StreamSession) {
        val (key, entry) = sessions.entries.firstOrNull { it.value.session === session }?.toPair() ?: return
        entry.refs--
        if (entry.refs > 0) return
        entry.closer = scheduler.schedule({ closeIfUnused(key, entry) }, LINGER_MS, TimeUnit.MILLISECONDS)
    }

    private fun closeIfUnused(key: String, entry: Entry) {
        synchronized(this) {
            if (entry.refs > 0 || sessions[key] !== entry) return
            sessions.remove(key)
        }
        entry.session.close()
        cache.touch(entry.file)
        cache.trim(inUse())
    }

    @Synchronized
    fun inUse(): Set<File> = sessions.values.map { it.file }.toSet()

    /** Applies a lower size limit now rather than after the next track. */
    fun trimCache() = cache.trim(inUse())

    /** Deletes everything not currently playing. */
    fun clearCache() = cache.clear(inUse())

    @Volatile private var readAheadValue = METERED_READ_AHEAD
    @Volatile private var readAheadCheckedAt = 0L

    /**
     * Whole tracks on unmetered networks (so they end up cached); a bounded window on mobile data.
     * Asked for every downloaded chunk, so the network check is cached for a few seconds.
     */
    private fun readAheadBytes(): Long {
        val now = System.currentTimeMillis()
        if (now - readAheadCheckedAt > 5_000) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val unmetered = cm?.isActiveNetworkMetered == false
            readAheadValue = if (unmetered || settings().wholeTracksOnMobile) Long.MAX_VALUE else METERED_READ_AHEAD
            readAheadCheckedAt = now
        }
        return readAheadValue
    }

    private companion object {
        const val TAG = "SubsonicStream"
        const val LINGER_MS = 15_000L
        const val METERED_READ_AHEAD = 8L * 1024 * 1024
    }
}
