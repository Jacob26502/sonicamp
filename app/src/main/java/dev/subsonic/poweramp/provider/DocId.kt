package dev.subsonic.poweramp.provider

/**
 * Document IDs exposed to Poweramp. They embed Navidrome IDs, so they stay stable across syncs
 * (Poweramp keys play counts, playlists and queue entries on them).
 *
 * Hierarchy: Library root → album artist → album → track. Albums without an album artist and
 * tracks without an album sit directly under the library root.
 */
sealed interface DocId {
    val encoded: String

    data class Library(val libraryId: Int) : DocId {
        override val encoded get() = "lib:$libraryId"
    }

    data class Artist(val libraryId: Int, val artistId: String) : DocId {
        override val encoded get() = "artist:$libraryId:$artistId"
    }

    data class Album(val albumId: String) : DocId {
        override val encoded get() = "album:$albumId"
    }

    data class Track(val trackId: String) : DocId {
        override val encoded get() = "track:$trackId"
    }

    /** Folder holding one .m3u8 per server playlist; lives under the first library's root. */
    data object Playlists : DocId {
        override val encoded get() = "playlists"
    }

    /** Ends in .m3u8 like the display name, in case a client goes by the id. */
    data class Playlist(val playlistId: String) : DocId {
        override val encoded get() = "playlist:$playlistId$PLAYLIST_EXT"
    }

    companion object {
        const val PLAYLIST_EXT = ".m3u8"

        /** Returns null for anything that isn't one of ours. */
        fun parse(encoded: String): DocId? {
            if (encoded == Playlists.encoded) return Playlists
            val kind = encoded.substringBefore(':', missingDelimiterValue = "")
            val rest = encoded.substringAfter(':', missingDelimiterValue = "")
            if (rest.isEmpty()) return null
            return when (kind) {
                "lib" -> rest.toIntOrNull()?.let { Library(it) }
                "artist" -> {
                    // Server ids may themselves contain ':', so only the first separator is ours.
                    val lib = rest.substringBefore(':').toIntOrNull()
                    val id = rest.substringAfter(':', missingDelimiterValue = "")
                    if (lib == null || id.isEmpty()) null else Artist(lib, id)
                }
                "album" -> Album(rest)
                "track" -> Track(rest)
                "playlist" -> rest.removeSuffix(PLAYLIST_EXT).takeIf { it.isNotEmpty() && rest.endsWith(PLAYLIST_EXT) }?.let(::Playlist)
                else -> null
            }
        }
    }
}
