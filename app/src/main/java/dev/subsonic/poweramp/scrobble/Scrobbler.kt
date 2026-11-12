package dev.subsonic.poweramp.scrobble

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.poweramp.PowerampApi
import dev.subsonic.poweramp.provider.SubsonicDocumentsProvider
import dev.subsonic.poweramp.settings.SavedServer
import dev.subsonic.poweramp.subsonic.Extensions
import dev.subsonic.poweramp.subsonic.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Reports what Poweramp plays from our library back to the server:
 * - "now playing" in real time (OpenSubsonic reportPlayback, or classic scrobble with submission=false),
 *   best effort and dropped when offline;
 * - finished plays as scrobbles with the time they started, queued in [ScrobbleQueue] and sent by
 *   [ScrobbleWorker], so plays made offline are sent later.
 *
 * Play counting is always ours ([PlaybackTracker]), and reportPlayback is sent with ignoreScrobble,
 * so a play is never counted twice.
 */
class Scrobbler(private val app: SubsonicApp) {

    private val prefs = app.getSharedPreferences("scrobble", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val queue: ScrobbleQueue by lazy { ScrobbleQueue(app) }

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    /**
     * Updates the playback state from a Poweramp broadcast and returns what to send. Fast and
     * synchronous (called from a receiver); sending happens in [deliver].
     */
    @Synchronized
    fun handle(intent: Intent): List<PlaybackTracker.Output> {
        val state = loadState()
        val ts = intent.getLongExtra(PowerampApi.EXTRA_TIMESTAMP, 0).takeIf { it > 0 } ?: System.currentTimeMillis()
        val result = when (intent.action) {
            PowerampApi.ACTION_TRACK_CHANGED_EXPLICIT -> {
                val track = intent.getBundleExtra(PowerampApi.EXTRA_TRACK)
                val path = track?.getString(PowerampApi.Track.PATH)
                val songId = TrackPaths.songId(path, SubsonicDocumentsProvider.AUTHORITY)
                Log.d(TAG, "Track changed: ${TrackPaths.redacted(path)} -> ${songId ?: "not ours"}")
                val durationMs = track?.getInt(PowerampApi.Track.DURATION_MS, 0)?.toLong()?.takeIf { it > 0 }
                    ?: ((track?.getInt(PowerampApi.Track.DURATION, 0) ?: 0) * 1000L)
                PlaybackTracker.onTrackChanged(state, songId, durationMs, ts)
            }
            PowerampApi.ACTION_STATUS_CHANGED_EXPLICIT -> {
                val playerState = intent.getIntExtra(PowerampApi.EXTRA_STATE, PowerampApi.STATE_NO_STATE)
                if (playerState == PowerampApi.STATE_NO_STATE) return emptyList()
                val paused = intent.getBooleanExtra(PowerampApi.EXTRA_PAUSED, false)
                val position = if (intent.hasExtra(PowerampApi.EXTRA_POSITION)) intent.getIntExtra(PowerampApi.EXTRA_POSITION, 0) else null
                PlaybackTracker.onStatus(
                    state,
                    playing = playerState == PowerampApi.STATE_PLAYING && !paused,
                    stopped = playerState == PowerampApi.STATE_STOPPED,
                    positionSec = position,
                    ts = ts,
                )
            }
            else -> return emptyList()
        }
        saveState(result.state)
        // State is tracked even when disabled, so turning it on mid-track behaves.
        return if (_enabled.value) result.outputs else emptyList()
    }

    /** Queues scrobbles (sent by [ScrobbleWorker]) and sends live reports, best effort. */
    suspend fun deliver(outputs: List<PlaybackTracker.Output>) {
        val server = currentServer() ?: return
        val scrobbles = outputs.filterIsInstance<PlaybackTracker.Output.Scrobble>()
        scrobbles.forEach {
            Log.i(TAG, "Play of ${it.trackId} counts; queued")
            queue.add(server.identity, it.trackId, it.playedAt)
        }
        if (scrobbles.isNotEmpty()) ScrobbleWorker.enqueue(app)

        val client = SubsonicClient(server.baseUrl, server.auth, app.httpClient)
        outputs.filterIsInstance<PlaybackTracker.Output.Report>().forEach { report(server, client, it) }
    }

    private suspend fun report(server: SavedServer, client: SubsonicClient, r: PlaybackTracker.Output.Report) {
        try {
            if (app.serverFeatures.supports(server.baseUrl.toString(), client, Extensions.PLAYBACK_REPORT)) {
                client.reportPlayback(r.trackId, r.state, r.positionMs)
            } else if (r.state == "starting" || r.state == "playing") {
                client.scrobble(r.trackId, playedAtMs = null, submission = false)
            }
            Log.d(TAG, "Reported ${r.state} for ${r.trackId}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Live report skipped: ${e.javaClass.simpleName}")
        }
    }

    /** Forget server: drop queued plays and playback state. */
    @Synchronized
    fun reset() {
        queue.clear()
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun currentServer(): SavedServer? = app.credentialStore.server.value ?: app.credentialStore.load()


    private fun loadState(): PlaybackTracker.State = try {
        prefs.getString(KEY_STATE, null)?.let { json.decodeFromString(PlaybackTracker.State.serializer(), it) }
    } catch (e: Exception) {
        null
    } ?: PlaybackTracker.State()

    private fun saveState(s: PlaybackTracker.State) {
        prefs.edit().putString(KEY_STATE, json.encodeToString(PlaybackTracker.State.serializer(), s)).apply()
    }

    private companion object {
        const val TAG = "Scrobbler"
        const val KEY_ENABLED = "enabled"
        const val KEY_STATE = "tracker_state"
    }
}
