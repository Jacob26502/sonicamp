package dev.subsonic.poweramp.sync

import dev.subsonic.poweramp.subsonic.MusicFolder
import dev.subsonic.poweramp.subsonic.Playlist
import dev.subsonic.poweramp.subsonic.Song
import java.security.MessageDigest

/** A track as stored locally. Everything Poweramp will need, flattened from [Song]. */
data class TrackRecord(
    val id: String,
    val libraryId: Int,
    val title: String,
    val albumId: String?,
    val album: String?,
    /** Display artist, e.g. "A • B" for multiple artists. */
    val artist: String?,
    /** Folders are grouped by album artist, so a compilation stays in one folder. */
    val albumArtistId: String?,
    val albumArtist: String?,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val genre: String?,
    val durationSec: Int?,
    val sizeBytes: Long?,
    val suffix: String?,
    val contentType: String?,
    val bitRate: Int?,
    val coverArt: String?,
    val path: String?,
    val created: String?,
    val composer: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
) {
    /**
     * Fingerprint of every stored field. Navidrome has no per-song "modified" timestamp, so a
     * track counts as changed exactly when this changes. Field order is part of the format.
     */
    val contentHash: String by lazy {
        val canonical = listOf(
            libraryId, title, albumId, album, artist, albumArtistId, albumArtist, trackNo, discNo, year,
            genre, durationSec, sizeBytes, suffix, contentType, bitRate, coverArt, path, created,
            composer, sampleRate, bitDepth, channels,
        ).joinToString(FIELD_SEPARATOR) { it?.toString() ?: NULL_MARKER }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FIELD_SEPARATOR = "\u001F"
        const val NULL_MARKER = "\u0000"
    }
}

/** Used when the server doesn't support getMusicFolders. */
const val DEFAULT_LIBRARY_ID = 0

fun Song.toTrackRecord(libraryId: Int): TrackRecord {
    val albumArtistNames = albumArtists.map { it.name }.filter { it.isNotBlank() }
    return TrackRecord(
        id = id,
        libraryId = libraryId,
        title = title,
        albumId = albumId,
        album = album,
        artist = displayArtist ?: artist,
        // Classic Subsonic songs have no album-artist fields: fall back to the track artist.
        albumArtistId = albumArtists.firstOrNull()?.id ?: artistId,
        albumArtist = displayAlbumArtist
            ?: albumArtistNames.takeIf { it.isNotEmpty() }?.joinToString(" • ")
            ?: artist,
        trackNo = track,
        discNo = discNumber,
        year = year,
        genre = genre,
        durationSec = duration,
        sizeBytes = size,
        suffix = suffix,
        contentType = contentType,
        bitRate = bitRate,
        coverArt = coverArt,
        path = path,
        created = created,
        composer = displayComposer?.takeIf { it.isNotBlank() },
        sampleRate = samplingRate?.takeIf { it > 0 },
        bitDepth = bitDepth?.takeIf { it > 0 },
        channels = channelCount?.takeIf { it > 0 },
    )
}

data class PlaylistRecord(
    val id: String,
    val name: String,
    val comment: String?,
    val owner: String?,
    val songCount: Int,
    val durationSec: Int,
    val changed: String?,
    val coverArt: String?,
    val trackIds: List<String>,
)

fun Playlist.toPlaylistRecord() = PlaylistRecord(
    id = id,
    name = name,
    comment = comment,
    owner = owner,
    songCount = songCount,
    durationSec = duration,
    changed = changed,
    coverArt = coverArt,
    trackIds = entry.map { it.id },
)

data class LibraryRecord(val id: Int, val name: String)

fun MusicFolder.toLibraryRecord() = LibraryRecord(id, name ?: "Library $id")
