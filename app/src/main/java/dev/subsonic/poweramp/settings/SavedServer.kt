package dev.subsonic.poweramp.settings

import dev.subsonic.poweramp.subsonic.SubsonicAuth
import okhttp3.HttpUrl
import java.security.MessageDigest

/** The configured server. Kept free of Android imports so sync code can be tested on the JVM. */
data class SavedServer(val baseUrl: HttpUrl, val auth: SubsonicAuth) {

    /**
     * Stable fingerprint of "which library is this": server address plus account.
     * The synced library is wiped whenever it changes. Contains no secrets (API keys are hashed).
     */
    val identity: String
        get() {
            val account = when (auth) {
                is SubsonicAuth.Password -> "user:${auth.username}"
                is SubsonicAuth.ApiKey -> "key:${sha256Hex(auth.key)}"
            }
            return sha256Hex("$baseUrl\n$account")
        }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
