package dev.subsonic.poweramp.scrobble

import dev.subsonic.poweramp.provider.DocId
import java.net.URLDecoder

/** Recognises our songs in the "path" of Poweramp's track broadcasts. */
object TrackPaths {

    /**
     * Returns our song id, or null if the path belongs to another source. Poweramp reports our tracks
     * as "@authority/lib%3A1/track%3A<id>"; document URIs (raw or URL-encoded) work too. The id is
     * the last path segment that parses as one of our track document ids.
     */
    fun songId(path: String?, authority: String): String? {
        if (path.isNullOrEmpty()) return null
        val at = path.indexOf(authority)
        if (at < 0) return null
        val decoded = decode(path.substring(at + authority.length))
        return decoded.split('/').asReversed().firstNotNullOfOrNull { segment ->
            (DocId.parse(segment) as? DocId.Track)?.trackId
        }
    }

    /** The path without query string or fragment, for logging. */
    fun redacted(path: String?): String? = path?.substringBefore('?')?.substringBefore('#')

    private fun decode(s: String): String = try {
        // Twice: a document URI may be encoded again inside another path.
        URLDecoder.decode(URLDecoder.decode(s.replace("+", "%2B"), "UTF-8").replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
