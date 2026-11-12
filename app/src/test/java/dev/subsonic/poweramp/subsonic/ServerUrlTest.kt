package dev.subsonic.poweramp.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    private fun parsed(input: String) = ServerUrl.parse(input).toString()

    @Test
    fun `bare host defaults to https`() {
        assertEquals("https://music.example.com/", parsed("music.example.com"))
    }

    @Test
    fun `explicit http with port is kept`() {
        assertEquals("http://10.0.2.2:4533/", parsed("http://10.0.2.2:4533"))
        assertTrue(ServerUrl.isInsecure(ServerUrl.parse("http://10.0.2.2:4533")))
        assertFalse(ServerUrl.isInsecure(ServerUrl.parse("music.example.com")))
    }

    @Test
    fun `reverse proxy sub-path is kept and trailing rest is stripped`() {
        assertEquals("https://example.com/navidrome/", parsed("https://example.com/navidrome"))
        assertEquals("https://example.com/navidrome/", parsed("https://example.com/navidrome/rest/"))
        assertEquals("https://example.com/", parsed("  https://example.com/rest?u=x#frag "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank input is rejected`() {
        ServerUrl.parse("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non http scheme is rejected`() {
        ServerUrl.parse("ftp://example.com")
    }
}
