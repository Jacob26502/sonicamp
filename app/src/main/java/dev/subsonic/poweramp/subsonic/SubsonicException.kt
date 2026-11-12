package dev.subsonic.poweramp.subsonic

import java.io.IOException

/** The server answered with `status: "failed"`. [code] is the Subsonic/OpenSubsonic error code. */
class SubsonicApiException(
    val code: Int,
    message: String?,
    val helpUrl: String? = null,
) : IOException("Subsonic error $code: ${message ?: "(no message)"}") {

    val serverMessage: String? = message

    companion object {
        const val GENERIC = 0
        const val MISSING_PARAMETER = 10
        const val CLIENT_TOO_OLD = 20
        const val SERVER_TOO_OLD = 30
        const val WRONG_CREDENTIALS = 40
        const val TOKEN_AUTH_NOT_SUPPORTED = 41
        const val AUTH_MECHANISM_NOT_SUPPORTED = 42
        const val CONFLICTING_AUTH = 43
        const val INVALID_API_KEY = 44
        const val NOT_AUTHORIZED = 50
        const val NOT_FOUND = 70
    }
}

/** The address answered, but not with a Subsonic response (wrong URL, proxy login page, etc.). */
class UnexpectedResponseException(message: String) : IOException(message)
