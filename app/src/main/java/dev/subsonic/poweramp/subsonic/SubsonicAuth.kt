package dev.subsonic.poweramp.subsonic

import dev.subsonic.poweramp.server.ServerAuth
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * How Subsonic requests carry [ServerAuth] credentials.
 *
 * - [ServerAuth.Password] uses Subsonic token auth (API 1.13+): every request sends `u`, a fresh
 *   random salt `s`, and `t = md5(password + salt)`. The password itself never goes over the wire,
 *   but it must be kept on the device to compute new tokens.
 * - [ServerAuth.ApiKey] uses the OpenSubsonic `apiKeyAuthentication` extension: only `apiKey` is
 *   sent, and the spec forbids also sending `u` (error 43). Not supported by Navidrome 0.64.
 */
object SubsonicAuth {

    /** Query parameters to add to a request. [salt] only matters for passwords. */
    fun queryParams(auth: ServerAuth, salt: String = randomSalt()): List<Pair<String, String>> = when (auth) {
        is ServerAuth.Password -> listOf(
            "u" to auth.username,
            "t" to md5Hex(auth.password + salt),
            "s" to salt,
        )
        is ServerAuth.ApiKey -> listOf("apiKey" to auth.key)
    }

    private val random = SecureRandom()

    /** 16 hex chars; the spec asks for at least 6. */
    fun randomSalt(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
