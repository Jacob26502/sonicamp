package dev.subsonic.poweramp.subsonic

import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Checks a server address and credentials, and collects what the server supports. */
class ConnectionTester(private val http: OkHttpClient) {

    suspend fun test(baseUrl: HttpUrl, auth: SubsonicAuth): ServerInfo {
        // 1. Unauthenticated probe. Old/plain Subsonic servers don't have this endpoint;
        //    treat any API-level failure as "no extensions". Network errors still propagate.
        val extensions = try {
            SubsonicClient(baseUrl, null, http).openSubsonicExtensions()
        } catch (e: SubsonicApiException) {
            emptyList()
        } catch (e: UnexpectedResponseException) {
            emptyList()
        }

        // 2. Authenticated ping. Let the server decide whether the auth method is acceptable
        //    (an API key on a server without the extension fails here with error 42/44).
        val ping = SubsonicClient(baseUrl, auth, http).ping()

        return ServerInfo(
            type = ping.type,
            serverVersion = ping.serverVersion,
            apiVersion = ping.version,
            openSubsonic = ping.openSubsonic,
            extensions = extensions,
        )
    }
}
