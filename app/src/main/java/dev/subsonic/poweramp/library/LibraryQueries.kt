package dev.subsonic.poweramp.library

import android.database.Cursor

/** Read-only views of the library for the Poweramp provider. All last-modified values are epoch ms. */

data class LibraryFolder(val id: Int, val name: String, val lastModified: Long)

data class ArtistFolder(val libraryId: Int, val id: String, val name: String, val lastModified: Long)

data class AlbumFolder(
    val id: String,
    val libraryId: Int,
    val name: String,
    val albumArtistId: String?,
    val year: Int?,
    val coverArt: String?,
    val lastModified: Long,
)

data class TrackRow(
    val id: String,
    val libraryId: Int,
    val title: String,
    val albumId: String?,
    val album: String?,
    val artist: String?,
    val albumArtistId: String?,
    val albumArtist: String?,
    val composer: String?,
    val trackNo: Int?,
    val discNo: Int?,
    /** Distinct disc numbers on this track's album; >1 means a multi-disc album. */
    val albumDiscCount: Int,
    val year: Int?,
    val genre: String?,
    val durationSec: Int?,
    val sizeBytes: Long?,
    val suffix: String?,
    val contentType: String?,
    val bitRate: Int?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val coverArt: String?,
    val path: String?,
    val lastModified: Long,
)

fun LibraryDatabase.libraryFolders(): List<LibraryFolder> = query(
    """SELECT l.id, l.name,
              COALESCE(f.last_modified, (SELECT MAX(last_modified) FROM tracks WHERE library_id = l.id), 0)
       FROM (SELECT id, name FROM libraries
             UNION SELECT DISTINCT library_id, 'Music' FROM tracks WHERE library_id NOT IN (SELECT id FROM libraries)) l
       LEFT JOIN folders f ON f.kind = '${FolderKind.LIBRARY}' AND f.id = CAST(l.id AS TEXT)
       ORDER BY l.id""",
) { LibraryFolder(it.getInt(0), it.getString(1), it.getLong(2)) }

fun LibraryDatabase.libraryFolder(id: Int): LibraryFolder? = libraryFolders().firstOrNull { it.id == id }

/**
 * The albums/artists views compute library_id with an aggregate, so the column has no type affinity
 * and `library_id = ?` with a string argument never matches. Always compare through this.
 */
private const val LIBRARY_ID_IS = "library_id = CAST(? AS INTEGER)"

private const val ARTIST_COLUMNS = "library_id, id, name, last_modified"

private fun artistOf(c: Cursor) = ArtistFolder(c.getInt(0), c.getString(1), c.getString(2) ?: "Unknown artist", c.getLong(3))

fun LibraryDatabase.artistFolders(libraryId: Int): List<ArtistFolder> =
    query("SELECT $ARTIST_COLUMNS FROM artists WHERE $LIBRARY_ID_IS ORDER BY name COLLATE NOCASE", libraryId.toString(), map = ::artistOf)

fun LibraryDatabase.artistFolder(libraryId: Int, artistId: String): ArtistFolder? =
    query("SELECT $ARTIST_COLUMNS FROM artists WHERE $LIBRARY_ID_IS AND id = ?", libraryId.toString(), artistId, map = ::artistOf).firstOrNull()

private const val ALBUM_COLUMNS = "id, library_id, name, album_artist_id, year, cover_art, last_modified"

private fun albumOf(c: Cursor) = AlbumFolder(
    id = c.getString(0),
    libraryId = c.getInt(1),
    name = c.getString(2) ?: "Unknown album",
    albumArtistId = c.stringOrNull(3),
    year = c.intOrNull(4),
    coverArt = c.stringOrNull(5),
    lastModified = c.getLong(6),
)

fun LibraryDatabase.albumFolders(libraryId: Int, albumArtistId: String): List<AlbumFolder> = query(
    "SELECT $ALBUM_COLUMNS FROM albums WHERE $LIBRARY_ID_IS AND album_artist_id = ? ORDER BY year, name COLLATE NOCASE",
    libraryId.toString(), albumArtistId, map = ::albumOf,
)

/** Albums with no album artist; shown directly under the library root. */
fun LibraryDatabase.albumFoldersWithoutArtist(libraryId: Int): List<AlbumFolder> = query(
    "SELECT $ALBUM_COLUMNS FROM albums WHERE $LIBRARY_ID_IS AND album_artist_id IS NULL ORDER BY name COLLATE NOCASE",
    libraryId.toString(), map = ::albumOf,
)

fun LibraryDatabase.albumFolder(albumId: String): AlbumFolder? =
    query("SELECT $ALBUM_COLUMNS FROM albums WHERE id = ?", albumId, map = ::albumOf).firstOrNull()

private const val TRACK_SELECT = """
    SELECT id, library_id, title, album_id, album, artist, album_artist_id, album_artist, composer,
           track_no, disc_no,
           (SELECT COUNT(DISTINCT d.disc_no) FROM tracks d WHERE d.album_id = t.album_id) AS disc_count,
           year, genre, duration_sec, size_bytes, suffix, content_type, bit_rate, sample_rate, bit_depth,
           channels, cover_art, path, last_modified
    FROM tracks t"""

private fun trackOf(c: Cursor) = TrackRow(
    id = c.getString(0),
    libraryId = c.getInt(1),
    title = c.getString(2),
    albumId = c.stringOrNull(3),
    album = c.stringOrNull(4),
    artist = c.stringOrNull(5),
    albumArtistId = c.stringOrNull(6),
    albumArtist = c.stringOrNull(7),
    composer = c.stringOrNull(8),
    trackNo = c.intOrNull(9),
    discNo = c.intOrNull(10),
    albumDiscCount = c.getInt(11),
    year = c.intOrNull(12),
    genre = c.stringOrNull(13),
    durationSec = c.intOrNull(14),
    sizeBytes = if (c.isNull(15)) null else c.getLong(15),
    suffix = c.stringOrNull(16),
    contentType = c.stringOrNull(17),
    bitRate = c.intOrNull(18),
    sampleRate = c.intOrNull(19),
    bitDepth = c.intOrNull(20),
    channels = c.intOrNull(21),
    coverArt = c.stringOrNull(22),
    path = c.stringOrNull(23),
    lastModified = c.getLong(24),
)

/** In disc, then track order: the order Poweramp shows in Folders. */
fun LibraryDatabase.tracksOfAlbum(albumId: String): List<TrackRow> =
    query("$TRACK_SELECT WHERE album_id = ? ORDER BY disc_no, track_no, title COLLATE NOCASE", albumId, map = ::trackOf)

/** Tracks with no album; shown directly under the library root. */
fun LibraryDatabase.tracksWithoutAlbum(libraryId: Int): List<TrackRow> = query(
    "$TRACK_SELECT WHERE library_id = ? AND album_id IS NULL ORDER BY title COLLATE NOCASE",
    libraryId.toString(), map = ::trackOf,
)

fun LibraryDatabase.track(trackId: String): TrackRow? =
    query("$TRACK_SELECT WHERE id = ?", trackId, map = ::trackOf).firstOrNull()

private fun <T> LibraryDatabase.query(sql: String, vararg args: String, map: (Cursor) -> T): List<T> =
    readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
