package dev.subsonic.poweramp.subsonic

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real server only when these environment variables are set
 * (otherwise it is skipped):
 *   SUBSONIC_TEST_URL       e.g. http://localhost:4533
 *   SUBSONIC_TEST_USER
 *   SUBSONIC_TEST_PASSWORD
 */
class LiveServerTest {

    private val url = System.getenv("SUBSONIC_TEST_URL")
    private val user = System.getenv("SUBSONIC_TEST_USER")
    private val password = System.getenv("SUBSONIC_TEST_PASSWORD")

    @Test
    fun `connection test succeeds against live server`() = runTest {
        assumeTrue("SUBSONIC_TEST_* not set; skipping live test", url != null && user != null && password != null)

        val info = ConnectionTester(OkHttpClient()).test(ServerUrl.parse(url!!), SubsonicAuth.Password(user!!, password!!))
        println("Live server: ${info.displayName}, API ${info.apiVersion}, extensions=${info.extensions.map { it.name }}")
        assertEquals(SubsonicClient.API_VERSION, info.apiVersion)
    }

    @Test
    fun `wrong password is rejected with code 40`() = runTest {
        assumeTrue(url != null && user != null)
        try {
            SubsonicClient(ServerUrl.parse(url!!), SubsonicAuth.Password(user!!, "definitely-wrong"), OkHttpClient()).ping()
            throw AssertionError("expected rejection")
        } catch (e: SubsonicApiException) {
            assertEquals(SubsonicApiException.WRONG_CREDENTIALS, e.code)
        }
    }
}
