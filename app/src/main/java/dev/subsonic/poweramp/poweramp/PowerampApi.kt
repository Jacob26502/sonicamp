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
