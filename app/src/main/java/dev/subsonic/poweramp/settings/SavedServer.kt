package dev.subsonic.poweramp.settings

import dev.subsonic.poweramp.server.ServerAuth
import dev.subsonic.poweramp.server.ServerType
import okhttp3.HttpUrl
import java.security.MessageDigest

/** The configured server. Kept free of Android imports so sync code can be tested on the JVM. */
data class SavedServer(
    val baseUrl: HttpUrl,
    val auth: ServerAuth,
    val type: ServerType = ServerType.SUBSONIC,
) {

    /**
     * Stable fingerprint of "which library is this": server type, address and account.
     * The synced library is wiped whenever it changes. Contains no secrets (API keys are hashed).
     * Subsonic keeps its original form (no type prefix) so existing libraries aren't wiped.
     */
    val identity: String
        get() {
            val account = when (auth) {
                is ServerAuth.Password -> "user:${auth.username}"
                is ServerAuth.ApiKey -> "key:${sha256Hex(auth.key)}"
            }
            val prefix = if (type == ServerType.SUBSONIC) "" else "${type.name}\n"
            return sha256Hex("$prefix$baseUrl\n$account")
        }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
