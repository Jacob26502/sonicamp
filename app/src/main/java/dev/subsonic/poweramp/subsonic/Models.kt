package dev.subsonic.poweramp.subsonic

import kotlinx.serialization.Serializable

/** Fields every `subsonic-response` carries. OpenSubsonic adds type, serverVersion, openSubsonic. */
@Serializable
data class BaseResponse(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val error: SubsonicError? = null,
)

@Serializable
data class SubsonicError(
    val code: Int,
    val message: String? = null,
    val helpUrl: String? = null,
)

@Serializable
data class OpenSubsonicExtension(
    val name: String,
    val versions: List<Int> = emptyList(),
)

/** A library ("music folder"). Navidrome supports several, with per-user access. */
@Serializable
data class MusicFolder(
    val id: Int,
    val name: String? = null,
)

@Serializable
data class ArtistRef(
    val id: String? = null,
    val name: String = "",
)

/**
 * A song ("child") as returned by search3, getPlaylist, etc.
 * [artists], [albumArtists], [displayArtist] and [displayAlbumArtist] are OpenSubsonic additions;
 * classic Subsonic servers only send [artist]/[artistId].
 */
@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val path: String? = null,
    val created: String? = null,
    val artists: List<ArtistRef> = emptyList(),
    val albumArtists: List<ArtistRef> = emptyList(),
    val displayArtist: String? = null,
    val displayAlbumArtist: String? = null,
    val displayComposer: String? = null,
    /** Audio format details (OpenSubsonic); Poweramp shows them in Info/Tags. */
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val created: String? = null,
    /** Updated on every modification; used to skip re-fetching unchanged playlists. */
    val changed: String? = null,
    val coverArt: String? = null,
    /** Only filled by getPlaylist. */
    val entry: List<Song> = emptyList(),
)

@Serializable
data class ScanStatus(
    val scanning: Boolean = false,
    val count: Long? = null,
    val lastScan: String? = null,
)

/** What a successful connection test learned about the server. */
data class ServerInfo(
    val type: String?,
    val serverVersion: String?,
    val apiVersion: String?,
    val openSubsonic: Boolean,
    val extensions: List<OpenSubsonicExtension>,
) {
    fun supports(extension: String) = extensions.any { it.name == extension }

    val supportsApiKey get() = supports(Extensions.API_KEY_AUTH)

    /** e.g. "Navidrome 0.64.2" or "Subsonic-compatible server". */
    val displayName: String
        get() {
            val name = type?.replaceFirstChar { it.uppercase() } ?: "Subsonic-compatible server"
            return listOfNotNull(name, serverVersion?.substringBefore(' ')).joinToString(" ")
        }
}

/** One set of lyrics from getLyricsBySongId (OpenSubsonic `songLyrics`). */
@Serializable
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    /** Milliseconds; positive means the lyrics should appear sooner. */
    val offset: Long = 0,
    val line: List<LyricLine> = emptyList(),
)

@Serializable
data class LyricLine(
    /** Milliseconds from the start of the track; only set for synced lyrics. */
    val start: Long? = null,
    val value: String = "",
)

object Extensions {
    const val API_KEY_AUTH = "apiKeyAuthentication"
    const val SONG_LYRICS = "songLyrics"
    const val PLAYBACK_REPORT = "playbackReport"
}
