package dev.subsonic.poweramp.provider

import android.os.CancellationSignal
import android.util.Log
import dev.subsonic.poweramp.server.MusicServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Fetches cover art from the server once per (cover id, size bucket) and keeps it on disk, so
 * Poweramp's repeated thumbnail requests don't hit the network. Lives in the app's cache dir:
 * Android may clear it under storage pressure, and it is refetched on demand.
 */
class CoverCache(private val dir: File, private val http: OkHttpClient) {

    /** Returns the cached file, downloading it first if needed. Blocking; call from a binder thread. */
    fun get(server: MusicServer, coverArtId: String, requestedPx: Int, signal: CancellationSignal?): File {
        val size = bucket(requestedPx)
        val file = File(dir, "${safeName(coverArtId)}_$size.img")
        if (file.length() > 0) return file

        dir.mkdirs()
        val call = http.newCall(Request.Builder().url(server.coverUrl(coverArtId, size)).build())
        signal?.setOnCancelListener { call.cancel() }
        try {
            call.execute().use { response ->
                val body = response.body
                val type = response.header("Content-Type").orEmpty()
                if (!response.isSuccessful || body == null || !type.startsWith("image/")) {
                    // Some servers send errors as JSON with HTTP 200; treat anything non-image as "no cover".
                    throw FileNotFoundException("No cover for $coverArtId (HTTP ${response.code}, $type)")
                }
                // Write to a temp file and rename, so a half-written image is never served.
                val tmp = File(dir, "${file.name}.${Thread.currentThread().id}.tmp")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    if (file.length() == 0L) throw IOException("Could not store cover $coverArtId")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cover $coverArtId unavailable: ${e.message}")
            throw if (e is FileNotFoundException) e else FileNotFoundException(e.message)
        } finally {
            signal?.setOnCancelListener(null)
        }
        return file
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "CoverCache"

        /** A few fixed sizes keep the cache small whatever sizes Poweramp asks for. */
        fun bucket(px: Int) = when {
            px <= 0 -> 512
            px <= 256 -> 256
            px <= 512 -> 512
            else -> 1024
        }

        fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
