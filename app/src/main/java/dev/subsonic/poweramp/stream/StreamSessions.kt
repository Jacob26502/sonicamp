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
    /** A track was played as a transcode: an upgrade to the original may be due. */
    private val onTranscodePlayed: () -> Unit = {},
) {
    private class Entry(val session: StreamSession, val file: File, val serverIdentity: String, val source: Source) {
        var refs = 0
        var closer: ScheduledFuture<*>? = null
    }

    private val sessions = HashMap<String, Entry>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "stream-sessions").apply { isDaemon = true } }

    /** What to stream for a track: the original file, or a server transcode. */
    data class Source(val url: HttpUrl, val size: Long, val variant: String, val transcoded: Boolean)

    /**
     * Picks the original or an MP3 transcode per the quality setting for the current network. A
     * fully cached original always wins (best quality, no network), and transcoding is skipped when
     * the original's bitrate is no higher than the target.
     *
     * @param streamUrl builds the stream URL for (maxBitRate kbps, format), both null for the original.
     */
    fun chooseSource(
        server: SavedServer,
        trackId: String,
        originalSize: Long,
        durationSec: Int?,
        originalKbps: Int?,
        streamUrl: (Int?, String?) -> HttpUrl,
    ): Source {
        val original = Source(streamUrl(null, "raw"), originalSize, RAW, transcoded = false)
        val kbps = settings().quality(isMetered())
        if (kbps == CacheSettings.ORIGINAL || durationSec == null || durationSec <= 0) return original
        if (originalKbps != null && originalKbps <= kbps) return original
        if (cache.isComplete(cache.fileFor(server.identity, trackId, originalSize, RAW))) return original
        return Source(
            url = streamUrl(kbps, "mp3"),
            size = StreamSession.mp3Size(durationSec, kbps),
            variant = "mp3-$kbps",
            transcoded = true,
        )
    }

    /** Returns the session for a track, creating it if needed. Pair every call with [release]. */
    @Synchronized
    fun acquire(server: SavedServer, trackId: String, source: Source): StreamSession {
        val file = cache.fileFor(server.identity, trackId, source.size, source.variant)
        val key = file.name
        val entry = sessions[key] ?: run {
            cache.touch(file)
            val session = StreamSession(
                trackId = trackId,
                url = source.url,
                size = source.size,
                file = SparseFile(file, source.size),
                http = http,
                readAhead = ::readAheadBytes,
                log = { Log.i(TAG, it) },
                transcoded = source.transcoded,
            )
            cache.writeMeta(file, server.identity, trackId, source.variant, source.size)
            if (source.transcoded) Log.i(TAG, "Streaming $trackId as ${source.variant}")
            Entry(session, file, server.identity, source).also { sessions[key] = it }
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
        val complete = entry.session.isComplete
        entry.session.close()
        cache.touch(entry.file)
        if (complete && !entry.source.transcoded) {
            // The original is all here: lower-quality copies of the track will never be used again.
            val dropped = cache.dropOtherVariants(entry.serverIdentity, entry.session.trackId, RAW, inUse())
            if (dropped > 0) Log.i(TAG, "Dropped $dropped transcoded copies of ${entry.session.trackId}")
        }
        if (entry.source.transcoded) onTranscodePlayed()
        cache.trim(inUse())
    }

    /** The source for downloading a track's original file (used by [UpgradeWorker]). */
    fun originalSource(trackId: String, size: Long, streamUrl: (Int?, String?) -> HttpUrl) =
        Source(streamUrl(null, "raw"), size, RAW, transcoded = false)

    @Synchronized
    fun inUse(): Set<File> = sessions.values.map { it.file }.toSet()

    /** Applies a lower size limit now rather than after the next track. */
    fun trimCache() = cache.trim(inUse())

    /** Deletes everything not currently playing. */
    fun clearCache() = cache.clear(inUse())

    fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false

    @Volatile private var readAheadValue = METERED_READ_AHEAD
    @Volatile private var readAheadCheckedAt = 0L

    /**
     * Whole tracks on unmetered networks (so they end up cached); a bounded window on mobile data.
     * Asked for every downloaded chunk, so the network check is cached for a few seconds.
     */
    private fun readAheadBytes(): Long {
        val now = System.currentTimeMillis()
        if (now - readAheadCheckedAt > 5_000) {
            readAheadValue = if (!isMetered() || settings().wholeTracksOnMobile) Long.MAX_VALUE else METERED_READ_AHEAD
            readAheadCheckedAt = now
        }
        return readAheadValue
    }

    companion object {
        private const val TAG = "SubsonicStream"
        private const val LINGER_MS = 15_000L
        private const val METERED_READ_AHEAD = 8L * 1024 * 1024
        /** Cache variant of the original file. */
        const val RAW = "raw"
    }
}
