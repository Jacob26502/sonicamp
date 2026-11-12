package dev.subsonic.poweramp.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicAuthTest {

    @Test
    fun `token matches the example in the Subsonic API docs`() {
        // subsonic.org/pages/api.jsp: password "sesame", salt "c19b2d"
        val params = SubsonicAuth.Password("joe", "sesame").queryParams(salt = "c19b2d").toMap()
        assertEquals("joe", params["u"])
        assertEquals("c19b2d", params["s"])
        assertEquals("26719a1196d2a940705a59634eb18eab", params["t"])
        assertFalse("password must never be sent", params.containsKey("p"))
    }

    @Test
    fun `api key auth sends only apiKey`() {
        val params = SubsonicAuth.ApiKey("abc123").queryParams().toMap()
        assertEquals(mapOf("apiKey" to "abc123"), params)
    }

    @Test
    fun `salts are random and long enough`() {
        val a = SubsonicAuth.randomSalt()
        val b = SubsonicAuth.randomSalt()
        assertNotEquals(a, b)
        assertTrue(a.length >= 6)
    }

    @Test
    fun `toString hides secrets`() {
        assertFalse("sesame" in SubsonicAuth.Password("joe", "sesame").toString())
        assertFalse("abc123" in SubsonicAuth.ApiKey("abc123").toString())
    }
}
