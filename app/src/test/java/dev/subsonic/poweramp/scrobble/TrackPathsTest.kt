package dev.subsonic.poweramp.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPathsTest {

    private val auth = "dev.subsonic.poweramp.provider"

    @Test
    fun `document and tree uris`() {
        assertEquals("abc", TrackPaths.songId("content://$auth/document/track%3Aabc", auth))
        assertEquals("abc", TrackPaths.songId("content://$auth/tree/lib%3A1/document/track%3Aabc", auth))
        assertEquals("abc", TrackPaths.songId("content%3A%2F%2F$auth%2Ftree%2Flib%253A1%2Fdocument%2Ftrack%253Aabc", auth))
    }

    @Test
    fun `poweramp style path with the document id as last segment`() {
        assertEquals("x1", TrackPaths.songId("$auth/lib:1/artist:1:a/album:b/track:x1", auth))
        // What Poweramp build 1035 reports for file tracks of ours.
        assertEquals("28FIzos80pG8j3nvohEIMD", TrackPaths.songId("@$auth/lib%3A1/track%3A28FIzos80pG8j3nvohEIMD", auth))
    }

    @Test
    fun `stream url of our server`() {
        val prefix = "http://127.0.0.1:4533/rest/stream"
        assertEquals("s9", TrackPaths.songId("$prefix.view?v=1&u=admin&t=x&s=y&id=s9", auth, prefix))
        assertNull(TrackPaths.songId("http://other/rest/stream.view?id=s9", auth, prefix))
    }

    @Test
    fun `other sources are not ours`() {
        assertNull(TrackPaths.songId(null, auth))
        assertNull(TrackPaths.songId("/storage/emulated/0/Music/a.flac", auth))
        assertNull(TrackPaths.songId("content://$auth/document/album%3Aabc", auth))
        assertNull(TrackPaths.songId("content://com.other/document/track%3Aabc", auth))
    }

    @Test
    fun `redaction drops the query string`() {
        assertEquals("http://h/rest/stream.view", TrackPaths.redacted("http://h/rest/stream.view?t=secret&s=salt"))
    }
}
