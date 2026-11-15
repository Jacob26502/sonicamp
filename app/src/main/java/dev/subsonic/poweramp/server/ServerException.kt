package dev.subsonic.poweramp.server

import java.io.IOException

/**
 * The server answered, but refused or failed the request. Implementations map their own error
 * codes onto [kind], so the rest of the app (sync, scrobbling, error messages) never needs to know
 * which server type it talks to.
 *
 * @param hint server-specific advice for the user, shown after the generic message
 * @param serverMessage the server's own wording, if any
 */
open class ServerException(
    val kind: Kind,
    val serverMessage: String? = null,
    val hint: String? = null,
    val helpUrl: String? = null,
    debugMessage: String = "${kind.name}: ${serverMessage ?: "(no message)"}",
) : IOException(debugMessage) {

    enum class Kind {
        WRONG_CREDENTIALS,
        /** The server doesn't accept this way of logging in (e.g. password vs API key). */
        LOGIN_METHOD_NOT_SUPPORTED,
        INVALID_API_KEY,
        /** Logged in, but the account may not use this. */
        NOT_AUTHORIZED,
        NOT_FOUND,
        /** Client and server speak incompatible API versions. */
        VERSION_MISMATCH,
        OTHER;

        /** The login itself is the problem: retrying won't help until the user changes it. */
        val isLoginProblem get() = this in setOf(WRONG_CREDENTIALS, LOGIN_METHOD_NOT_SUPPORTED, INVALID_API_KEY, NOT_AUTHORIZED)
    }
}

/** The address answered, but not as a music server (wrong URL, proxy login page, etc.). */
class UnexpectedResponseException(message: String) : IOException(message)
