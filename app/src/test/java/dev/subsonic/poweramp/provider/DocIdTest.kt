package dev.subsonic.poweramp.provider

import dev.subsonic.poweramp.library.TrackRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocIdTest {

    @Test
    fun `ids round trip`() {
        listOf(
            DocId.Library(1),
            DocId.Artist(2, "0EeSCUY4dCkAePb7Qt0vLM"),
            DocId.Album("2fFEJcqvGdYqEDcuaLvG3r"),
            DocId.Track("3CuAPq2EiA2QCpSDAA1zXo"),
        ).forEach { assertEquals(it, DocId.parse(it.encoded)) }
    }

    @Test
    fun `server ids containing colons survive`() {
        assertEquals(DocId.Artist(1, "a:b:c"), DocId.parse("artist:1:a:b:c"))
        assertEquals(DocId.Track("x:y"), DocId.parse("track:x:y"))
    }

    @Test
    fun `foreign or malformed ids are rejected`() {
        listOf("", "root1", "lib:", "lib:abc", "artist:1", "artist:x:y", "album:", "folder:1").forEach {
            assertNull("accepted '$it'", DocId.parse(it))
        }
    }

    private fun row(trackNo: Int?, discNo: Int?, discCount: Int, path: String? = null, title: String = "Song", suffix: String? = "flac") = TrackRow(
        id = "t", libraryId = 1, title = title, albumId = "al", album = "A", artist = null, albumArtistId = null,
        albumArtist = null, composer = null, trackNo = trackNo, discNo = discNo, albumDiscCount = discCount, year = null,
        genre = null, durationSec = 100, sizeBytes = null, suffix = suffix, contentType = null, bitRate = null,
        sampleRate = null, bitDepth = null, channels = null, coverArt = null, path = path, lastModified = 0,
    )

    @Test
    fun `multi-disc albums use disc x 1000 + track`() {
        assertEquals(2013, SubsonicDocumentsProvider.trackNumber(row(13, 2, discCount = 2)))
        assertEquals(1001, SubsonicDocumentsProvider.trackNumber(row(1, 1, discCount = 2)))
        assertEquals(7, SubsonicDocumentsProvider.trackNumber(row(7, 1, discCount = 1)))
        assertEquals(7, SubsonicDocumentsProvider.trackNumber(row(7, null, discCount = 2)))
        assertNull(SubsonicDocumentsProvider.trackNumber(row(null, 1, discCount = 2)))
    }

    @Test
    fun `display name comes from the server path, else is built`() {
        assertEquals("01-32 - Blaze.flac", SubsonicDocumentsProvider.displayName(row(32, 1, 1, path = "Artist/Album/01-32 - Blaze.flac")))
        assertEquals("05 - AC∕DC Song.mp3", SubsonicDocumentsProvider.displayName(row(5, null, 1, title = "AC/DC Song", suffix = "mp3")))
        assertEquals("Song", SubsonicDocumentsProvider.displayName(row(null, null, 1, suffix = null)))
    }
}
