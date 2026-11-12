package dev.subsonic.poweramp.sync

import dev.subsonic.poweramp.subsonic.ArtistRef
import dev.subsonic.poweramp.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRecordTest {

    private val song = Song(
        id = "s1", title = "Blaze", album = "OST", albumId = "al1",
        artist = "A • B", artistId = "ar-a",
        displayArtist = "A • B", displayAlbumArtist = "C • D",
        albumArtists = listOf(ArtistRef("ar-c", "C"), ArtistRef("ar-d", "D")),
        track = 32, discNumber = 1, duration = 118, size = 1000, suffix = "flac",
    )

    @Test
    fun `compilation tracks are grouped under the first album artist`() {
        val r = song.toTrackRecord(1)
        assertEquals("ar-c", r.albumArtistId)
        assertEquals("C • D", r.albumArtist)
        assertEquals("A • B", r.artist)
    }

    @Test
    fun `classic subsonic songs fall back to the track artist`() {
        val r = Song(id = "s", title = "T", artist = "Solo", artistId = "ar-s").toTrackRecord(DEFAULT_LIBRARY_ID)
        assertEquals("ar-s", r.albumArtistId)
        assertEquals("Solo", r.albumArtist)
    }

    @Test
    fun `hash is stable and changes when any field changes`() {
        val a = song.toTrackRecord(1)
        assertEquals(a.contentHash, song.toTrackRecord(1).contentHash)
        assertEquals(32, a.contentHash.length)
        assertNotEquals(a.contentHash, song.copy(title = "Blaze!").toTrackRecord(1).contentHash)
        assertNotEquals(a.contentHash, song.copy(track = 33).toTrackRecord(1).contentHash)
        assertNotEquals(a.contentHash, song.toTrackRecord(2).contentHash)
    }

    @Test
    fun `composer and audio format are mapped, zeros and blanks dropped`() {
        val r = song.copy(displayComposer = "Kosaki", samplingRate = 44100, bitDepth = 24, channelCount = 2).toTrackRecord(1)
        assertEquals("Kosaki", r.composer)
        assertEquals(44100, r.sampleRate)
        assertEquals(24, r.bitDepth)
        assertEquals(2, r.channels)

        val empty = song.copy(displayComposer = " ", samplingRate = 0, bitDepth = 0, channelCount = 0).toTrackRecord(1)
        assertEquals(null, empty.composer)
        assertEquals(null, empty.sampleRate)
        assertEquals(null, empty.bitDepth)
        assertEquals(null, empty.channels)

        assertNotEquals(r.contentHash, song.copy(displayComposer = "Other", samplingRate = 44100, bitDepth = 24, channelCount = 2).toTrackRecord(1).contentHash)
    }

    @Test
    fun `null and empty string hash differently`() {
        assertNotEquals(
            song.copy(genre = null).toTrackRecord(1).contentHash,
            song.copy(genre = "").toTrackRecord(1).contentHash,
        )
    }

    @Test
    fun `diff finds inserts, updates and deletes`() {
        val keep = song.toTrackRecord(1)
        val changed = song.copy(id = "s2").toTrackRecord(1)
        val added = song.copy(id = "s3").toTrackRecord(1)
        val existing = mapOf(keep.id to keep.contentHash, "s2" to "old-hash", "gone" to "h")

        val diff = TrackDiff.between(existing, listOf(keep, changed, added))

        assertEquals(listOf("s3"), diff.inserted.map { it.id })
        assertEquals(listOf("s2"), diff.updated.map { it.id })
        assertEquals(setOf("gone"), diff.deletedIds)
        assertTrue(TrackDiff.between(mapOf(keep.id to keep.contentHash), listOf(keep)).isEmpty)
    }
}
