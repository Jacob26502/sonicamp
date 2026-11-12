package dev.subsonic.poweramp.stream

/**
 * Sorted, non-overlapping byte ranges [start, end) that have been downloaded. Not thread-safe; the
 * owner ([SparseFile]) synchronises.
 */
class RangeSet {
    private val starts = ArrayList<Long>()
    private val ends = ArrayList<Long>()

    val isEmpty get() = starts.isEmpty()

    /** Total bytes covered. */
    val coveredBytes: Long get() = starts.indices.sumOf { ends[it] - starts[it] }

    fun add(start: Long, end: Long) {
        if (end <= start) return
        var s = start
        var e = end
        // Merge with every range that overlaps or touches [s, e).
        var i = 0
        while (i < starts.size) {
            if (ends[i] < s || starts[i] > e) {
                i++
                continue
            }
            s = minOf(s, starts[i])
            e = maxOf(e, ends[i])
            starts.removeAt(i)
            ends.removeAt(i)
        }
        val at = starts.indexOfFirst { it > s }.let { if (it < 0) starts.size else it }
        starts.add(at, s)
        ends.add(at, e)
    }

    /** End of the covered run containing [offset], or [offset] itself if it isn't covered. */
    fun coveredEnd(offset: Long): Long {
        for (i in starts.indices) {
            if (offset >= starts[i] && offset < ends[i]) return ends[i]
            if (starts[i] > offset) break
        }
        return offset
    }

    /** Start of the first covered range after [offset] (where a download from there can stop), or null. */
    fun nextCoveredStart(offset: Long): Long? = starts.firstOrNull { it > offset }

    /** First byte at or after [offset] that isn't covered. */
    fun firstGap(offset: Long): Long {
        var pos = offset
        while (true) {
            val end = coveredEnd(pos)
            if (end == pos) return pos
            pos = end
        }
    }

    fun toList(): List<Long> = starts.indices.flatMap { listOf(starts[it], ends[it]) }

    companion object {
        fun fromList(flat: List<Long>): RangeSet = RangeSet().apply {
            flat.chunked(2).forEach { if (it.size == 2) add(it[0], it[1]) }
        }
    }
}
