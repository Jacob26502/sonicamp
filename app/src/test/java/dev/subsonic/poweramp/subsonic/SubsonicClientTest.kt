package dev.subsonic.poweramp.subsonic

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SubsonicClientTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(auth: SubsonicAuth? = SubsonicAuth.Password("admin", "pw")) =
        SubsonicClient(ServerUrl.parse(server.url("/").toString()), auth, http)

    private fun enqueueJson(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json"))

    @Test
    fun `ping parses an OpenSubsonic response and sends token auth`() = runTest {
        enqueueJson(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
               "serverVersion":"0.64.2 (10114574)","openSubsonic":true}}"""
        )

        val ping = client().ping()

        assertEquals("navidrome", ping.type)
        assertEquals("1.16.1", ping.version)
        assertTrue(ping.openSubsonic)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/ping.view", url.encodedPath)
        assertEquals("admin", url.queryParameter("u"))
        assertEquals("json", url.queryParameter("f"))
        assertNull(url.queryParameter("p"))
        assertEquals(SubsonicAuth.md5Hex("pw" + url.queryParameter("s")), url.queryParameter("t"))
    }

    @Test
    fun `failed status becomes SubsonicApiException with code and helpUrl`() = runTest {
        enqueueJson(
            """{"subsonic-response":{"status":"failed","version":"1.16.1",
               "error":{"code":40,"message":"Wrong username or password","helpUrl":"https://h"}}}"""
        )
        try {
            client().ping()
            fail("expected exception")
        } catch (e: SubsonicApiException) {
            assertEquals(40, e.code)
            assertEquals("https://h", e.helpUrl)
        }
    }

    @Test
    fun `subsonic error inside an HTTP 401 is still parsed`() = runTest {
        enqueueJson("""{"subsonic-response":{"status":"failed","error":{"code":44}}}""", code = 401)
        try {
            client(SubsonicAuth.ApiKey("k")).ping()
            fail("expected exception")
        } catch (e: SubsonicApiException) {
            assertEquals(44, e.code)
        }
    }

    @Test(expected = UnexpectedResponseException::class)
    fun `html page is reported as not a subsonic server`() = runTest {
        server.enqueue(MockResponse().setBody("<html>Login</html>"))
        client().ping()
    }

    @Test
    fun `extensions call is unauthenticated`() = runTest {
        enqueueJson(
            """{"subsonic-response":{"status":"ok","openSubsonic":true,"openSubsonicExtensions":[
               {"name":"transcodeOffset","versions":[1]},{"name":"songLyrics","versions":[1,2]}]}}"""
        )

        val ext = client(auth = null).openSubsonicExtensions()

        assertEquals(listOf("transcodeOffset", "songLyrics"), ext.map { it.name })
        assertEquals(listOf(1, 2), ext[1].versions)
        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("u"))
        assertNull(url.queryParameter("t"))
        assertNull(url.queryParameter("apiKey"))
    }

    @Test
    fun `stream url carries id and auth under a reverse proxy path`() {
        val c = SubsonicClient(ServerUrl.parse("https://example.com/navidrome"), SubsonicAuth.ApiKey("k"), http)
        val url = c.streamUrl("song1", maxBitRateKbps = 320)
        assertEquals("/navidrome/rest/stream.view", url.encodedPath)
        assertEquals("song1", url.queryParameter("id"))
        assertEquals("320", url.queryParameter("maxBitRate"))
        assertEquals("k", url.queryParameter("apiKey"))
        assertFalse(url.queryParameterNames.contains("u"))
    }

    @Test
    fun `connection tester tolerates servers without the extensions endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        enqueueJson("""{"subsonic-response":{"status":"ok","version":"1.15.0"}}""")

        val info = ConnectionTester(http).test(ServerUrl.parse(server.url("/").toString()), SubsonicAuth.Password("a", "b"))

        assertEquals("1.15.0", info.apiVersion)
        assertFalse(info.openSubsonic)
        assertTrue(info.extensions.isEmpty())
        assertFalse(info.supportsApiKey)
    }
}
