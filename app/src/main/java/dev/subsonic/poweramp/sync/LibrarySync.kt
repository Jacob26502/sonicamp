package dev.subsonic.poweramp.sync

import dev.subsonic.poweramp.server.MusicServer
import dev.subsonic.poweramp.settings.SavedServer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** The server is in the middle of scanning; its data may be incomplete. Try again later. */
class ServerBusyException : IOException("The server is scanning its library. Will retry later.")

/** The server returned an empty library although we had tracks. Refuse rather than wipe everything. */
class SuspiciousEmptyLibraryException :
    IOException("The server returned no songs. Not removing your library in case this is a server problem.")

data class SyncResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val playlistsChanged: Int,
    val totalTracks: Int,
    /** The previous library belonged to a different server/account and was discarded. */
    val wiped: Boolean,
) {
    val changed get() = wiped || added + updated + removed + playlistsChanged > 0

    fun summary(): String =
        if (!changed) "No changes ($totalTracks tracks)"
        else "$totalTracks tracks: $added added, $updated updated, $removed removed" +
            if (playlistsChanged > 0) ", $playlistsChanged playlists updated" else ""
}

/**
 * Full library sync: pages through every track, diffs against [store] by content hash, and applies
 * the result in one transaction. A full fetch is cheap (about one request per 500 tracks) and works on
 * any server; Navidrome, for one, has no reliable per-song "modified" time to sync incrementally.
 */
class LibrarySync(
    private val store: LibraryStore,
    private val serverFor: (SavedServer) -> MusicServer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 500,
) {
    private val mutex = Mutex()

    /** Concurrent calls (scheduled + "sync now") run one after the other, never in parallel. */
    suspend fun sync(server: SavedServer): SyncResult = mutex.withLock {
        val music = serverFor(server)
        if (music.isScanning()) throw ServerBusyException()

        val identity = server.identity
        val wiped = store.identity().let { it != null && it != identity }
        if (wiped) store.wipe()

        val libraries = music.libraries()
        val tracks = fetchAllTracks(music, libraries)
        val existing = store.trackHashes()
        if (tracks.isEmpty() && existing.isNotEmpty()) throw SuspiciousEmptyLibraryException()

        val trackDiff = TrackDiff.between(existing, tracks)
        val playlistDiff = fetchPlaylistChanges(music)

        store.apply(
            LibraryUpdate(
                identity = identity,
                libraries = libraries,
                tracks = trackDiff,
                playlists = playlistDiff,
                syncedAt = clock(),
            )
        )

        SyncResult(
            added = trackDiff.inserted.size,
            updated = trackDiff.updated.size,
            removed = trackDiff.deletedIds.size,
            playlistsChanged = playlistDiff.upserts.size + playlistDiff.deletedIds.size,
            totalTracks = tracks.size,
            wiped = wiped,
        )
    }

    private suspend fun fetchAllTracks(music: MusicServer, libraries: List<LibraryRecord>): Collection<TrackRecord> {
        // Keyed by id: pages can overlap if the library changes during the sync.
        val byId = LinkedHashMap<String, TrackRecord>()
        val sources: List<Int?> = libraries.map { it.id }.ifEmpty { listOf(null) }
        for (libraryId in sources) {
            var offset = 0
            while (true) {
                val page = music.tracks(libraryId, offset, pageSize)
                page.forEach { byId[it.id] = it }
                if (page.size < pageSize) break
                offset += pageSize
            }
        }
        return byId.values
    }

    private suspend fun fetchPlaylistChanges(music: MusicServer): PlaylistDiff {
        val known = store.playlistVersions()
        val current = music.playlists()
        val upserts = current
            .filter { it.id !in known || it.version == null || known[it.id] != it.version }
            .map { music.playlist(it.id) }
        return PlaylistDiff(upserts, known.keys - current.map { it.id }.toSet())
    }
}
