package dev.subsonic.poweramp.scrobble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrobbleQueueTest {

    private val queue = ScrobbleQueue(InstrumentationRegistry.getInstrumentation().targetContext, name = null)

    @After
    fun tearDown() = queue.close()

    @Test
    fun pendingIsInListeningOrder() {
        queue.add("srv", "b", 2_000)
        queue.add("srv", "a", 1_000)
        assertEquals(listOf("a", "b"), queue.pending().map { it.songId })
    }

    @Test
    fun attemptsRemoveAndOtherServers() {
        queue.add("srv", "a", 1)
        queue.add("old", "b", 2)
        val a = queue.pending().first { it.songId == "a" }
        queue.markAttempt(a.id)
        assertEquals(1, queue.pending().first { it.songId == "a" }.attempts)

        queue.removeOtherServers("srv")
        assertEquals(listOf("a"), queue.pending().map { it.songId })
        queue.remove(a.id)
        assertEquals(0, queue.count())
    }
}
