package dev.subsonic.poweramp.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.subsonic.poweramp.sync.LibraryStore
import dev.subsonic.poweramp.sync.LibraryUpdate
import dev.subsonic.poweramp.sync.PlaylistRecord
import dev.subsonic.poweramp.sync.TrackRecord

data class LibraryCounts(val tracks: Int, val albums: Int, val artists: Int, val playlists: Int)

/**
 * Local copy of the server's library. It's a cache: a schema change simply drops everything and the
 * next sync refills it. Albums and artists are views over `tracks`, so they can't drift out of sync.
 *
 * WAL mode lets the Poweramp provider (phase 3) read while a sync is writing.
 *
 * @param name null for an in-memory database (tests).
 */
class LibraryDatabase(context: Context, name: String? = "library.db") :
    SQLiteOpenHelper(context, name, null, VERSION), LibraryStore {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

    private fun recreate(db: SQLiteDatabase) {
        listOf("albums", "artists").forEach { db.execSQL("DROP VIEW IF EXISTS $it") }
        listOf("playlist_entries", "playlists", "tracks", "libraries", "meta").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    // ---- LibraryStore ----

    override fun identity(): String? = meta(readableDatabase, KEY_IDENTITY)

    override fun trackHashes(): Map<String, String> =
        readableDatabase.rawQuery("SELECT id, content_hash FROM tracks", null).use { c ->
            HashMap<String, String>(c.count * 2).apply { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    override fun playlistVersions(): Map<String, String?> =
        readableDatabase.rawQuery("SELECT id, changed FROM playlists", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), if (c.isNull(1)) null else c.getString(1)) }
        }

    override fun apply(update: LibraryUpdate) = transaction { db ->
        setMeta(db, KEY_IDENTITY, update.identity)

        db.delete("libraries", null, null)
        update.libraries.forEach {
            db.insertOrThrow("libraries", null, ContentValues().apply { put("id", it.id); put("name", it.name) })
        }

        val tracks = update.tracks
        (tracks.inserted + tracks.updated).forEach {
            db.insertWithOnConflict("tracks", null, it.toValues(update.syncedAt), SQLiteDatabase.CONFLICT_REPLACE)
        }
        db.compileStatement("DELETE FROM tracks WHERE id = ?").use { stmt ->
            tracks.deletedIds.forEach { stmt.bindString(1, it); stmt.executeUpdateDelete() }
        }

        val playlists = update.playlists
        playlists.deletedIds.forEach { deletePlaylist(db, it) }
        playlists.upserts.forEach { upsertPlaylist(db, it, update.syncedAt) }

        if (!tracks.isEmpty || !playlists.isEmpty) {
            // Phase 3 compares this to decide whether Poweramp needs to rescan.
            setMeta(db, KEY_LIBRARY_VERSION, ((meta(db, KEY_LIBRARY_VERSION)?.toLong() ?: 0) + 1).toString())
        }
    }

    override fun wipe() = transaction { db ->
        listOf("playlist_entries", "playlists", "tracks", "libraries", "meta").forEach { db.delete(it, null, null) }
    }

    // ---- Queries for the UI (and later the provider) ----

    fun counts(): LibraryCounts = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM tracks), (SELECT COUNT(*) FROM albums), " +
            "(SELECT COUNT(*) FROM artists), (SELECT COUNT(*) FROM playlists)",
        null,
    ).use { c ->
        c.moveToFirst()
        LibraryCounts(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
    }

    /** Increments whenever a sync changes anything. */
    fun libraryVersion(): Long = meta(readableDatabase, KEY_LIBRARY_VERSION)?.toLong() ?: 0

    /** Track ids of a playlist in order. */
    fun playlistTrackIds(playlistId: String): List<String> =
        readableDatabase.rawQuery(
            "SELECT track_id FROM playlist_entries WHERE playlist_id = ? ORDER BY position",
            arrayOf(playlistId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // ---- helpers ----

    private fun upsertPlaylist(db: SQLiteDatabase, p: PlaylistRecord, syncedAt: Long) {
        deletePlaylist(db, p.id)
        db.insertOrThrow("playlists", null, ContentValues().apply {
            put("id", p.id)
            put("name", p.name)
            put("comment", p.comment)
            put("owner", p.owner)
            put("song_count", p.songCount)
            put("duration_sec", p.durationSec)
            put("changed", p.changed)
            put("cover_art", p.coverArt)
            put("last_modified", syncedAt)
        })
        db.compileStatement("INSERT INTO playlist_entries(playlist_id, position, track_id) VALUES (?, ?, ?)").use { stmt ->
            p.trackIds.forEachIndexed { index, trackId ->
                stmt.bindString(1, p.id)
                stmt.bindLong(2, index.toLong())
                stmt.bindString(3, trackId)
                stmt.executeInsert()
            }
        }
    }

    private fun deletePlaylist(db: SQLiteDatabase, id: String) {
        db.delete("playlist_entries", "playlist_id = ?", arrayOf(id))
        db.delete("playlists", "id = ?", arrayOf(id))
    }

    private fun TrackRecord.toValues(syncedAt: Long) = ContentValues().apply {
        put("id", id)
        put("library_id", libraryId)
        put("title", title)
        put("album_id", albumId)
        put("album", album)
        put("artist", artist)
        put("album_artist_id", albumArtistId)
        put("album_artist", albumArtist)
        put("track_no", trackNo)
        put("disc_no", discNo)
        put("year", year)
        put("genre", genre)
        put("duration_sec", durationSec)
        put("size_bytes", sizeBytes)
        put("suffix", suffix)
        put("content_type", contentType)
        put("bit_rate", bitRate)
        put("cover_art", coverArt)
        put("path", path)
        put("created", created)
        put("content_hash", contentHash)
        put("last_modified", syncedAt)
    }

    private fun meta(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun setMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val VERSION = 1
        const val KEY_IDENTITY = "identity"
        const val KEY_LIBRARY_VERSION = "library_version"

        val SCHEMA = listOf(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE libraries (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            """CREATE TABLE tracks (
                id TEXT PRIMARY KEY,
                library_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                album_id TEXT,
                album TEXT,
                artist TEXT,
                album_artist_id TEXT,
                album_artist TEXT,
                track_no INTEGER,
                disc_no INTEGER,
                year INTEGER,
                genre TEXT,
                duration_sec INTEGER,
                size_bytes INTEGER,
                suffix TEXT,
                content_type TEXT,
                bit_rate INTEGER,
                cover_art TEXT,
                path TEXT,
                created TEXT,
                content_hash TEXT NOT NULL,
                last_modified INTEGER NOT NULL
            )""",
            "CREATE INDEX tracks_by_album ON tracks (album_id)",
            "CREATE INDEX tracks_by_album_artist ON tracks (library_id, album_artist_id)",
            """CREATE TABLE playlists (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                comment TEXT,
                owner TEXT,
                song_count INTEGER NOT NULL,
                duration_sec INTEGER NOT NULL,
                changed TEXT,
                cover_art TEXT,
                last_modified INTEGER NOT NULL
            )""",
            """CREATE TABLE playlist_entries (
                playlist_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                track_id TEXT NOT NULL,
                PRIMARY KEY (playlist_id, position)
            )""",
            """CREATE VIEW albums AS
                SELECT album_id AS id, library_id, MIN(album) AS name,
                       MIN(album_artist_id) AS album_artist_id, MIN(album_artist) AS album_artist,
                       MAX(year) AS year, COUNT(*) AS track_count, MAX(last_modified) AS last_modified
                FROM tracks WHERE album_id IS NOT NULL GROUP BY album_id""",
            """CREATE VIEW artists AS
                SELECT album_artist_id AS id, library_id, MIN(album_artist) AS name,
                       COUNT(DISTINCT album_id) AS album_count, MAX(last_modified) AS last_modified
                FROM tracks WHERE album_artist_id IS NOT NULL GROUP BY library_id, album_artist_id""",
        )
    }
}
