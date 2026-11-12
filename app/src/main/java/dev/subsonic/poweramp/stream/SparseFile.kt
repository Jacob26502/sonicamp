package dev.subsonic.poweramp.stream

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * A local copy of a remote file that fills in as parts are downloaded, in any order. Which byte
 * ranges are present is kept in a ".ranges" file beside it, so a partly played track resumes from
 * what's already there and a fully downloaded one plays without the network.
 */
class SparseFile(val data: File, val size: Long) : Closeable {

    private val rangesFile = File(data.path + RANGES_SUFFIX)
    private val raf: RandomAccessFile
    private val ranges: RangeSet

    init {
        data.parentFile?.mkdirs()
        raf = RandomAccessFile(data, "rw")
        ranges = loadRanges()
    }

    private fun loadRanges(): RangeSet {
        if (!rangesFile.exists() || raf.length() == 0L) return RangeSet()
        return try {
            val record = readRecord(rangesFile, legacySize = size) ?: return RangeSet()
            if (record.size != size) return RangeSet()
            val set = record.ranges
            // Never trust ranges beyond what's actually on disk (e.g. after a crash mid-write).
            if (set.toList().all { it <= raf.length() && it <= size }) set else RangeSet()
        } catch (e: Exception) {
            RangeSet()
        }
    }

    @Synchronized
    fun write(offset: Long, buf: ByteArray, off: Int, len: Int) {
        if (len <= 0 || offset >= size) return
        val n = minOf(len.toLong(), size - offset).toInt()
        raf.seek(offset)
        raf.write(buf, off, n)
        ranges.add(offset, offset + n)
    }

    /** Copies what's present from [offset] (up to [len] bytes); returns 0 if [offset] isn't downloaded yet. */
    @Synchronized
    fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        val end = ranges.coveredEnd(offset)
        if (end <= offset) return 0
        val n = minOf(len.toLong(), end - offset).toInt()
        raf.seek(offset)
        raf.readFully(buf, off, n)
        return n
    }

    @Synchronized fun firstGap(offset: Long): Long = ranges.firstGap(offset).coerceAtMost(size)

    /** Where a download starting at [offset] can stop, because the bytes after are already present. */
    @Synchronized fun downloadEnd(offset: Long): Long = (ranges.nextCoveredStart(offset) ?: size).coerceAtMost(size)

    @Synchronized fun isCovered(offset: Long): Boolean = ranges.coveredEnd(offset) > offset

    val isComplete: Boolean @Synchronized get() = ranges.coveredEnd(0) >= size

    val downloadedBytes: Long @Synchronized get() = ranges.coveredBytes

    @Synchronized
    fun saveRanges() {
        try {
            val tmp = File(rangesFile.path + ".tmp")
            tmp.writeText("$size\n" + ranges.toList().joinToString(","))
            if (!tmp.renameTo(rangesFile)) tmp.delete()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    override fun close() {
        saveRanges()
        raf.close()
    }

    /** Contents of a ".ranges" file: the full file size, then the downloaded ranges. */
    class Record(val size: Long, val ranges: RangeSet) {
        val isComplete get() = ranges.coveredEnd(0) >= size
    }

    companion object {
        const val RANGES_SUFFIX = ".ranges"

        /**
         * @param legacySize the size to assume for records written before the size was stored (a
         *   single line of ranges); without it such records are unreadable.
         */
        fun readRecord(rangesFile: File, legacySize: Long? = null): Record? = try {
            val lines = rangesFile.readText().lines()
            fun parse(line: String?) = line.orEmpty().split(',').filter { it.isNotBlank() }.map { it.trim().toLong() }
            if (lines.size == 1 && lines[0].contains(',')) {
                legacySize?.let { Record(it, RangeSet.fromList(parse(lines[0]))) }
            } else {
                Record(lines[0].trim().toLong(), RangeSet.fromList(parse(lines.getOrNull(1))))
            }
        } catch (e: Exception) {
            null
        }
    }
}
