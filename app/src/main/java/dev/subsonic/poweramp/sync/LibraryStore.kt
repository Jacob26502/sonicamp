package dev.subsonic.poweramp.sync

/** Changes to tracks found by comparing the server's library with the local copy. */
data class TrackDiff(
    val inserted: List<TrackRecord>,
    val updated: List<TrackRecord>,
    val deletedIds: Set<String>,
) {
    val isEmpty get() = inserted.isEmpty() && updated.isEmpty() && deletedIds.isEmpty()

    companion object {
        /** [existing] maps track id to its stored [TrackRecord.contentHash]. */
        fun between(existing: Map<String, String>, fetched: Collection<TrackRecord>): TrackDiff {
            val inserted = ArrayList<TrackRecord>()
            val updated = ArrayList<TrackRecord>()
            val seen = HashSet<String>(fetched.size * 2)
            for (track in fetched) {
                seen += track.id
                when (val oldHash = existing[track.id]) {
                    null -> inserted += track
                    track.contentHash -> Unit
                    else -> updated += track
                }
            }
            return TrackDiff(inserted, updated, existing.keys - seen)
        }
    }
}

data class PlaylistDiff(
    /** New or changed playlists, with their full track lists. */
    val upserts: List<PlaylistRecord>,
    val deletedIds: Set<String>,
) {
    val isEmpty get() = upserts.isEmpty() && deletedIds.isEmpty()
}

data class LibraryUpdate(
    val identity: String,
    val libraries: List<LibraryRecord>,
    val tracks: TrackDiff,
    val playlists: PlaylistDiff,
    /** Becomes the last-modified time of every inserted/updated row (what Poweramp compares). */
    val syncedAt: Long,
)

/** Local copy of the library. Implemented by the SQLite database; faked in tests. */
interface LibraryStore {
    /** [dev.subsonic.poweramp.settings.SavedServer.identity] of the server this data came from. */
    fun identity(): String?

    /** Track id to content hash. */
    fun trackHashes(): Map<String, String>

    /** Playlist id to the server's `changed` value when it was last fetched. */
    fun playlistVersions(): Map<String, String?>

    /** Applies everything atomically and records [LibraryUpdate.identity]. */
    fun apply(update: LibraryUpdate)

    fun wipe()
}
