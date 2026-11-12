package dev.subsonic.poweramp.stream

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class RangeSetTest {

    @Test
    fun `ranges merge and report coverage`() {
        val r = RangeSet()
        r.add(10, 20)
        r.add(30, 40)
        assertEquals(20, r.coveredEnd(10))
        assertEquals(25, r.coveredEnd(25))
        assertEquals(30L, r.nextCoveredStart(20))
        assertEquals(20, r.firstGap(10))
        r.add(20, 30) // touching both: one range
        assertEquals(listOf(10L, 40L), r.toList())
        r.add(0, 5)
        r.add(3, 12)
        assertEquals(listOf(0L, 40L), r.toList())
        assertEquals(40, r.coveredBytes)
        assertEquals(r.toList(), RangeSet.fromList(r.toList()).toList())
    }
}

class StreamSessionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val content = Random(42).nextBytes(3 * 1024 * 1024 + 123)
    private lateinit var server: MockWebServer
    private val ranges: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val failNext = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(content))
                ranges += range
                if (failNext.getAndDecrement() > 0) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                val (s, e) = range.removePrefix("bytes=").split('-')
                val start = s.toInt()
                val end = if (e.isEmpty()) content.size - 1 else minOf(e.toInt(), content.size - 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${content.size}")
                    .setBody(Buffer().write(content, start, end - start + 1))
                    .throttleBody(256 * 1024, 10, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun session(readAhead: Long = Long.MAX_VALUE, name: String = "t.audio") = StreamSession(
        trackId = "t",
        url = server.url("/rest/stream.view"),
        size = content.size.toLong(),
        file = SparseFile(tmp.root.resolve(name), content.size.toLong()),
        http = OkHttpClient(),
        readAhead = { readAhead },
        readTimeoutMs = 10_000,
    )

    private fun StreamSession.readAll(offset: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        val n = read(offset, len, buf)
        return buf.copyOf(n)
    }

    private fun expected(offset: Int, len: Int) = content.copyOfRange(offset, minOf(offset + len, content.size))

    @Test
    fun `sequential playback is one request and fills the cache`() {
        val s = session()
        var pos = 0L
        while (pos < content.size) {
            val chunk = s.readAll(pos, 128 * 1024)
            assertArrayEquals(expected(pos.toInt(), 128 * 1024), chunk)
            pos += chunk.size
        }
        waitUntil { s.isComplete }
        s.close()
        assertEquals(listOf("bytes=0-${content.size - 1}"), ranges.toList())
    }

    @Test
    fun `a peek near the end fetches one block without moving the download`() {
        val s = session()
        assertArrayEquals(expected(0, 4096), s.readAll(0, 4096))
        val tail = content.size - 2000L
        assertArrayEquals(expected(tail.toInt(), 2000), s.readAll(tail, 2000))
        waitUntil { s.isComplete }
        s.close()
        assertEquals(2, ranges.size)
        assertEquals("bytes=$tail-${content.size - 1}", ranges[1])
    }

    @Test
    fun `a seek fetches a block, then the download follows the play position`() {
        val s = session(readAhead = 300 * 1024L) // small window: the first download pauses early
        s.readAll(0, 4096)
        val target = 2L * 1024 * 1024
        var pos = target
        repeat(6) { // read past the first block
            val chunk = s.readAll(pos, 64 * 1024)
            assertArrayEquals(expected(pos.toInt(), 64 * 1024), chunk)
            pos += chunk.size
        }
        s.close()
        assertTrue(ranges.toString(), ranges.any { it.startsWith("bytes=$target-") })
        assertTrue(ranges.toString(), ranges.any { it.startsWith("bytes=${target + StreamSession.BLOCK}-") })
    }

    @Test
    fun `a cached file plays without the network`() {
        session().apply { waitUntil { isComplete }; close() }
        server.shutdown()
        val again = session()
        assertTrue(again.isComplete)
        assertArrayEquals(expected(1000, 5000), again.readAll(1000, 5000))
        again.close()
    }

    @Test
    fun `dropped connections are retried`() {
        failNext.set(2)
        val s = session()
        assertArrayEquals(expected(0, 1000), s.readAll(0, 1000))
        s.close()
    }

    @Test
    fun `a dead server fails the read instead of hanging`() {
        server.shutdown()
        val s = session()
        try {
            s.readAll(5000, 100)
            fail("read should fail")
        } catch (e: IOException) {
            // expected
        }
        s.close()
        assertFalse(s.isComplete)
    }

    private fun waitUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out")
            Thread.sleep(20)
        }
    }
}
