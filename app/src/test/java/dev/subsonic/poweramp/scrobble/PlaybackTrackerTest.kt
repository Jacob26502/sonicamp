package dev.subsonic.poweramp.scrobble

import dev.subsonic.poweramp.scrobble.PlaybackTracker.Output.Report
import dev.subsonic.poweramp.scrobble.PlaybackTracker.Output.Scrobble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackerTest {

    /** Feeds events in order and collects every output. */
    private class Run {
        var state = PlaybackTracker.State()
        val outputs = mutableListOf<PlaybackTracker.Output>()

        fun track(id: String?, durMs: Long, ts: Long) = apply {
            PlaybackTracker.onTrackChanged(state, id, durMs, ts).let { state = it.state; outputs += it.outputs }
        }

        fun play(ts: Long, pos: Int? = null) = status(true, false, ts, pos)
        fun pause(ts: Long, pos: Int? = null) = status(false, false, ts, pos)
        fun stop(ts: Long) = status(false, true, ts, null)

        private fun status(playing: Boolean, stopped: Boolean, ts: Long, pos: Int?) = apply {
            PlaybackTracker.onStatus(state, playing, stopped, pos, ts).let { state = it.state; outputs += it.outputs }
        }

        val scrobbles get() = outputs.filterIsInstance<Scrobble>()
        val reports get() = outputs.filterIsInstance<Report>().map { "${it.trackId}:${it.state}" }
    }

    @Test
    fun `threshold is half the track or 4 minutes, never under 30 s`() {
        assertTrue(PlaybackTracker.counts(100_000, 200_000))
        assertFalse(PlaybackTracker.counts(99_999, 200_000))
        assertTrue(PlaybackTracker.counts(240_000, 1_000_000))
        assertFalse(PlaybackTracker.counts(29_000, 29_000))
        assertTrue(PlaybackTracker.counts(240_000, 0)) // unknown length
    }

    @Test
    fun `a track played through is scrobbled with its start time when the next starts`() {
        val r = Run().play(0).track("a", 200_000, 1_000).track("b", 180_000, 201_000)
        assertEquals(listOf(Scrobble("a", 1_000)), r.scrobbles)
        assertEquals(listOf("a:starting", "a:stopped", "b:starting"), r.reports)
    }

    @Test
    fun `skipping early is not a play`() {
        val r = Run().play(0).track("a", 200_000, 0).track("b", 200_000, 50_000)
        assertTrue(r.scrobbles.isEmpty())
    }

    @Test
    fun `pauses do not count as listening`() {
        val r = Run().track("a", 200_000, 0).play(0)
            .pause(60_000).play(1_000_000).pause(1_030_000) // 90 s listened
        assertTrue(r.scrobbles.isEmpty())
        r.play(2_000_000).pause(2_010_000) // 100 s
        assertEquals(listOf(Scrobble("a", 0)), r.scrobbles)
        assertEquals(listOf("a:starting", "a:paused", "a:playing", "a:paused", "a:playing", "a:paused"), r.reports)
    }

    @Test
    fun `scrobbled once even with more pauses`() {
        val r = Run().track("a", 100_000, 0).play(0).pause(60_000).play(70_000).track("b", 100_000, 110_000)
        assertEquals(1, r.scrobbles.size)
    }

    @Test
    fun `other apps' tracks produce nothing`() {
        val r = Run().play(0).track(null, 200_000, 0).pause(150_000).play(160_000).track(null, 100_000, 300_000)
        assertTrue(r.outputs.isEmpty())
    }

    @Test
    fun `stop at end of list scrobbles and reports stopped`() {
        val r = Run().track("a", 60_000, 0).play(0).stop(60_000)
        assertEquals(listOf(Scrobble("a", 0)), r.scrobbles)
        assertEquals(listOf("a:starting", "a:stopped"), r.reports)
        r.play(100_000)
        assertEquals("a:starting", r.reports.last())
    }

    @Test
    fun `re-announced track keeps counting, repeat after scrobble is a new play`() {
        val r = Run().play(0).track("a", 100_000, 0).track("a", 100_000, 30_000)
        assertTrue(r.scrobbles.isEmpty())
        r.track("b", 100_000, 100_000)
        assertEquals(listOf(Scrobble("a", 0)), r.scrobbles)

        val repeat = Run().play(0).track("a", 100_000, 0).pause(60_000) // counted here
        repeat.play(60_000).track("a", 100_000, 100_000).track("b", 100_000, 160_000)
        assertEquals(listOf(Scrobble("a", 0), Scrobble("a", 100_000)), repeat.scrobbles)
    }

    @Test
    fun `a missed pause cannot inflate listening time beyond the track`() {
        val r = Run().track("a", 20_000, 0).play(0).track("b", 200_000, 86_400_000)
        assertTrue(r.scrobbles.isEmpty()) // 20 s track: never counted, however long the gap
    }

    @Test
    fun `report positions come from Poweramp when sent`() {
        val r = Run().track("a", 200_000, 0).play(0, pos = 42)
        assertEquals(42_000L, (r.outputs.single() as Report).positionMs)
    }
}
