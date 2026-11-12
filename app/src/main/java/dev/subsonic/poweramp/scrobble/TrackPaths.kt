package dev.subsonic.poweramp.scrobble

import dev.subsonic.poweramp.provider.DocId
import java.net.URLDecoder

/** Recognises our songs in the "path" of Poweramp's track broadcasts. */
object TrackPaths {

    /**
     * Returns our song id, or null if the path belongs to another source. Handles a document URI or
     * a path that embeds our authority and document id (raw or URL-encoded), and the stream URL
     * itself when [streamPrefix] (our server's `.../rest/stream`) is given.
     */
    fun songId(path: String?, authority: String, streamPrefix: String? = null): String? {
        if (path.isNullOrEmpty()) return null
        if (streamPrefix != null && path.startsWith(streamPrefix)) {
            return queryParam(path, "id")
        }
        val at = path.indexOf(authority)
        if (at < 0) return null
        val decoded = decode(path.substring(at + authority.length))
        // The document id is the last path segment that parses as one of ours.
        return decoded.split('/').asReversed().firstNotNullOfOrNull { segment ->
            (DocId.parse(segment) as? DocId.Track)?.trackId
        }
    }

    /** The path without query string or fragment, safe to log (stream URLs carry login tokens). */
    fun redacted(path: String?): String? = path?.substringBefore('?')?.substringBefore('#')

    private fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").split('&').firstNotNullOfOrNull { p ->
            val (k, v) = p.split('=', limit = 2).let { it[0] to it.getOrNull(1) }
            if (decode(k) == name) v?.let(::decode) else null
        }

    private fun decode(s: String): String = try {
        // Twice: Poweramp may store an already-encoded document URI inside another encoded path.
        URLDecoder.decode(URLDecoder.decode(s.replace("+", "%2B"), "UTF-8").replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
