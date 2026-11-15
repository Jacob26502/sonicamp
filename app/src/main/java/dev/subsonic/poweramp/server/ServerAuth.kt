package dev.subsonic.poweramp.server

/**
 * Credentials the user enters. Each [MusicServer] implementation decides how to send them
 * (Subsonic: token or apiKey query parameters).
 */
sealed interface ServerAuth {

    class Password(val username: String, val password: String) : ServerAuth {
        override fun equals(other: Any?) =
            other is Password && other.username == username && other.password == password

        override fun hashCode() = 31 * username.hashCode() + password.hashCode()

        // Never print the password (logs, crash reports).
        override fun toString() = "Password(username=$username, password=***)"
    }

    class ApiKey(val key: String) : ServerAuth {
        override fun equals(other: Any?) = other is ApiKey && other.key == key
        override fun hashCode() = key.hashCode()
        override fun toString() = "ApiKey(***)"
    }
}

/** Which kind of server a saved configuration talks to. Stored, so values must not be renamed. */
enum class ServerType {
    /** Subsonic and OpenSubsonic servers (Navidrome, Airsonic, Gonic, ...). */
    SUBSONIC,
}
