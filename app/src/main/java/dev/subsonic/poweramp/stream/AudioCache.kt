package dev.subsonic.poweramp.stream

import java.io.File
import java.security.MessageDigest

/**
 * Directory of [SparseFile]s, one per track version, capped at [maxBytes]: the least recently used
 * files not currently playing are deleted first. Partly downloaded files count by their size on disk.
 */
class AudioCache(private val dir: File, private val maxBytes: () -> Long) {

    /**
     * The cache file for a track. The key includes the server identity and the file size, so a
     * changed file on the server or another account never reuses stale bytes.
     */
    fun fileFor(serverIdentity: String, trackId: String, size: Long, variant: String = "raw"): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$serverIdentity\u0000$trackId\u0000$size\u0000$variant".toByteArray())
        return File(dir, digest.take(16).joinToString("") { "%02x".format(it) } + DATA_SUFFIX)
    }

    /** Marks a file as just used, so it is evicted last. */
    fun touch(file: File) {
        if (file.exists()) file.setLastModified(System.currentTimeMillis())
    }

    /** Deletes least recently used files (never those in [inUse]) until the cache fits. */
    @Synchronized
    fun trim(inUse: Set<File>) {
        val files = dir.listFiles { f -> f.name.endsWith(DATA_SUFFIX) }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        val limit = maxBytes()
        if (total <= limit) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= limit) break
            if (f in inUse) continue
            total -= f.length()
            delete(f)
        }
    }

    fun totalBytes(): Long = dir.listFiles { f -> f.name.endsWith(DATA_SUFFIX) }?.sumOf { it.length() } ?: 0

    data class Stats(val bytes: Long, val tracks: Int, val completeTracks: Int)

    /**
     * Bytes downloaded and how many tracks are complete, from each track's range record (a track
     * that is playing right now counts as of its last save). Reads one small file per track, so call
     * it off the main thread.
     */
    fun stats(): Stats {
        val files = dir.listFiles { f -> f.name.endsWith(DATA_SUFFIX) } ?: return Stats(0, 0, 0)
        // Older records lack the size; a file then is as long as its furthest downloaded byte, which
        // for those (always downloaded to the end) is the track size.
        val records = files.map { SparseFile.readRecord(File(it.path + SparseFile.RANGES_SUFFIX), legacySize = it.length()) }
        return Stats(
            bytes = records.sumOf { it?.ranges?.coveredBytes ?: 0 },
            tracks = files.size,
            completeTracks = records.count { it?.isComplete == true },
        )
    }

    @Synchronized
    fun clear(inUse: Set<File> = emptySet()) {
        dir.listFiles { f -> f.name.endsWith(DATA_SUFFIX) }?.filter { it !in inUse }?.forEach(::delete)
    }

    private fun delete(f: File) {
        f.delete()
        File(f.path + SparseFile.RANGES_SUFFIX).delete()
    }

    companion object {
        const val DATA_SUFFIX = ".audio"
    }
}
