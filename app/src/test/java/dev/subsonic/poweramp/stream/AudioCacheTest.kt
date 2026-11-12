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
    fun `records say what each file holds, and other versions of a track can be dropped`() {
        val raw = put("t", 4000, complete = true, age = 0)
        val mp3 = cache.fileFor("srv", "t", 1000, "mp3-192")
        SparseFile(mp3, 1000).use { it.write(0, ByteArray(1000), 0, 1000) }
        val other = cache.fileFor("srv", "u", 1000, "mp3-192")
        SparseFile(other, 1000).use { it.write(0, ByteArray(1000), 0, 1000) }
        cache.writeMeta(raw, "srv", "t", "raw", 4000)
        cache.writeMeta(mp3, "srv", "t", "mp3-192", 1000)
        cache.writeMeta(other, "srv", "u", "mp3-192", 1000)

        assertEquals(setOf("t/raw", "t/mp3-192", "u/mp3-192"), cache.entries().map { "${it.trackId}/${it.variant}" }.toSet())
        assertEquals(1, cache.dropOtherVariants("srv", "t", "raw", inUse = emptySet()))
        assertFalse(mp3.exists())
        assertFalse(java.io.File(mp3.path + AudioCache.META_SUFFIX).exists())
        assertTrue(raw.exists())
        assertTrue("another track's copy stays", other.exists())
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
