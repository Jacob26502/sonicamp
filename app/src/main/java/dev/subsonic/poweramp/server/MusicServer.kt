package dev.subsonic.poweramp.server

import dev.subsonic.poweramp.lyrics.TrackLyrics
import dev.subsonic.poweramp.sync.LibraryRecord
import dev.subsonic.poweramp.sync.PlaylistRecord
import dev.subsonic.poweramp.sync.TrackRecord
import okhttp3.HttpUrl

/**
 * Everything the app needs from a music server. The library database, Poweramp provider, streaming,
 * cache, lyrics cache and scrobble queue only use this interface; [SubsonicServer] implements it for
 * Subsonic/OpenSubsonic servers, and another server type (e.g. Jellyfin) would add its own
 * implementation plus a [ServerType] and a case in [MusicServers.create].
 *
 * Calls throw [ServerException] when the server refuses a request, [UnexpectedResponseException]
 * when the address isn't a music server, and other [java.io.IOException]s for network trouble.
 *
 * Ids (tracks, albums, artists, playlists, cover art) are the server's own, as strings; they must
 * stay stable across syncs. Library ids are Ints because the local database and Poweramp document
 * ids use them; a server with string library ids must map them to stable Ints.
 */
interface MusicServer {

    /** Distinguishes this server's data in local caches (lyrics). Contains no secrets. */
    val cacheKey: String

    /** Checks the address and credentials; describes the server for the login screen. */
    suspend fun connect(): ServerDescription

    /** True while the server is (re)scanning its files, when its library may be incomplete. */
    suspend fun isScanning(): Boolean

    /** The server's libraries; empty if it has just one (tracks then use [DEFAULT_LIBRARY_ID][dev.subsonic.poweramp.sync.DEFAULT_LIBRARY_ID]). */
    suspend fun libraries(): List<LibraryRecord>

    /**
     * One page of every track, from one library ([libraryId] from [libraries]) or from all (null).
     * A page shorter than [count] is the last. Pages need not be a consistent snapshot.
     */
    suspend fun tracks(libraryId: Int?, offset: Int, count: Int): List<TrackRecord>

    /** Every playlist with a version marker, so unchanged ones aren't fetched again. */
    suspend fun playlists(): List<PlaylistVersion>

    /** A playlist with its tracks in order. */
    suspend fun playlist(id: String): PlaylistRecord

    /**
     * URL of a track's audio, including whatever authentication it needs (treat it as a secret).
     * [StreamQuality.Original] must support HTTP range requests; a transcode must be a
     * constant-bitrate MP3 served as `audio/mpeg`.
     */
    fun streamUrl(trackId: String, quality: StreamQuality): HttpUrl

    /** URL of cover art (secret, like [streamUrl]); [sizePx] is a hint. */
    fun coverUrl(coverId: String, sizePx: Int): HttpUrl

    /** A track's lyrics, or [TrackLyrics.NONE]. */
    suspend fun lyrics(trackId: String, artist: String?, title: String): TrackLyrics

    /** Live playback state ("now playing"), best effort. Must not count a play. */
    suspend fun reportPlayback(trackId: String, state: PlaybackState, positionMs: Long)

    /** Counts a finished play that started at [playedAtMs]. */
    suspend fun scrobble(trackId: String, playedAtMs: Long)
}

/** What [MusicServer.connect] found, for the login screen. */
data class ServerDescription(
    /** e.g. "Navidrome 0.64.2". */
    val name: String,
    /** Extra lines worth showing, e.g. API version and supported extensions. */
    val details: List<String> = emptyList(),
    /** Whether the server accepts API keys, so the login screen can suggest them; null if unknown. */
    val supportsApiKeys: Boolean? = null,
)

data class PlaylistVersion(
    val id: String,
    /** Changes whenever the playlist does; null if the server doesn't say (then always refetched). */
    val version: String?,
)

sealed interface StreamQuality {
    data object Original : StreamQuality
    data class Mp3(val kbps: Int) : StreamQuality
}

enum class PlaybackState { STARTING, PLAYING, PAUSED, STOPPED }
