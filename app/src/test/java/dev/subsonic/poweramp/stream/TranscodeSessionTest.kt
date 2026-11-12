package dev.subsonic.poweramp.stream

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TranscodeSessionTest {

    @get:Rule val tmp = TemporaryFolder()

    /** What the server "transcodes" to: shorter than the size the session is given. */
    private val mp3 = Random(7).nextBytes(400_000)
    private val size = StreamSession.mp3Size(durationSec = 16, kbps = 192) // ≈ 506 KB
    private var contentType = "audio/mpeg"
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                // Transcodes ignore Range and arrive slowly.
                return MockResponse().setHeader("Content-Type", contentType)
                    .setBody(Buffer().write(mp3))
                    .throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun session() = StreamSession(
        trackId = "t",
        url = server.url("/rest/stream.view"),
        size = size,
        file = SparseFile(tmp.root.resolve("t.audio"), size),
        http = OkHttpClient(),
        readAhead = { 0 }, // ignored for transcodes: always the whole stream
        readTimeoutMs = 10_000,
        transcoded = true,
    )

    private fun StreamSession.bytes(offset: Long, len: Int) = ByteArray(len).also { assertEquals(len, read(offset, len, it)) }

    @Test
    fun `peeks at the end are answered at once with zeros`() {
        val s = session()
        val start = System.currentTimeMillis()
        val tail = s.bytes(size - 4096, 4096)
        assertTrue("took ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 200)
        assertTrue(tail.all { it == 0.toByte() })
        s.close()
    }

    @Test
    fun `the stream is downloaded once without ranges and padded with zeros`() {
        val s = session()
        assertArrayEquals(mp3.copyOfRange(0, 1000), s.bytes(0, 1000))
        assertArrayEquals(mp3.copyOfRange(300_000, 310_000), s.bytes(300_000, 10_000)) // waits, no range request
        val pad = s.bytes(mp3.size.toLong(), (size - mp3.size).toInt())
        assertTrue(pad.all { it == 0.toByte() })
        assertTrue(s.isComplete)
        s.close()
        assertEquals(1, requests.size)
        assertEquals(null, requests[0].getHeader("Range"))
    }

    @Test
    fun `a server that sends the original instead fails the read`() {
        contentType = "audio/flac"
        val s = session()
        try {
            s.bytes(0, 100)
            fail("should not play a FLAC as the MP3 transcode")
        } catch (e: IOException) {
            // expected
        }
        s.close()
    }

    @Test
    fun `size estimate covers measured Navidrome transcodes`() {
        // (duration s, kbps, real bytes) measured on Navidrome 0.64.2.
        listOf(
            Triple(119, 128, 1_919_290L), Triple(119, 320, 4_797_566L), Triple(107, 320, 4_306_339L),
            Triple(241, 192, 5_808_087L), Triple(156, 192, 3_767_323L), Triple(258, 320, 10_360_851L),
        ).forEach { (dur, kbps, real) ->
            val audioRoom = StreamSession.mp3Size(dur, kbps) - StreamSession.ZERO_TAIL
            assertTrue("$dur s @ $kbps: room $audioRoom < real $real", audioRoom >= real)
        }
    }
}
