package dev.subsonic.poweramp.scrobble

import kotlinx.serialization.Serializable

/**
 * Turns Poweramp's track-changed and status-changed broadcasts into "now playing" reports and
 * scrobbles. Pure logic: the state is persisted between broadcasts by [Scrobbler], since the app
 * process may be started fresh for each one.
 *
 * A play is scrobbled once the track has played for half its length or 4 minutes, whichever is
 * shorter, and tracks under 30 seconds never are (the Last.fm rules, which Navidrome follows too).
 * The scrobble carries the time the track started.
 */
object PlaybackTracker {

    @Serializable
    data class State(
        /** Our song id, or null when Poweramp is playing something that isn't ours. */
        val trackId: String? = null,
        val durationMs: Long = 0,
        val startedAt: Long = 0,
        /** Listening time before the current playing stretch. */
        val playedMs: Long = 0,
        /** Start of the current playing stretch, if playing. */
        val playingSince: Long? = null,
        /** Poweramp's last known play state, whatever the track. */
        val playing: Boolean = false,
        val scrobbled: Boolean = false,
        val startReported: Boolean = false,
    )

    sealed interface Output {
        /** OpenSubsonic reportPlayback states: starting, playing, paused, stopped. */
        data class Report(val trackId: String, val state: String, val positionMs: Long) : Output
        data class Scrobble(val trackId: String, val playedAt: Long) : Output
    }

    data class Result(val state: State, val outputs: List<Output>)

    const val MIN_TRACK_MS = 30_000L
    const val MAX_THRESHOLD_MS = 240_000L

    fun onTrackChanged(s: State, trackId: String?, durationMs: Long, ts: Long): Result {
        // Re-announcement of the track already playing (e.g. Poweramp restarting its service):
        // keep counting. After it was scrobbled, the same id again means a repeat.
        if (trackId != null && trackId == s.trackId && !s.scrobbled) {
            return Result(s.copy(durationMs = durationMs.takeIf { it > 0 } ?: s.durationMs), emptyList())
        }
        val outputs = mutableListOf<Output>()
        finish(s, ts, outputs, reportStop = true)
        val next = State(
            trackId = trackId,
            durationMs = durationMs,
            startedAt = ts,
            playingSince = if (s.playing) ts else null,
            playing = s.playing,
            startReported = s.playing && trackId != null,
        )
        if (trackId != null && s.playing) outputs += Output.Report(trackId, "starting", 0)
        return Result(next, outputs)
    }

    /**
     * @param playing Poweramp is playing (not paused, not stopped).
     * @param stopped Poweramp stopped (end of list) rather than paused.
     * @param positionSec position in the track, when Poweramp sends it.
     */
    fun onStatus(s: State, playing: Boolean, stopped: Boolean, positionSec: Int?, ts: Long): Result {
        val outputs = mutableListOf<Output>()
        val id = s.trackId
        val next = when {
            playing && !s.playing -> {
                val resumed = s.copy(playing = true, playingSince = ts, startReported = s.startReported || id != null)
                if (id != null) {
                    val state = if (s.startReported) "playing" else "starting"
                    outputs += Output.Report(id, state, positionSec?.times(1000L) ?: s.playedMs)
                }
                resumed
            }
            !playing && s.playing -> {
                val paused = finish(s, ts, outputs, reportStop = stopped).copy(playing = false)
                if (id != null && !stopped) {
                    outputs += Output.Report(id, "paused", positionSec?.times(1000L) ?: paused.playedMs)
                }
                paused
            }
            else -> s
        }
        return Result(next, outputs)
    }

    /** Ends the current playing stretch; scrobbles if the play now counts. Leaves [State.playing] alone. */
    private fun finish(s: State, ts: Long, outputs: MutableList<Output>, reportStop: Boolean): State {
        val stretch = s.playingSince?.let { (ts - it).coerceAtLeast(0) } ?: 0
        // A missed pause broadcast (Poweramp killed) mustn't turn days into listening time.
        val cap = if (s.durationMs > 0) s.durationMs else MAX_THRESHOLD_MS
        val played = (s.playedMs + stretch).coerceAtMost(cap)
        var scrobbled = s.scrobbled
        val id = s.trackId
        if (id != null) {
            if (!scrobbled && counts(played, s.durationMs)) {
                outputs += Output.Scrobble(id, s.startedAt)
                scrobbled = true
            }
            if (reportStop && s.startReported) outputs += Output.Report(id, "stopped", played)
        }
        return s.copy(
            playedMs = played,
            playingSince = null,
            scrobbled = scrobbled,
            startReported = s.startReported && !reportStop,
        )
    }

    fun counts(playedMs: Long, durationMs: Long): Boolean {
        if (durationMs in 1 until MIN_TRACK_MS) return false
        val threshold = if (durationMs > 0) minOf(durationMs / 2, MAX_THRESHOLD_MS) else MAX_THRESHOLD_MS
        return playedMs >= threshold
    }
}
