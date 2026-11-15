package dev.subsonic.poweramp.server

import dev.subsonic.poweramp.settings.SavedServer
import dev.subsonic.poweramp.subsonic.ServerFeatures
import dev.subsonic.poweramp.subsonic.SubsonicServer
import okhttp3.OkHttpClient

/**
 * Creates the [MusicServer] for a saved configuration. The one place that knows which
 * implementation serves which [ServerType]. Servers are cheap to create; per-server state that
 * should outlive them (Subsonic's extension list) is held here.
 */
class MusicServers(private val http: OkHttpClient) {

    private val subsonicFeatures = ServerFeatures()

    fun create(saved: SavedServer): MusicServer = when (saved.type) {
        ServerType.SUBSONIC -> SubsonicServer(saved.baseUrl, saved.auth, http, subsonicFeatures)
    }

    /** Forget what was learned about servers (after the user changes servers). */
    fun clearCachedCapabilities() = subsonicFeatures.clear()
}
