package dev.subsonic.poweramp.subsonic

import dev.subsonic.poweramp.sync.LibraryRecord
import dev.subsonic.poweramp.sync.PlaylistRecord
import dev.subsonic.poweramp.sync.TrackRecord

/** Conversions from Subsonic API models to the app's server-neutral records. */

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

fun MusicFolder.toLibraryRecord() = LibraryRecord(id, name ?: "Library $id")
