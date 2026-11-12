package dev.subsonic.poweramp.subsonic

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Lyrics, scrobbling and extension detection. */
class SubsonicClientPhase4Test {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() =
        SubsonicClient(ServerUrl.parse(server.url("/").toString()), SubsonicAuth.Password("admin", "pw"), OkHttpClient())

    private fun ok(inner: String = "") = MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"$inner}}""")

    @Test
    fun `structured lyrics decode`() = runTest {
        server.enqueue(ok(""","lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"offset":-100,
            "line":[{"start":0,"value":"a"},{"start":1500,"value":"b"}]}]}"""))

        val lyrics = client().lyricsBySongId("s1").single()

        assertTrue(lyrics.synced)
        assertEquals(-100L, lyrics.offset)
        assertEquals(listOf(0L, 1500L), lyrics.line.map { it.start })
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/getLyricsBySongId.view", url.encodedPath)
        assertEquals("s1", url.queryParameter("id"))
    }

    @Test
    fun `no lyrics is an empty list`() = runTest {
        server.enqueue(ok(""","lyricsList":{}"""))
        assertTrue(client().lyricsBySongId("s1").isEmpty())
    }

    @Test
    fun `classic lyrics blank value is null`() = runTest {
        server.enqueue(ok(""","lyrics":{"value":""}"""))
        assertNull(client().lyrics("A", "T"))
        server.enqueue(ok(""","lyrics":{"artist":"A","title":"T","value":"la la"}"""))
        assertEquals("la la", client().lyrics("A", "T"))
    }

    @Test
    fun `scrobble sends time and submission`() = runTest {
        server.enqueue(ok())
        client().scrobble("s1", 1_700_000_000_000, submission = true)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/scrobble.view", url.encodedPath)
        assertEquals("s1", url.queryParameter("id"))
        assertEquals("1700000000000", url.queryParameter("time"))
        assertEquals("true", url.queryParameter("submission"))
    }

    @Test
    fun `report playback never asks the server to scrobble`() = runTest {
        server.enqueue(ok())
        client().reportPlayback("s1", "playing", 42_000)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/reportPlayback.view", url.encodedPath)
        assertEquals("s1", url.queryParameter("mediaId"))
        assertEquals("song", url.queryParameter("mediaType"))
        assertEquals("playing", url.queryParameter("state"))
        assertEquals("42000", url.queryParameter("positionMs"))
        assertEquals("true", url.queryParameter("ignoreScrobble"))
    }

    @Test
    fun `server features are cached, and a failed check is retried`() = runTest {
        var now = 0L
        val features = ServerFeatures(ttlMs = 1_000_000, clock = { now })
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(features.supports("k", client(), Extensions.SONG_LYRICS))

        now = 61_000
        server.enqueue(ok(""","openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]"""))
        assertTrue(features.supports("k", client(), Extensions.SONG_LYRICS))
        assertFalse(features.supports("k", client(), Extensions.PLAYBACK_REPORT)) // cached, no request
        assertEquals(2, server.requestCount)
    }
}
