package dev.subsonic.poweramp.subsonic

import dev.subsonic.poweramp.server.ServerException
import dev.subsonic.poweramp.server.ServerException.Kind

/**
 * The server answered with `status: "failed"`. [code] is the Subsonic/OpenSubsonic error code; it is
 * also mapped to a server-neutral [ServerException.Kind] for the rest of the app.
 */
class SubsonicApiException(
    val code: Int,
    message: String?,
    helpUrl: String? = null,
) : ServerException(
    kind = kindOf(code),
    serverMessage = message,
    hint = hintFor(code),
    helpUrl = helpUrl,
    debugMessage = "Subsonic error $code: ${message ?: "(no message)"}",
) {
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

        fun kindOf(code: Int): Kind = when (code) {
            WRONG_CREDENTIALS -> Kind.WRONG_CREDENTIALS
            TOKEN_AUTH_NOT_SUPPORTED, AUTH_MECHANISM_NOT_SUPPORTED -> Kind.LOGIN_METHOD_NOT_SUPPORTED
            INVALID_API_KEY -> Kind.INVALID_API_KEY
            NOT_AUTHORIZED -> Kind.NOT_AUTHORIZED
            NOT_FOUND -> Kind.NOT_FOUND
            CLIENT_TOO_OLD, SERVER_TOO_OLD -> Kind.VERSION_MISMATCH
            else -> Kind.OTHER
        }

        private fun hintFor(code: Int): String? = when (code) {
            TOKEN_AUTH_NOT_SUPPORTED -> "This server doesn't accept password logins from apps. Use an API key instead."
            CONFLICTING_AUTH -> "The server received conflicting login details (app bug)."
            CLIENT_TOO_OLD -> "The server needs a newer Subsonic API version than this app supports."
            SERVER_TOO_OLD -> "The server is too old for this app."
            else -> null
        }
    }
}
