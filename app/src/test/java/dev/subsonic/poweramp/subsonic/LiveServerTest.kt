package dev.subsonic.poweramp.subsonic

import dev.subsonic.poweramp.server.ServerAuth
import dev.subsonic.poweramp.settings.SavedServer
import dev.subsonic.poweramp.sync.InMemoryLibraryStore
import dev.subsonic.poweramp.sync.LibrarySync
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real server only when these environment variables are set
 * (otherwise it is skipped):
 *   SUBSONIC_TEST_URL       e.g. http://localhost:4533
 *   SUBSONIC_TEST_USER
 *   SUBSONIC_TEST_PASSWORD
 */
class LiveServerTest {

    private val url = System.getenv("SUBSONIC_TEST_URL")
    private val user = System.getenv("SUBSONIC_TEST_USER")
    private val password = System.getenv("SUBSONIC_TEST_PASSWORD")

    @Test
    fun `connection test succeeds against live server`() = runTest {
        assumeTrue("SUBSONIC_TEST_* not set; skipping live test", url != null && user != null && password != null)

        val info = SubsonicServer(ServerUrl.parse(url!!), ServerAuth.Password(user!!, password!!), OkHttpClient(), ServerFeatures())
            .connect()
        println("Live server: ${info.name}; ${info.details}")
        assertTrue(info.details.first().startsWith("Subsonic API ${SubsonicClient.API_VERSION}"))
    }

    @Test
    fun `full sync then a no-op sync against live server`() = runTest {
        assumeTrue(url != null && user != null && password != null)
        val http = OkHttpClient()
        val store = InMemoryLibraryStore()
        val sync = LibrarySync(store, { SubsonicServer(it.baseUrl, it.auth, http, ServerFeatures()) })
        val server = SavedServer(ServerUrl.parse(url!!), ServerAuth.Password(user!!, password!!))

        val first = sync.sync(server)
        println("Live first sync: ${first.summary()}; playlists=${store.playlists.keys.size}; albumArtists=${store.tracks.values.map { it.albumArtist }.distinct()}")
        assertTrue(first.totalTracks > 0)
        assertEquals(first.totalTracks, first.added)

        val second = sync.sync(server)
        println("Live second sync: ${second.summary()}")
        assertFalse("second sync should find no changes", second.changed)
    }

    @Test
    fun `wrong password is rejected with code 40`() = runTest {
        assumeTrue(url != null && user != null)
        try {
            SubsonicClient(ServerUrl.parse(url!!), ServerAuth.Password(user!!, "definitely-wrong"), OkHttpClient()).ping()
            throw AssertionError("expected rejection")
        } catch (e: SubsonicApiException) {
            assertEquals(SubsonicApiException.WRONG_CREDENTIALS, e.code)
        }
    }
}
