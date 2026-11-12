package dev.subsonic.poweramp.subsonic

import kotlinx.serialization.Serializable

/** Fields every `subsonic-response` carries. OpenSubsonic adds type, serverVersion, openSubsonic. */
@Serializable
data class BaseResponse(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val error: SubsonicError? = null,
)

@Serializable
data class SubsonicError(
    val code: Int,
    val message: String? = null,
    val helpUrl: String? = null,
)

@Serializable
data class OpenSubsonicExtension(
    val name: String,
    val versions: List<Int> = emptyList(),
)

/** What a successful connection test learned about the server. */
data class ServerInfo(
    val type: String?,
    val serverVersion: String?,
    val apiVersion: String?,
    val openSubsonic: Boolean,
    val extensions: List<OpenSubsonicExtension>,
) {
    fun supports(extension: String) = extensions.any { it.name == extension }

    val supportsApiKey get() = supports(Extensions.API_KEY_AUTH)

    /** e.g. "Navidrome 0.64.2" or "Subsonic-compatible server". */
    val displayName: String
        get() {
            val name = type?.replaceFirstChar { it.uppercase() } ?: "Subsonic-compatible server"
            return listOfNotNull(name, serverVersion?.substringBefore(' ')).joinToString(" ")
        }
}

object Extensions {
    const val API_KEY_AUTH = "apiKeyAuthentication"
}
