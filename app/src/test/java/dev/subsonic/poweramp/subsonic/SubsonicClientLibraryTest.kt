package dev.subsonic.poweramp.subsonic

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Client calls used by the library sync. */
class SubsonicClientLibraryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(http: OkHttpClient = OkHttpClient()) =
        SubsonicClient(ServerUrl.parse(server.url("/").toString()), SubsonicAuth.Password("admin", "pw"), http)

    private fun ok(inner: String) = MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"$inner}}""")

    @Test
    fun `all songs page sends an empty search3 query with paging and library`() = runTest {
        server.enqueue(ok(""","searchResult3":{"song":[{"id":"a","title":"A","duration":10,"albumArtists":[{"id":"x","name":"X"}]}]}"""))

        val songs = client().allSongsPage(offset = 500, count = 250, musicFolderId = 3)

        assertEquals(listOf("a"), songs.map { it.id })
        assertEquals("X", songs[0].albumArtists.single().name)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/search3.view", url.encodedPath)
        assertEquals("", url.queryParameter("query"))
        assertEquals("0", url.queryParameter("artistCount"))
        assertEquals("0", url.queryParameter("albumCount"))
        assertEquals("250", url.queryParameter("songCount"))
        assertEquals("500", url.queryParameter("songOffset"))
        assertEquals("3", url.queryParameter("musicFolderId"))
    }

    @Test
    fun `empty search result decodes as an empty list`() = runTest {
        server.enqueue(ok(""","searchResult3":{}"""))
        assertTrue(client().allSongsPage(0, 500).isEmpty())
    }

    @Test
    fun `numeric ids and a single object instead of an array are accepted`() = runTest {
        // Some servers' JSON uses numbers for ids and collapses one-element lists to an object.
        server.enqueue(ok(""","playlists":{"playlist":{"id":42,"name":"Solo","songCount":1,"duration":5}}"""))

        val playlists = client().playlists()

        assertEquals(1, playlists.size)
        assertEquals("42", playlists[0].id)
    }

    @Test
    fun `playlist entries and music folders decode`() = runTest {
        server.enqueue(ok(""","playlist":{"id":"p1","name":"Mix","changed":"2026-01-01","entry":[{"id":"s1"},{"id":"s2"}]}"""))
        server.enqueue(ok(""","musicFolders":{"musicFolder":[{"id":1,"name":"Music"},{"id":2,"name":"Audiobooks"}]}"""))

        assertEquals(listOf("s1", "s2"), client().playlist("p1").entry.map { it.id })
        assertEquals(listOf(1, 2), client().musicFolders().map { it.id })
    }

    @Test
    fun `cancelling the coroutine cancels the http call`() {
        // Accepts the request and never answers (and still shuts down cleanly, unlike a long delay).
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

        val start = System.nanoTime()
        try {
            runBlocking { withTimeout(300) { client(http).ping() } }
            throw AssertionError("expected timeout")
        } catch (e: TimeoutCancellationException) {
            // expected
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $elapsedMs ms; the request was not cancelled", elapsedMs < 3_000)
        // The cancelled call is torn down on OkHttp's own thread; give it a moment.
        val deadline = System.currentTimeMillis() + 2_000
        while (http.dispatcher.runningCallsCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(0, http.dispatcher.runningCallsCount())
    }
}
