package dev.subsonic.poweramp.lyrics

import dev.subsonic.poweramp.subsonic.LyricLine
import dev.subsonic.poweramp.subsonic.StructuredLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFormatTest {

    private fun synced(lang: String?, vararg lines: Pair<Long?, String>, offset: Long = 0) =
        StructuredLyrics(lang = lang, synced = true, offset = offset, line = lines.map { LyricLine(it.first, it.second) })

    private fun plain(lang: String?, vararg lines: String) =
        StructuredLyrics(lang = lang, synced = false, line = lines.map { LyricLine(null, it) })

    @Test
    fun `timestamps are mm ss hundredths`() {
        assertEquals("00:00.00", LyricsFormat.timestamp(0))
        assertEquals("01:02.34", LyricsFormat.timestamp(62_345))
        assertEquals("75:00.00", LyricsFormat.timestamp(4_500_000))
    }

    @Test
    fun `synced lyrics become LRC with the offset applied`() {
        val lrc = LyricsFormat.toLrc(synced("eng", 1_000L to "one", 2_500L to "two", 200L to "early", offset = 500))
        assertEquals("[00:00.50]one\n[00:02.00]two\n[00:00.00]early", lrc)
    }

    @Test
    fun `lines without a time keep the previous time`() {
        assertEquals("[00:01.00]a\n[00:01.00]b", LyricsFormat.toLrc(synced(null, 1_000L to "a", null to "b")))
    }

    @Test
    fun `prefers synced lyrics in the device language, and fills both columns`() {
        val result = LyricsFormat.fromStructured(
            listOf(plain("eng", "plain"), synced("jpn", 0L to "jp"), synced("eng", 0L to "en")),
            language = "en",
        )
        assertEquals("[00:00.00]en", result.synced)
        assertEquals("en", result.plain)
    }

    @Test
    fun `unsynced only gives plain text`() {
        val result = LyricsFormat.fromStructured(listOf(plain("xxx", "a", "b")), language = "en")
        assertNull(result.synced)
        assertEquals("a\nb", result.plain)
    }

    @Test
    fun `blank or missing lyrics are none`() {
        assertTrue(LyricsFormat.fromStructured(emptyList()).isEmpty)
        assertTrue(LyricsFormat.fromStructured(listOf(plain(null, " ", ""))).isEmpty)
        assertTrue(LyricsFormat.fromPlain("  ").isEmpty)
        assertEquals("x", LyricsFormat.fromPlain(" x\n").plain)
    }
}
