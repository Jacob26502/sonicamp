package dev.subsonic.poweramp.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.subsonic.poweramp.sync.LibraryRecord
import dev.subsonic.poweramp.sync.LibraryUpdate
import dev.subsonic.poweramp.sync.PlaylistDiff
import dev.subsonic.poweramp.sync.PlaylistRecord
import dev.subsonic.poweramp.sync.TrackDiff
import dev.subsonic.poweramp.sync.TrackRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device/emulator against real SQLite (in-memory database). */
@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {

    private lateinit var db: LibraryDatabase

    @Before
    fun setUp() {
        db = LibraryDatabase(InstrumentationRegistry.getInstrumentation().targetContext, name = null)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun track(id: String, album: String, albumArtist: String, title: String = id) = TrackRecord(
        id = id, libraryId = 1, title = title, albumId = "al-$album", album = album, artist = albumArtist,
        albumArtistId = "ar-$albumArtist", albumArtist = albumArtist, trackNo = 1, discNo = 1, year = 2024,
        genre = null, durationSec = 100, sizeBytes = 1000, suffix = "flac", contentType = "audio/flac",
        bitRate = 900, coverArt = "mf-$id", path = "$albumArtist/$album/$id.flac", created = null,
    )

    private fun update(
        inserted: List<TrackRecord> = emptyList(),
        updated: List<TrackRecord> = emptyList(),
        deleted: Set<String> = emptySet(),
        playlists: List<PlaylistRecord> = emptyList(),
        deletedPlaylists: Set<String> = emptySet(),
        at: Long = 1_000,
    ) = LibraryUpdate(
        identity = "id-1",
        libraries = listOf(LibraryRecord(1, "Music")),
        tracks = TrackDiff(inserted, updated, deleted),
        playlists = PlaylistDiff(playlists, deletedPlaylists),
        syncedAt = at,
    )

    @Test
    fun applyStoresTracksAndViewsDeriveAlbumsAndArtists() {
        val a = track("t1", "Epsilon", "Andora")
        val b = track("t2", "Epsilon", "Andora")
        val c = track("t3", "OST", "Kosaki")

        db.apply(update(inserted = listOf(a, b, c)))

        assertEquals(LibraryCounts(tracks = 3, albums = 2, artists = 2, playlists = 0), db.counts())
        assertEquals(mapOf("t1" to a.contentHash, "t2" to b.contentHash, "t3" to c.contentHash), db.trackHashes())
        assertEquals("id-1", db.identity())
        assertEquals(1L, db.libraryVersion())
    }

    @Test
    fun updatesAndDeletesApply() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"), track("t2", "A", "X"))))
        val renamed = track("t1", "A", "X", title = "Renamed")

        db.apply(update(updated = listOf(renamed), deleted = setOf("t2"), at = 2_000))

        assertEquals(mapOf("t1" to renamed.contentHash), db.trackHashes())
        assertEquals(2L, db.libraryVersion())
    }

    @Test
    fun noOpSyncDoesNotBumpVersion() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"))))
        db.apply(update())
        assertEquals(1L, db.libraryVersion())
    }

    @Test
    fun playlistsKeepOrderAndDuplicates() {
        val p = PlaylistRecord("p1", "Mix", null, "admin", 3, 300, "v1", null, listOf("t2", "t1", "t2"))
        db.apply(update(inserted = listOf(track("t1", "A", "X"), track("t2", "A", "X")), playlists = listOf(p)))

        assertEquals(listOf("t2", "t1", "t2"), db.playlistTrackIds("p1"))
        assertEquals(mapOf("p1" to "v1"), db.playlistVersions())

        db.apply(update(playlists = listOf(p.copy(changed = "v2", trackIds = listOf("t1")))))
        assertEquals(listOf("t1"), db.playlistTrackIds("p1"))

        db.apply(update(deletedPlaylists = setOf("p1")))
        assertEquals(emptyList<String>(), db.playlistTrackIds("p1"))
        assertEquals(0, db.counts().playlists)
    }

    @Test
    fun wipeRemovesEverything() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"))))
        db.wipe()
        assertEquals(LibraryCounts(0, 0, 0, 0), db.counts())
        assertNull(db.identity())
        assertEquals(0L, db.libraryVersion())
    }
}
