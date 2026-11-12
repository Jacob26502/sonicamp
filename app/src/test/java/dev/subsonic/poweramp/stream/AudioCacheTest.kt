package dev.subsonic.poweramp.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private var limit = 10_000L
    private val cache by lazy { AudioCache(tmp.root) { limit } }

    private fun put(id: String, bytes: Int, complete: Boolean, age: Long): java.io.File {
        val f = cache.fileFor("srv", id, bytes.toLong())
        SparseFile(f, bytes.toLong()).use { s ->
            val data = ByteArray(bytes)
            if (complete) s.write(0, data, 0, bytes) else s.write(0, data, 0, bytes / 2)
        }
        f.setLastModified(System.currentTimeMillis() - age)
        return f
    }

    @Test
    fun `keys separate servers, sizes and variants`() {
        val a = cache.fileFor("srv", "t", 100)
        assertEquals(a, cache.fileFor("srv", "t", 100))
        assertNotEquals(a, cache.fileFor("other", "t", 100))
        assertNotEquals(a, cache.fileFor("srv", "t", 101))
        assertNotEquals(a, cache.fileFor("srv", "t", 100, "mp3-192"))
    }

    @Test
    fun `stats count fully downloaded tracks`() {
        put("a", 4000, complete = true, age = 0)
        put("b", 4000, complete = false, age = 0)
        val s = cache.stats()
        assertEquals(2, s.tracks)
        assertEquals(1, s.completeTracks)
        assertEquals(6000, s.bytes) // the partial one is 2000 bytes on disk
    }

    @Test
    fun `records from before the size was stored are still read`() {
        val f = put("old", 4000, complete = true, age = 0)
        java.io.File(f.path + SparseFile.RANGES_SUFFIX).writeText("0,4000") // 5b format
        assertEquals(1, cache.stats().completeTracks)
        SparseFile(f, 4000).use { assertTrue("cached bytes kept, no re-download", it.isComplete) }
    }

    @Test
    fun `trim removes least recently used, never files in use`() {
        val oldest = put("old", 4000, true, age = 30_000)
        val playing = put("playing", 4000, true, age = 20_000)
        val newest = put("new", 4000, true, age = 0)
        cache.trim(inUse = setOf(playing))
        assertFalse(oldest.exists())
        assertFalse(java.io.File(oldest.path + SparseFile.RANGES_SUFFIX).exists())
        assertTrue(playing.exists())
        assertTrue(newest.exists())

        limit = 0
        cache.clear(inUse = setOf(playing))
        assertTrue(playing.exists())
        assertFalse(newest.exists())
    }
}
