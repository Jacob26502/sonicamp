package dev.subsonic.poweramp.provider

import android.content.ContentResolver
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmpz.poweramp.player.TrackProviderConsts
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.server.ServerAuth
import dev.subsonic.poweramp.settings.SavedServer
import dev.subsonic.poweramp.subsonic.ServerUrl
import dev.subsonic.poweramp.sync.LibraryRecord
import dev.subsonic.poweramp.sync.LibraryUpdate
import dev.subsonic.poweramp.sync.PlaylistDiff
import dev.subsonic.poweramp.sync.PlaylistRecord
import dev.subsonic.poweramp.sync.TrackDiff
import dev.subsonic.poweramp.sync.TrackRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Queries the real provider through ContentResolver, the way Poweramp does, against seeded data.
 * Runs in the app's own process (same uid), so MANAGE_DOCUMENTS doesn't block it.
 */
@RunWith(AndroidJUnit4::class)
class SubsonicDocumentsProviderTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SubsonicApp
    private val resolver: ContentResolver = app.contentResolver
    private val authority = SubsonicDocumentsProvider.AUTHORITY

    private fun track(id: String, album: String, artist: String, disc: Int?, no: Int, cover: String? = "mf-$id") = TrackRecord(
        id = id, libraryId = 1, title = "Title $id", albumId = "al-$album", album = album, artist = artist,
        albumArtistId = "ar-$artist", albumArtist = artist, trackNo = no, discNo = disc, year = 2020, genre = "OST",
        durationSec = 200, sizeBytes = 5_000, suffix = "flac", contentType = "audio/flac", bitRate = 900,
        coverArt = cover, path = "$artist/$album/${disc ?: 1}-$no $id.flac", created = null,
        composer = "Comp", sampleRate = 96_000, bitDepth = 24, channels = 2,
    )

    @Before
    fun setUp() {
        app.libraryDatabase.wipe()
        app.credentialStore.save(SavedServer(ServerUrl.parse("http://music.test:4533"), ServerAuth.Password("user", "pw")))
        app.libraryDatabase.apply(
            LibraryUpdate(
                identity = "test",
                libraries = listOf(LibraryRecord(1, "Music Library")),
                tracks = TrackDiff(
                    inserted = listOf(
                        // Disc 2 listed first on purpose: ordering must come from disc/track numbers.
                        track("d2t1", "OST", "Kosaki", disc = 2, no = 1),
                        track("d1t2", "OST", "Kosaki", disc = 1, no = 2),
                        track("d1t1", "OST", "Kosaki", disc = 1, no = 1),
                        track("solo", "Single", "Andora", disc = null, no = 1, cover = null),
                    ),
                    updated = emptyList(), deletedIds = emptySet(),
                ),
                playlists = PlaylistDiff(emptyList(), emptySet()),
                syncedAt = 1_000,
            )
        )
    }

    @After
    fun tearDown() {
        app.libraryDatabase.wipe()
        app.credentialStore.clear()
    }

    private fun Cursor.rows(): List<Map<String, Any?>> = use { c ->
        buildList {
            while (c.moveToNext()) add(c.columnNames.associateWith { col ->
                val i = c.getColumnIndex(col)
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> c.getString(i)
                }
            })
        }
    }

    private fun children(docId: String, projection: Array<String>? = null) =
        resolver.query(DocumentsContract.buildChildDocumentsUri(authority, docId), projection, null, null, null)!!.rows()

    @Test
    fun rootPerLibrary() {
        val roots = resolver.query(DocumentsContract.buildRootsUri(authority), null, null, null, null)!!.rows()
        assertEquals(1, roots.size)
        assertEquals("lib:1", roots[0][Root.COLUMN_DOCUMENT_ID])
        assertEquals("Music Library · music.test", roots[0][Root.COLUMN_SUMMARY])
        assertTrue((roots[0][Root.COLUMN_FLAGS] as Long).toInt() and Root.FLAG_SUPPORTS_IS_CHILD != 0)
    }

    @Test
    fun noRootsWithoutServer() {
        app.credentialStore.clear()
        assertEquals(0, resolver.query(DocumentsContract.buildRootsUri(authority), null, null, null, null)!!.count)
    }

    @Test
    fun hierarchyIsLibraryArtistAlbumTrack() {
        val artists = children("lib:1")
        assertEquals(listOf("Andora", "Kosaki"), artists.map { it[Document.COLUMN_DISPLAY_NAME] })
        assertTrue(artists.all { it[Document.COLUMN_MIME_TYPE] == Document.MIME_TYPE_DIR })
        assertEquals(1_000L, artists[0][Document.COLUMN_LAST_MODIFIED])

        // Poweramp asks for its custom __flags column explicitly; it's not in the default projection.
        val albums = children(
            "artist:1:ar-Kosaki",
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_FLAGS, TrackProviderConsts.COLUMN_FLAGS),
        )
        assertEquals(listOf("album:al-OST"), albums.map { it[Document.COLUMN_DOCUMENT_ID] })
        assertTrue((albums[0][Document.COLUMN_FLAGS] as Long).toInt() and Document.FLAG_SUPPORTS_THUMBNAIL != 0)
        assertEquals(TrackProviderConsts.FLAG_NO_SUBDIRS.toLong(), albums[0][TrackProviderConsts.COLUMN_FLAGS])
    }

    @Test
    fun albumTracksCarryMetadataInDiscOrder() {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            MediaStore.MediaColumns.TITLE, MediaStore.Audio.AudioColumns.DURATION, MediaStore.Audio.AudioColumns.TRACK,
            TrackProviderConsts.COLUMN_TRACK_ALT, TrackProviderConsts.COLUMN_URL, TrackProviderConsts.COLUMN_ALBUM_ARTIST,
            TrackProviderConsts.COLUMN_BITS_PER_SAMPLE, TrackProviderConsts.COLUMN_TRACK_WAVE, "sample-rate",
        )
        val tracks = children("album:al-OST", projection)

        assertEquals(listOf("track:d1t1", "track:d1t2", "track:d2t1"), tracks.map { it[Document.COLUMN_DOCUMENT_ID] })
        assertEquals(listOf(1001L, 1002L, 2001L), tracks.map { it[MediaStore.Audio.AudioColumns.TRACK] })
        assertEquals(listOf(1L, 2L, 3L), tracks.map { it[TrackProviderConsts.COLUMN_TRACK_ALT] })
        val first = tracks[0]
        assertEquals("Title d1t1", first[MediaStore.MediaColumns.TITLE])
        assertEquals(200_000L, first[MediaStore.Audio.AudioColumns.DURATION])
        assertNull("tracks are files now, not URLs", first[TrackProviderConsts.COLUMN_URL])
        assertEquals("01. Title d1t1.flac", first[Document.COLUMN_DISPLAY_NAME])
        assertEquals("audio/flac", first[Document.COLUMN_MIME_TYPE])
        assertEquals("Kosaki", first[TrackProviderConsts.COLUMN_ALBUM_ARTIST])
        assertEquals(24L, first[TrackProviderConsts.COLUMN_BITS_PER_SAMPLE])
        assertEquals(96_000L, first["sample-rate"])
        assertEquals(0, (first[TrackProviderConsts.COLUMN_TRACK_WAVE] as ByteArray).size)
    }

    @Test
    fun singleDiscAlbumKeepsPlainTrackNumbers() {
        val tracks = children("album:al-Single", arrayOf(MediaStore.Audio.AudioColumns.TRACK, Document.COLUMN_FLAGS))
        assertEquals(1L, tracks.single()[MediaStore.Audio.AudioColumns.TRACK])
        assertEquals(0L, tracks.single()[Document.COLUMN_FLAGS]) // no cover, no thumbnail flag
    }

    @Test
    fun queryDocumentWorksForEveryLevel() {
        listOf("lib:1", "artist:1:ar-Kosaki", "album:al-OST", "track:d1t1").forEach { id ->
            val rows = resolver.query(DocumentsContract.buildDocumentUri(authority, id), null, null, null, null)!!.rows()
            assertEquals(id, rows.single()[Document.COLUMN_DOCUMENT_ID])
        }
    }

    @Test
    fun treeUrisResolveChildrenAndAncestors() {
        // How Poweramp addresses documents after the user picks the root in Music Folders.
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "lib:1")
        val album = DocumentsContract.buildDocumentUriUsingTree(tree, "album:al-OST")
        assertTrue(DocumentsContract.isTreeUri(album))
        val rows = resolver.query(album, null, null, null, null)!!.rows() // goes through isChildDocument
        assertEquals("album:al-OST", rows.single()[Document.COLUMN_DOCUMENT_ID])

        val meta = resolver.call(album, TrackProviderConsts.CALL_GET_DIR_METADATA, album.toString(), null)!!
        assertEquals(listOf("lib:1", "artist:1:ar-Kosaki"), meta.getStringArray(TrackProviderConsts.EXTRA_ANCESTORS)!!.toList())
    }

    private fun addPlaylist() = app.libraryDatabase.apply(
        LibraryUpdate(
            identity = "test",
            libraries = listOf(LibraryRecord(1, "Music Library")),
            tracks = TrackDiff(emptyList(), emptyList(), emptySet()),
            playlists = PlaylistDiff(
                listOf(PlaylistRecord("pl1", "Road/Trip", null, null, 3, 600, "c1", null, listOf("solo", "d2t1", "gone"))),
                emptySet(),
            ),
            syncedAt = 5_000,
        )
    )

    @Test
    fun noPlaylistsFolderWithoutPlaylists() {
        assertFalse(children("lib:1").any { it[Document.COLUMN_DOCUMENT_ID] == "playlists" })
    }

    @Test
    fun playlistsFolderHoldsM3u8Files() {
        addPlaylist()
        val root = children("lib:1")
        assertEquals("playlists", root.first()[Document.COLUMN_DOCUMENT_ID])
        assertEquals("Playlists", root.first()[Document.COLUMN_DISPLAY_NAME])
        assertEquals(5_000L, root.first()[Document.COLUMN_LAST_MODIFIED])

        val playlists = children("playlists")
        val pl = playlists.single()
        assertEquals("playlist:pl1.m3u8", pl[Document.COLUMN_DOCUMENT_ID])
        assertEquals("Road∕Trip", pl[Document.COLUMN_DISPLAY_NAME])
        assertEquals("audio/x-mpegurl", pl[Document.COLUMN_MIME_TYPE])

        val uri = DocumentsContract.buildDocumentUri(authority, "playlist:pl1.m3u8")
        val text = resolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(
            listOf(
                "#EXTM3U",
                "#PLAYLIST:Road/Trip",
                "#EXTINF:200,Andora - Title solo",
                "@dev.subsonic.poweramp.provider/lib%3A1/track%3Asolo",
                "#EXTINF:200,Kosaki - Title d2t1",
                "@dev.subsonic.poweramp.provider/lib%3A1/track%3Ad2t1",
            ),
            text.trim().lines(),
        )

        // Tree access and ancestors work for playlists too.
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "lib:1")
        val inTree = DocumentsContract.buildDocumentUriUsingTree(tree, "playlist:pl1.m3u8")
        assertEquals(1, resolver.query(inTree, null, null, null, null)!!.use { it.count })
        val meta = resolver.call(inTree, TrackProviderConsts.CALL_GET_DIR_METADATA, inTree.toString(), null)!!
        assertEquals(listOf("lib:1", "playlists"), meta.getStringArray(TrackProviderConsts.EXTRA_ANCESTORS)!!.toList())
    }

    @Test
    fun tracksOpenAsSeekableFilesOfTheLibrarySize() {
        // Opening must not touch the network (the test server doesn't exist); reads would.
        val uri = DocumentsContract.buildDocumentUri(authority, "track:d1t1")
        resolver.openFileDescriptor(uri, "r")!!.use { assertEquals(5_000L, it.statSize) }
        assertTrue(runCatching { resolver.openFileDescriptor(uri, "w") }.isFailure)
    }

    @Test
    fun unknownDocumentsAreNotFound() {
        // DocumentsProvider turns the provider's FileNotFoundException into a null cursor
        // (or an exception, depending on the Android version); either way no row comes back.
        val result = runCatching {
            resolver.query(DocumentsContract.buildDocumentUri(authority, "track:nope"), null, null, null, null)?.use { it.count }
        }.getOrNull()
        assertTrue("got rows for a missing track", result == null || result == 0)
    }
}
