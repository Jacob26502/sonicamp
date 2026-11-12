package dev.subsonic.poweramp.lyrics

import android.util.Log
import dev.subsonic.poweramp.subsonic.Extensions
import dev.subsonic.poweramp.subsonic.ServerFeatures
import dev.subsonic.poweramp.subsonic.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Fetches lyrics when Poweramp asks for them (its Lyrics view queries the provider for the lyrics
 * columns), and caches the answer on disk, including "this song has none", so the next view is instant
 * and works offline.
 */
class LyricsRepository(
    private val dir: File,
    private val features: ServerFeatures,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Entry(val fetchedAt: Long, val lyrics: TrackLyrics)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return the song's lyrics, [TrackLyrics.NONE] if it has none, or null if the server couldn't be
     *   reached and nothing is cached.
     */
    suspend fun get(client: SubsonicClient, songId: String, artist: String?, title: String): TrackLyrics? {
        val file = fileFor(client, songId)
        val cached = read(file)
        if (cached != null && !expired(cached)) return cached.lyrics

        val fresh = try {
            if (features.supports(client.baseUrl.toString(), client, Extensions.SONG_LYRICS)) {
                LyricsFormat.fromStructured(client.lyricsBySongId(songId))
            } else {
                LyricsFormat.fromPlain(client.lyrics(artist, title))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lyrics for $songId unavailable: ${e.javaClass.simpleName}")
            return cached?.lyrics // stale beats nothing
        }
        write(file, Entry(clock(), fresh))
        return fresh
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun expired(e: Entry): Boolean {
        val ttl = if (e.lyrics.isEmpty) NONE_TTL_MS else FOUND_TTL_MS
        return clock() - e.fetchedAt > ttl
    }

    /** Keyed by server and song, hashed so file names never contain server ids verbatim. */
    private fun fileFor(client: SubsonicClient, songId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("${client.baseUrl}\u0000$songId".toByteArray())
        return File(dir, digest.take(16).joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun read(file: File): Entry? = try {
        if (file.exists()) json.decodeFromString(Entry.serializer(), file.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun write(file: File, entry: Entry) {
        try {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(json.encodeToString(Entry.serializer(), entry))
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache lyrics", e)
        }
    }

    private companion object {
        const val TAG = "SubsonicLyrics"
        /** Lyrics files change rarely; "none" is re-checked sooner in case lyrics are added. */
        const val FOUND_TTL_MS = 30L * 24 * 60 * 60_000
        const val NONE_TTL_MS = 3L * 24 * 60 * 60_000
    }
}
