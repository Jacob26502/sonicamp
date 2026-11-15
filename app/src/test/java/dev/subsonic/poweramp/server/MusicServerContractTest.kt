package dev.subsonic.poweramp.server

import dev.subsonic.poweramp.lyrics.TrackLyrics
import dev.subsonic.poweramp.settings.SavedServer
import dev.subsonic.poweramp.subsonic.ServerUrl
import dev.subsonic.poweramp.subsonic.SubsonicApiException
import dev.subsonic.poweramp.sync.InMemoryLibraryStore
import dev.subsonic.poweramp.sync.LibraryRecord
import dev.subsonic.poweramp.sync.LibrarySync
import dev.subsonic.poweramp.sync.PlaylistRecord
import dev.subsonic.poweramp.sync.ServerBusyException
import dev.subsonic.poweramp.sync.TrackRecord
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The app's sync runs against any [MusicServer]; nothing outside the subsonic package needs Subsonic. */
class MusicServerContractTest {

    private fun track(id: String, lib: Int) = TrackRecord(
        id = id, libraryId = lib, title = "T$id", albumId = "al", album = "A", artist = "Ar", albumArtistId = "ar",
        albumArtist = "Ar", trackNo = 1, discNo = null, year = null, genre = null, durationSec = 60, sizeBytes = 1,
        suffix = "flac", contentType = "audio/flac", bitRate = null, coverArt = null, path = null, created = null,
    )

    /** An in-memory server, as a second implementation (e.g. Jellyfin) would look to the app. */
    private class FakeServer(
        val libs: List<LibraryRecord>,
        val tracksByLib: Map<Int?, List<TrackRecord>>,
        val lists: List<PlaylistRecord>,
        var scanning: Boolean = false,
    ) : MusicServer {
        val fetchedPlaylists = mutableListOf<String>()
        override val cacheKey = "fake"
        override suspend fun connect() = ServerDescription("Fake")
        override suspend fun isScanning() = scanning
        override suspend fun libraries() = libs
        override suspend fun tracks(libraryId: Int?, offset: Int, count: Int) =
            tracksByLib[libraryId].orEmpty().drop(offset).take(count)
        override suspend fun playlists() = lists.map { PlaylistVersion(it.id, it.changed) }
        override suspend fun playlist(id: String) = lists.first { it.id == id }.also { fetchedPlaylists += id }
        override fun streamUrl(trackId: String, quality: StreamQuality): HttpUrl = "http://fake/$trackId".toHttpUrl()
        override fun coverUrl(coverId: String, sizePx: Int): HttpUrl = "http://fake/c/$coverId".toHttpUrl()
        override suspend fun lyrics(trackId: String, artist: String?, title: String) = TrackLyrics.NONE
        override suspend fun reportPlayback(trackId: String, state: PlaybackState, positionMs: Long) = Unit
        override suspend fun scrobble(trackId: String, playedAtMs: Long) = Unit
    }

    private val saved = SavedServer(ServerUrl.parse("http://fake"), ServerAuth.Password("u", "p"))

    @Test
    fun `sync works against any MusicServer`() = runTest {
        val playlist = PlaylistRecord("p1", "Mix", null, null, 1, 60, "v1", null, listOf("a"))
        val fake = FakeServer(
            libs = listOf(LibraryRecord(1, "One"), LibraryRecord(2, "Two")),
            tracksByLib = mapOf(1 to (1..5).map { track("a$it", 1) }, 2 to listOf(track("b", 2))),
            lists = listOf(playlist),
        )
        val store = InMemoryLibraryStore()
        val sync = LibrarySync(store, { fake }, pageSize = 2)

        val first = sync.sync(saved)
        assertEquals(6, first.added)
        assertEquals(setOf(1, 2), store.tracks.values.map { it.libraryId }.toSet())
        assertEquals(listOf("p1"), fake.fetchedPlaylists)

        val second = sync.sync(saved)
        assertFalse(second.changed)
        assertEquals("unchanged playlist version isn't refetched", listOf("p1"), fake.fetchedPlaylists)
    }

    @Test
    fun `a scanning server is retried later`() = runTest {
        val fake = FakeServer(emptyList(), emptyMap(), emptyList(), scanning = true)
        try {
            LibrarySync(InMemoryLibraryStore(), { fake }).sync(saved)
            fail("should not sync while scanning")
        } catch (e: ServerBusyException) {
            // expected
        }
    }

    @Test
    fun `subsonic error codes map to server-neutral kinds`() {
        assertEquals(ServerException.Kind.WRONG_CREDENTIALS, SubsonicApiException(40, null).kind)
        assertEquals(ServerException.Kind.LOGIN_METHOD_NOT_SUPPORTED, SubsonicApiException(41, null).kind)
        assertEquals(ServerException.Kind.INVALID_API_KEY, SubsonicApiException(44, null).kind)
        assertEquals(ServerException.Kind.NOT_FOUND, SubsonicApiException(70, null).kind)
        assertEquals(ServerException.Kind.OTHER, SubsonicApiException(0, "boom").kind)
        assertTrue(SubsonicApiException(50, null).kind.isLoginProblem)
        assertFalse(SubsonicApiException(70, null).kind.isLoginProblem)
        assertTrue("token auth refusal suggests API keys", SubsonicApiException(41, null).hint!!.contains("API key"))
    }

    @Test
    fun `saved subsonic servers keep their identity`() {
        // Must not change, or every existing library is wiped on update.
        val explicit = SavedServer(ServerUrl.parse("http://fake"), ServerAuth.Password("u", "p"), ServerType.SUBSONIC)
        assertEquals(saved.identity, explicit.identity)
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest("http://fake/\nuser:u".toByteArray())
                .joinToString("") { "%02x".format(it) },
            saved.identity,
        )
    }
}
