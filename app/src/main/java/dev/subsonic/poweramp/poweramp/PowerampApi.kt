package dev.subsonic.poweramp.poweramp

/**
 * The few Poweramp API constants we use, copied from PowerampAPI.java in
 * https://github.com/maxmpz/powerampapi (licence: free use by software that communicates with
 * Poweramp). The full file is ~90 KB of constants we don't need, so only these are copied.
 * Provider column/call names come from the vendored com.maxmpz.poweramp.player.TrackProviderConsts.
 */
object PowerampApi {
    const val PACKAGE = "com.maxmpz.audioplayer"

    /** Broadcast receiver that accepts API intents, including scan requests (since build 855). */
    const val API_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver"

    /** Sent explicitly to registered apps when playback status changes; arrives even in the background. */
    const val ACTION_STATUS_CHANGED_EXPLICIT = "com.maxmpz.audioplayer.STATUS_CHANGED_EXPLICIT"

    /** Sent explicitly to registered apps when the track changes (Poweramp caches the app list). Extras: track, ts. */
    const val ACTION_TRACK_CHANGED_EXPLICIT = "com.maxmpz.audioplayer.TRACK_CHANGED_EXPLICIT"

    /** int, one of the STATE_* values. */
    const val EXTRA_STATE = "state"
    const val EXTRA_PAUSED = "paused"
    /** Bundle with [Track] fields. */
    const val EXTRA_TRACK = "track"
    /** int, optional: position in the track, in seconds. */
    const val EXTRA_POSITION = "pos"
    /** long, System.currentTimeMillis() of the event. */
    const val EXTRA_TIMESTAMP = "ts"

    const val STATE_NO_STATE = -1
    const val STATE_STOPPED = 0
    const val STATE_PLAYING = 1
    const val STATE_PAUSED = 2

    object Track {
        const val PATH = "path"
        /** Seconds. */
        const val DURATION = "dur"
        /** Milliseconds (since build 948). */
        const val DURATION_MS = "durMs"
    }

    object Scanner {
        const val ACTION_SCAN_DIRS = "com.maxmpz.audioplayer.ACTION_SCAN_DIRS"

        /** Target path; "@authority/" scans just this provider (since 1027 full-path form). */
        const val EXTRA_PATH = "path"

        /** Legacy target form, still how Poweramp reports provider-targeted scans to CALL_RESCAN. */
        const val EXTRA_PROVIDER = "provider"

        const val EXTRA_FULL_RESCAN = "fullRescan"
        const val EXTRA_ERASE_TAGS = "eraseTags"
    }
}
