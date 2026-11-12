package dev.subsonic.poweramp.sync

/** Test double for [LibraryStore] with the same semantics as the SQLite implementation. */
class InMemoryLibraryStore : LibraryStore {
    var identity: String? = null
    val tracks = LinkedHashMap<String, TrackRecord>()
    val trackModified = HashMap<String, Long>()
    val playlists = LinkedHashMap<String, PlaylistRecord>()
    var libraries: List<LibraryRecord> = emptyList()
    var applyCount = 0
    var wipeCount = 0

    override fun identity() = identity
    override fun trackHashes() = tracks.mapValues { it.value.contentHash }
    override fun playlistVersions() = playlists.mapValues { it.value.changed }

    override fun apply(update: LibraryUpdate) {
        applyCount++
        identity = update.identity
        libraries = update.libraries
        (update.tracks.inserted + update.tracks.updated).forEach {
            tracks[it.id] = it
            trackModified[it.id] = update.syncedAt
        }
        update.tracks.deletedIds.forEach { tracks.remove(it); trackModified.remove(it) }
        update.playlists.deletedIds.forEach { playlists.remove(it) }
        update.playlists.upserts.forEach { playlists[it.id] = it }
    }

    override fun wipe() {
        wipeCount++
        identity = null
        tracks.clear()
        trackModified.clear()
        playlists.clear()
        libraries = emptyList()
    }
}
