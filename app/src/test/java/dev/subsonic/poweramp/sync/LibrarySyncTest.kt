package dev.subsonic.poweramp.sync

import dev.subsonic.poweramp.server.ServerAuth
import dev.subsonic.poweramp.settings.SavedServer
import dev.subsonic.poweramp.subsonic.ServerUrl
import dev.subsonic.poweramp.subsonic.ServerFeatures
import dev.subsonic.poweramp.subsonic.SubsonicServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibrarySyncTest {

    /** A tiny fake Subsonic server whose library the tests edit between syncs. */
    private class FakeServer : Dispatcher() {
        data class FakeSong(val id: String, val title: String, val album: String = "Album", val library: Int = 1)
        data class FakePlaylist(val id: String, val changed: String, val songIds: List<String>)

        val songs = mutableListOf<FakeSong>()
        val playlists = mutableListOf<FakePlaylist>()
        var libraries = listOf(1)
        var scanning = false
        val requests = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val method = url.pathSegments.last().removeSuffix(".view")
            requests += method
            val inner = when (method) {
                "getScanStatus" -> ""","scanStatus":{"scanning":$scanning,"count":${songs.size}}"""
                "getMusicFolders" -> ""","musicFolders":{"musicFolder":[${libraries.joinToString { """{"id":$it,"name":"Lib $it"}""" }}]}"""
                "search3" -> {
                    val offset = url.queryParameter("songOffset")!!.toInt()
                    val count = url.queryParameter("songCount")!!.toInt()
                    val lib = url.queryParameter("musicFolderId")?.toInt()
                    val page = songs.filter { lib == null || it.library == lib }.drop(offset).take(count)
                    ""","searchResult3":{"song":[${page.joinToString { it.json() }}]}"""
                }
                "getPlaylists" -> ""","playlists":{"playlist":[${playlists.joinToString { """{"id":"${it.id}","name":"P","changed":"${it.changed}","songCount":${it.songIds.size}}""" }}]}"""
                "getPlaylist" -> {
                    val p = playlists.first { it.id == url.queryParameter("id") }
                    ""","playlist":{"id":"${p.id}","name":"P","changed":"${p.changed}","entry":[${p.songIds.joinToString { """{"id":"$it"}""" }}]}"""
                }
                else -> return MockResponse().setResponseCode(404)
            }
            return MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"$inner}}""")
        }

        private fun FakeSong.json() =
            """{"id":"$id","title":"$title","album":"$album","albumId":"al-$album","artist":"Art","artistId":"ar1","duration":100}"""
    }

    private lateinit var mock: MockWebServer
    private val fake = FakeServer()
    private val store = InMemoryLibraryStore()
    private var now = 1_000L

    private lateinit var server: SavedServer
    private lateinit var sync: LibrarySync

    @Before
    fun setUp() {
        mock = MockWebServer().apply { dispatcher = fake; start() }
        server = SavedServer(ServerUrl.parse(mock.url("/").toString()), ServerAuth.Password("admin", "pw"))
        val http = OkHttpClient()
        sync = LibrarySync(store, { SubsonicServer(it.baseUrl, it.auth, http, ServerFeatures()) }, clock = { now }, pageSize = 2)
    }

    @After
    fun tearDown() {
        mock.shutdown()
    }

    @Test
    fun `first sync pages through everything`() = runTest {
        (1..5).forEach { fake.songs += FakeServer.FakeSong("s$it", "Song $it") }

        val result = sync.sync(server)

        assertEquals(5, result.added)
        assertEquals(5, store.tracks.size)
        assertEquals(server.identity, store.identity)
        assertEquals(listOf(LibraryRecord(1, "Lib 1")), store.libraries)
        // pageSize 2: pages of 2, 2, 1
        assertEquals(3, fake.requests.count { it == "search3" })
    }

    @Test
    fun `second sync with no server changes changes nothing`() = runTest {
        fake.songs += FakeServer.FakeSong("s1", "One")
        sync.sync(server)
        now = 2_000

        val result = sync.sync(server)

        assertFalse(result.changed)
        assertEquals("No changes (1 tracks)", result.summary())
        assertEquals(1_000L, store.trackModified["s1"]) // last-modified untouched
    }

    @Test
    fun `edits, additions and removals are detected`() = runTest {
        fake.songs += listOf(FakeServer.FakeSong("s1", "One"), FakeServer.FakeSong("s2", "Two"))
        sync.sync(server)
        now = 2_000
        fake.songs[0] = FakeServer.FakeSong("s1", "One (Remastered)")
        fake.songs.removeAt(1)
        fake.songs += FakeServer.FakeSong("s3", "Three")

        val result = sync.sync(server)

        assertEquals(1, result.added)
        assertEquals(1, result.updated)
        assertEquals(1, result.removed)
        assertEquals("One (Remastered)", store.tracks["s1"]!!.title)
        assertEquals(2_000L, store.trackModified["s1"])
        assertEquals(setOf("s1", "s3"), store.tracks.keys)
    }

    @Test
    fun `tracks are tagged with their library`() = runTest {
        fake.libraries = listOf(1, 2)
        fake.songs += listOf(FakeServer.FakeSong("a", "A", library = 1), FakeServer.FakeSong("b", "B", library = 2))

        sync.sync(server)

        assertEquals(1, store.tracks["a"]!!.libraryId)
        assertEquals(2, store.tracks["b"]!!.libraryId)
    }

    @Test
    fun `playlists are only refetched when changed`() = runTest {
        fake.songs += FakeServer.FakeSong("s1", "One")
        fake.playlists += FakeServer.FakePlaylist("p1", "v1", listOf("s1"))
        sync.sync(server)
        assertEquals(listOf("s1"), store.playlists["p1"]!!.trackIds)

        fake.requests.clear()
        sync.sync(server)
        assertFalse("unchanged playlist was refetched", "getPlaylist" in fake.requests)

        fake.playlists[0] = FakeServer.FakePlaylist("p1", "v2", listOf("s1", "s1"))
        val result = sync.sync(server)
        assertEquals(1, result.playlistsChanged)
        assertEquals(listOf("s1", "s1"), store.playlists["p1"]!!.trackIds)

        fake.playlists.clear()
        sync.sync(server)
        assertTrue(store.playlists.isEmpty())
    }

    @Test(expected = ServerBusyException::class)
    fun `sync waits while the server is scanning`() = runTest {
        fake.scanning = true
        sync.sync(server)
    }

    @Test
    fun `an empty result never wipes an existing library`() = runTest {
        fake.songs += FakeServer.FakeSong("s1", "One")
        sync.sync(server)
        fake.songs.clear()

        try {
            sync.sync(server)
            throw AssertionError("expected SuspiciousEmptyLibraryException")
        } catch (e: SuspiciousEmptyLibraryException) {
            // expected
        }
        assertEquals(1, store.tracks.size)
    }

    @Test
    fun `switching account replaces the library`() = runTest {
        fake.songs += FakeServer.FakeSong("s1", "One")
        sync.sync(server)

        val other = server.copy(auth = ServerAuth.Password("someone-else", "pw"))
        val result = sync.sync(other)

        assertTrue(result.wiped)
        assertTrue(result.changed)
        assertEquals(1, store.wipeCount)
        assertEquals(other.identity, store.identity)
        assertEquals(1, result.added)
    }
}
