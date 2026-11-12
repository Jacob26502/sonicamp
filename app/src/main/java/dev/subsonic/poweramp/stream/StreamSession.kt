package dev.subsonic.poweramp.stream

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Streams one track into a [SparseFile] and serves reads from it. Shared by every descriptor
 * Poweramp opens for the track (it opens each 2–4 times when starting one).
 *
 * - A background downloader reads ahead from the play position with one range request, so normal
 *   playback is a single HTTP response.
 * - A read the downloader isn't about to reach (Poweramp peeking at tags near the end, or the first
 *   read after a seek) fetches just one [BLOCK] directly, leaving the downloader alone. If reading
 *   then carries on past that block, the play position has moved, and the downloader follows.
 * - The downloader stops [readAhead] bytes ahead of the last read (a whole track on Wi-Fi), and
 *   retries with backoff when the connection drops.
 *
 * No Android dependencies, so it is tested on the JVM against a mock server.
 */
class StreamSession(
    val trackId: String,
    private val url: HttpUrl,
    val size: Long,
    private val file: SparseFile,
    private val http: OkHttpClient,
    private val readAhead: () -> Long,
    private val log: (String) -> Unit = {},
    private val readTimeoutMs: Long = 20_000,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // All guarded by [lock].
    private var closed = false
    /** Where the downloader should continue from. */
    private var wantPos = 0L
    private var generation = 0
    /** Where the downloader currently is, or -1 when idle. */
    private var dlPos = -1L
    private var lastReadEnd = 0L
    private var lastBlockEnd = -1L
    private var failure: IOException? = null

    private var requests = 0
    private val thread = Thread(::downloadLoop, "download-$trackId").apply { isDaemon = true }

    init {
        thread.start()
    }

    val isComplete get() = file.isComplete

    /** Fills [data] with [size] bytes from [offset] (fewer only at end of file). Blocks until downloaded. */
    fun read(offset: Long, size: Int, data: ByteArray): Int {
        if (offset >= this.size) return 0
        val wanted = minOf(size.toLong(), this.size - offset).toInt()
        var filled = 0
        val deadline = System.currentTimeMillis() + readTimeoutMs
        if (!file.isCovered(offset)) lock.withLock {
            // The reader is here now; lets a paused downloader judge how far ahead it is.
            if (offset in lastReadEnd..(lastReadEnd + WAIT_WINDOW)) {
                lastReadEnd = offset
                changed.signalAll()
            }
        }
        while (filled < wanted) {
            val pos = offset + filled
            val n = file.read(pos, data, filled, wanted - filled)
            if (n > 0) {
                filled += n
                continue
            }
            fetchMissing(pos, deadline)
        }
        lock.withLock {
            lastReadEnd = offset + filled
            changed.signalAll() // the downloader may be paused waiting for reads to catch up
        }
        return filled
    }

    /** Makes [pos] available or throws. */
    private fun fetchMissing(pos: Long, deadline: Long) {
        val action = lock.withLock {
            if (closed) throw IOException("closed")
            // Downloaded since the caller looked (the downloader publishes under this lock).
            if (file.isCovered(pos)) return
            // Where the downloader is, or where it is about to start.
            val dlFrom = if (dlPos >= 0) dlPos else file.firstGap(wantPos)
            when {
                // The downloader is about to reach it: wait.
                failure == null && pos >= dlFrom && pos - dlFrom <= WAIT_WINDOW -> Action.WAIT
                // Reading continues past a block we fetched directly: the play position moved here.
                pos == lastBlockEnd -> {
                    retargetLocked(pos)
                    Action.WAIT
                }
                else -> Action.FETCH_BLOCK
            }
        }
        when (action) {
            Action.WAIT -> lock.withLock {
                while (!closed && !file.isCovered(pos)) {
                    failure?.let { throw it }
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw IOException("Timed out waiting for $trackId at $pos")
                    changed.await(left, TimeUnit.MILLISECONDS)
                }
            }
            Action.FETCH_BLOCK -> {
                val end = minOf(pos + BLOCK, file.downloadEnd(pos))
                var attempt = 0
                while (true) {
                    try {
                        fetch(file.firstGap(pos), end, stopIf = { false })
                        break
                    } catch (e: IOException) {
                        if (++attempt >= BLOCK_ATTEMPTS || System.currentTimeMillis() > deadline) throw e
                    }
                }
                lock.withLock { lastBlockEnd = end }
            }
        }
    }

    private enum class Action { WAIT, FETCH_BLOCK }

    private fun retargetLocked(pos: Long) {
        log("Seek in $trackId -> $pos")
        wantPos = pos
        generation++
        failure = null
        changed.signalAll()
    }

    private fun downloadLoop() {
        var failures = 0
        while (true) {
            val (start, gen) = lock.withLock {
                while (true) {
                    if (closed) return
                    val start = file.firstGap(wantPos)
                    val ahead = start - lastReadEnd
                    if (start < size && failure == null && ahead <= readAhead()) return@withLock start to generation
                    dlPos = -1
                    changed.await(1, TimeUnit.SECONDS)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            val end = file.downloadEnd(start)
            try {
                fetch(start, end) {
                    lock.withLock { closed || generation != gen || dlPos - lastReadEnd > readAhead() }
                }
                failures = 0
            } catch (e: IOException) {
                if (lock.withLock { closed }) return
                failures++
                log("Download of $trackId at $start failed ($failures): ${e.javaClass.simpleName}")
                if (failures >= MAX_FAILURES) {
                    lock.withLock {
                        failure = e
                        changed.signalAll()
                    }
                    failures = 0
                } else {
                    lock.withLock { changed.await(BACKOFF_MS * failures, TimeUnit.MILLISECONDS) }
                }
            }
        }
    }

    /**
     * Downloads [start, end) into the file, publishing progress as it goes, until done or [stopIf].
     * Used by the downloader and for direct block fetches.
     */
    private fun fetch(start: Long, end: Long, stopIf: () -> Boolean) {
        if (start >= end) return
        val request = Request.Builder().url(url).header("Range", "bytes=$start-${end - 1}").build()
        val call = http.newCall(request)
        lock.withLock { requests++ }
        call.execute().use { r ->
            if (r.code != 206 && !(r.code == 200 && start == 0L)) throw IOException("HTTP ${r.code} for a range request")
            val body = r.body ?: throw IOException("empty body")
            val input = body.byteStream()
            val buf = ByteArray(CHUNK)
            var pos = start
            while (pos < end) {
                val n = input.read(buf, 0, minOf(CHUNK.toLong(), end - pos).toInt())
                if (n < 0) {
                    if (pos < end) throw IOException("Connection closed at $pos of $end")
                    break
                }
                file.write(pos, buf, 0, n)
                pos += n
                lock.withLock {
                    if (Thread.currentThread() === thread) dlPos = pos
                    changed.signalAll()
                }
                if (stopIf()) {
                    call.cancel()
                    break
                }
            }
        }
    }

    fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            changed.signalAll()
        }
        thread.interrupt()
        log("Closed $trackId: $requests requests, ${file.downloadedBytes} of $size bytes cached")
        file.close()
    }

    companion object {
        /** Bytes fetched directly for a read away from the play position (seek target, tag peeks). */
        const val BLOCK = 256 * 1024L
        /** A read this far ahead of the downloader waits for it rather than fetching directly. */
        const val WAIT_WINDOW = 1024 * 1024L
        const val CHUNK = 64 * 1024
        const val MAX_FAILURES = 4
        const val BLOCK_ATTEMPTS = 3
        const val BACKOFF_MS = 1_000L
    }
}
