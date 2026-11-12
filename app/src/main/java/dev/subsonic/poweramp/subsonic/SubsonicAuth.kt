package dev.subsonic.poweramp.subsonic

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * How requests authenticate with the server.
 *
 * - [Password] uses Subsonic token auth (API 1.13+): every request sends `u`, a fresh random
 *   salt `s`, and `t = md5(password + salt)`. The password itself never goes over the wire,
 *   but it must be kept on the device to compute new tokens.
 * - [ApiKey] uses the OpenSubsonic `apiKeyAuthentication` extension: only `apiKey` is sent,
 *   and the spec forbids also sending `u` (error 43). Not supported by Navidrome 0.64.
 */
sealed interface SubsonicAuth {

    /** Query parameters to add to a request. [salt] only matters for [Password]. */
    fun queryParams(salt: String = randomSalt()): List<Pair<String, String>>

    class Password(val username: String, val password: String) : SubsonicAuth {
        override fun queryParams(salt: String): List<Pair<String, String>> = listOf(
            "u" to username,
            "t" to md5Hex(password + salt),
            "s" to salt,
        )

        override fun equals(other: Any?) =
            other is Password && other.username == username && other.password == password

        override fun hashCode() = 31 * username.hashCode() + password.hashCode()

        // Never print the password (logs, crash reports).
        override fun toString() = "Password(username=$username, password=***)"
    }

    class ApiKey(val key: String) : SubsonicAuth {
        override fun queryParams(salt: String): List<Pair<String, String>> = listOf("apiKey" to key)

        override fun equals(other: Any?) = other is ApiKey && other.key == key
        override fun hashCode() = key.hashCode()
        override fun toString() = "ApiKey(***)"
    }

    companion object {
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
}
