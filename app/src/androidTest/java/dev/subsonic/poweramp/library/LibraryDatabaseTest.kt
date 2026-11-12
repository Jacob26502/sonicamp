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
        composer = "Comp", sampleRate = 44100, bitDepth = 16, channels = 2,
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

    private fun album(name: String) = db.folderLastModified(FolderKind.ALBUM, "al-$name")
    private fun artist(name: String) = db.folderLastModified(FolderKind.ARTIST, FolderKind.artistId(1, "ar-$name"))
    private fun library() = db.folderLastModified(FolderKind.LIBRARY, "1")

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
    fun newMetadataColumnsAndAlbumCoverAreStored() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"), track("t2", "A", "X"))))

        db.readableDatabase.rawQuery("SELECT composer, sample_rate, bit_depth, channels FROM tracks WHERE id = 't1'", null).use {
            it.moveToFirst()
            assertEquals("Comp", it.getString(0))
            assertEquals(44100, it.getInt(1))
            assertEquals(16, it.getInt(2))
            assertEquals(2, it.getInt(3))
        }
        db.readableDatabase.rawQuery("SELECT cover_art, last_modified FROM albums WHERE id = 'al-A'", null).use {
            it.moveToFirst()
            assertEquals("mf-t1", it.getString(0))
            assertEquals(1_000L, it.getLong(1))
        }
    }

    @Test
    fun removingATrackBumpsOnlyItsFolders() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"), track("t2", "A", "X"), track("t3", "B", "Y"))))

        db.apply(update(deleted = setOf("t2"), at = 2_000))

        assertEquals(2_000L, album("A"))
        assertEquals(2_000L, artist("X"))
        assertEquals(2_000L, library())
        assertEquals(1_000L, album("B"))
        assertEquals(1_000L, artist("Y"))
    }

    @Test
    fun movingATrackBetweenAlbumsBumpsBoth() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"), track("t3", "A", "X"), track("t2", "B", "X"))))

        db.apply(update(updated = listOf(track("t1", "B", "X")), at = 2_000))

        assertEquals(2_000L, album("A"))
        assertEquals(2_000L, album("B"))
    }

    @Test
    fun albumMovingToAnotherArtistBumpsOldAndNewArtist() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"), track("t2", "C", "X"), track("t3", "D", "Y"))))

        // Album A is re-tagged from artist X to artist Y.
        db.apply(update(updated = listOf(track("t1", "A", "Y")), at = 2_000))

        assertEquals(2_000L, artist("X")) // lost album A
        assertEquals(2_000L, artist("Y")) // gained album A
        assertEquals(1_000L, album("C"))
        assertEquals(1_000L, album("D"))
    }

    @Test
    fun foldersOfVanishedAlbumsAndArtistsArePruned() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"), track("t2", "B", "Y"))))

        db.apply(update(deleted = setOf("t1"), at = 2_000))

        assertNull(album("A"))
        assertNull(artist("X"))
        assertEquals(1_000L, album("B"))
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
    fun noOpSyncDoesNotBumpVersionOrFolders() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"))))
        db.apply(update(at = 2_000))
        assertEquals(1L, db.libraryVersion())
        assertEquals(1_000L, album("A"))
        assertEquals(1_000L, library())
    }

    @Test
    fun playlistsKeepOrderAndDuplicates() {
        val p = PlaylistRecord("p1", "Mix", null, "admin", 3, 300, "v1", null, listOf("t2", "t1", "t2"))
        db.apply(update(inserted = listOf(track("t1", "A", "X"), track("t2", "A", "X")), playlists = listOf(p)))

        assertEquals(listOf("t2", "t1", "t2"), db.playlistTrackIds("p1"))
        assertEquals(mapOf("p1" to "v1"), db.playlistVersions())
        assertEquals(1_000L, db.folderLastModified(FolderKind.PLAYLISTS, FolderKind.PLAYLISTS_ID))

        db.apply(update(playlists = listOf(p.copy(changed = "v2", trackIds = listOf("t1"))), at = 2_000))
        assertEquals(listOf("t1"), db.playlistTrackIds("p1"))
        assertEquals(2_000L, db.folderLastModified(FolderKind.PLAYLISTS, FolderKind.PLAYLISTS_ID))

        db.apply(update(deletedPlaylists = setOf("p1"), at = 3_000))
        assertEquals(emptyList<String>(), db.playlistTrackIds("p1"))
        assertEquals(0, db.counts().playlists)
    }

    @Test
    fun wipeRemovesEverything() {
        db.apply(update(inserted = listOf(track("t1", "A", "X"))))
        db.wipe()
        assertEquals(LibraryCounts(0, 0, 0, 0), db.counts())
        assertNull(db.identity())
        assertNull(album("A"))
        assertEquals(0L, db.libraryVersion())
    }
}
