package dev.subsonic.poweramp.subsonic

import dev.subsonic.poweramp.lyrics.LyricsFormat
import dev.subsonic.poweramp.lyrics.TrackLyrics
import dev.subsonic.poweramp.server.MusicServer
import dev.subsonic.poweramp.server.PlaybackState
import dev.subsonic.poweramp.server.PlaylistVersion
import dev.subsonic.poweramp.server.ServerAuth
import dev.subsonic.poweramp.server.ServerDescription
import dev.subsonic.poweramp.server.StreamQuality
import dev.subsonic.poweramp.server.UnexpectedResponseException
import dev.subsonic.poweramp.sync.DEFAULT_LIBRARY_ID
import dev.subsonic.poweramp.sync.LibraryRecord
import dev.subsonic.poweramp.sync.PlaylistRecord
import dev.subsonic.poweramp.sync.TrackRecord
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * [MusicServer] for Subsonic and OpenSubsonic servers (developed against Navidrome), on top of the
 * low-level [SubsonicClient]. Picks endpoints by the server's OpenSubsonic extensions ([features]).
 */
class SubsonicServer(
    private val baseUrl: HttpUrl,
    private val auth: ServerAuth,
    private val http: OkHttpClient,
    private val features: ServerFeatures,
) : MusicServer {

    private val client = SubsonicClient(baseUrl, auth, http)

    override val cacheKey: String get() = baseUrl.toString()

    private suspend fun supports(extension: String) = features.supports(cacheKey, client, extension)

    override suspend fun connect(): ServerDescription {
        // 1. Unauthenticated probe. Old/plain Subsonic servers don't have this endpoint;
        //    treat any API-level failure as "no extensions". Network errors still propagate.
        val extensions = try {
            SubsonicClient(baseUrl, null, http).openSubsonicExtensions()
        } catch (e: SubsonicApiException) {
            emptyList()
        } catch (e: UnexpectedResponseException) {
            emptyList()
        }

        // 2. Authenticated ping. Let the server decide whether the auth method is acceptable
        //    (an API key on a server without the extension fails here with error 42/44).
        val ping = client.ping()
        val info = ServerInfo(
            type = ping.type,
            serverVersion = ping.serverVersion,
            apiVersion = ping.version,
            openSubsonic = ping.openSubsonic,
            extensions = extensions,
        )
        return ServerDescription(
            name = info.displayName,
            details = buildList {
                add("Subsonic API ${info.apiVersion ?: "?"}" + if (info.openSubsonic) " · OpenSubsonic" else "")
                if (extensions.isNotEmpty()) add("Extensions: " + extensions.joinToString { it.name })
            },
            supportsApiKeys = info.supportsApiKey,
        )
    }

    /** getScanStatus is optional: servers without it are treated as not scanning. */
    override suspend fun isScanning(): Boolean = try {
        client.scanStatus().scanning
    } catch (e: SubsonicApiException) {
        false
    }

    /** getMusicFolders is optional: without it everything is one library. */
    override suspend fun libraries(): List<LibraryRecord> = try {
        client.musicFolders().map { it.toLibraryRecord() }
    } catch (e: SubsonicApiException) {
        emptyList()
    }

    /** search3 with an empty query lists every song (supported by Navidrome). */
    override suspend fun tracks(libraryId: Int?, offset: Int, count: Int): List<TrackRecord> =
        client.allSongsPage(offset, count, libraryId).map { it.toTrackRecord(libraryId ?: DEFAULT_LIBRARY_ID) }

    override suspend fun playlists(): List<PlaylistVersion> =
        client.playlists().map { PlaylistVersion(it.id, it.changed) }

    override suspend fun playlist(id: String): PlaylistRecord = client.playlist(id).toPlaylistRecord()

    override fun streamUrl(trackId: String, quality: StreamQuality): HttpUrl = when (quality) {
        StreamQuality.Original -> client.streamUrl(trackId, format = "raw")
        is StreamQuality.Mp3 -> client.streamUrl(trackId, maxBitRateKbps = quality.kbps, format = "mp3")
    }

    override fun coverUrl(coverId: String, sizePx: Int): HttpUrl = client.coverArtUrl(coverId, sizePx)

    /** Structured lyrics (OpenSubsonic songLyrics) where available, else classic getLyrics by artist and title. */
    override suspend fun lyrics(trackId: String, artist: String?, title: String): TrackLyrics =
        if (supports(Extensions.SONG_LYRICS)) LyricsFormat.fromStructured(client.lyricsBySongId(trackId))
        else LyricsFormat.fromPlain(client.lyrics(artist, title))

    /**
     * OpenSubsonic reportPlayback (with ignoreScrobble, so it never counts a play), or on classic
     * servers a "now playing" scrobble when playback starts.
     */
    override suspend fun reportPlayback(trackId: String, state: PlaybackState, positionMs: Long) {
        if (supports(Extensions.PLAYBACK_REPORT)) {
            client.reportPlayback(trackId, state.name.lowercase(), positionMs)
        } else if (state == PlaybackState.STARTING || state == PlaybackState.PLAYING) {
            client.scrobble(trackId, playedAtMs = null, submission = false)
        }
    }

    override suspend fun scrobble(trackId: String, playedAtMs: Long) =
        client.scrobble(trackId, playedAtMs, submission = true)
}
