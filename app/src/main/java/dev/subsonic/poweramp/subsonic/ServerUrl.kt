package dev.subsonic.poweramp.subsonic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns what the user typed into a normalised server base URL. */
object ServerUrl {

    /**
     * Accepts `host:port`, full URLs, URLs with a sub-path (reverse proxy), and URLs the user
     * copied with a trailing `/rest`. Returns a URL whose path ends in `/`, with no query or
     * fragment. A missing scheme defaults to https; type `http://` explicitly for plain http.
     *
     * @throws IllegalArgumentException with a user-facing message if the input is unusable.
     */
    fun parse(input: String): HttpUrl {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter the server address" }

        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        // toHttpUrlOrNull only accepts http and https, so ftp:// etc. are rejected here.
        val url = withScheme.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("That doesn't look like a server address")

        val segments = url.encodedPathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.lastOrNull().equals("rest", ignoreCase = true)) segments.removeAt(segments.lastIndex)

        return url.newBuilder()
            .encodedPath("/" + segments.joinToString("") { "$it/" })
            .query(null)
            .fragment(null)
            .build()
    }

    fun isInsecure(url: HttpUrl) = !url.isHttps
}
