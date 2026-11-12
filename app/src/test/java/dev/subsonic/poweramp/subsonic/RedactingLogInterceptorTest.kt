package dev.subsonic.poweramp.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactingLogInterceptorTest {

    @Test
    fun `credentials are masked and other parameters kept`() {
        val url = "https://music.example.com/rest/stream.view?u=joe&t=abc&s=salt&p=enc:pw&apiKey=k&id=song1&v=1.16.1".toHttpUrl()

        val logged = RedactingLogInterceptor.redact(url)

        listOf("joe", "abc", "salt", "enc:pw", "=k&").forEach { assertFalse("leaked $it in $logged", it in logged) }
        assertTrue("id=song1" in logged)
        assertTrue("v=1.16.1" in logged)
        assertEquals(7, logged.split('&').size)
    }

    @Test
    fun `url without query is unchanged`() {
        assertEquals("https://example.com/rest/ping.view", RedactingLogInterceptor.redact("https://example.com/rest/ping.view".toHttpUrl()))
    }
}
