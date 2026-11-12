package dev.subsonic.poweramp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.library.LibraryCounts
import dev.subsonic.poweramp.sync.SyncSettings
import dev.subsonic.poweramp.sync.SyncStatus
import dev.subsonic.poweramp.sync.SyncWorker
import dev.subsonic.poweramp.stream.AudioCache
import dev.subsonic.poweramp.stream.CacheSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class LibraryUiState(
    val configured: Boolean = false,
    val status: SyncStatus = SyncStatus(),
    val settings: SyncSettings = SyncSettings(),
    val counts: LibraryCounts? = null,
    /** The library changed since Poweramp last scanned it. */
    val powerampRescanPending: Boolean = false,
    val scrobbleEnabled: Boolean = true,
)

data class CacheUiState(val settings: CacheSettings, val stats: AudioCache.Stats?)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SubsonicApp

    /** Bumped to re-read Poweramp state after a manual rescan request. */
    private val refresh = MutableStateFlow(0)

    /** Re-read counts whenever the sync status changes (i.e. after every sync or wipe). */
    private val libraryInfo = combine(app.syncStatus.status, refresh) { _, _ -> }
        .map { app.libraryDatabase.counts() to app.powerampRescans.pending }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<LibraryUiState> = combine(
        app.credentialStore.server,
        app.syncStatus.status,
        app.syncSettings.settings,
        libraryInfo,
        app.scrobbler.enabled,
    ) { server, status, settings, (counts, pending), scrobble ->
        LibraryUiState(
            configured = server != null,
            status = status,
            settings = settings,
            counts = counts,
            powerampRescanPending = pending,
            scrobbleEnabled = scrobble,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setScrobbleEnabled(enabled: Boolean) = app.scrobbler.setEnabled(enabled)

    private val cacheRefresh = MutableStateFlow(0)

    /** Re-read every few seconds while shown: the cache fills as Poweramp plays. */
    private val cacheStats = combine(
        cacheRefresh,
        flow { while (true) { emit(Unit); delay(5_000) } },
    ) { _, _ -> app.audioCache.stats() }.flowOn(Dispatchers.IO)

    val cache: StateFlow<CacheUiState> = combine(app.cacheSettings.settings, cacheStats) { settings, stats ->
        CacheUiState(settings, stats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CacheUiState(app.cacheSettings.settings.value, null))

    fun setCacheMaxGb(gb: Int) {
        app.cacheSettings.update { it.copy(maxGb = gb) }
        viewModelScope.launch(Dispatchers.IO) {
            app.streamSessions.trimCache()
            cacheRefresh.update { it + 1 }
        }
    }

    fun setWholeTracksOnMobile(enabled: Boolean) = app.cacheSettings.update { it.copy(wholeTracksOnMobile = enabled) }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            app.streamSessions.clearCache()
            cacheRefresh.update { it + 1 }
        }
    }

    fun syncNow() = SyncWorker.syncNow(app, app.syncSettings.settings.value)

    /** We're in the foreground, so Poweramp will honour this. */
    fun askPowerampToRescan() {
        app.powerampRescans.request()
        refresh.update { it + 1 }
    }

    fun setInterval(hours: Int) = updateSettings { it.copy(intervalHours = hours) }

    fun setWifiOnly(wifiOnly: Boolean) = updateSettings { it.copy(wifiOnly = wifiOnly) }

    private fun updateSettings(transform: (SyncSettings) -> SyncSettings) {
        val settings = app.syncSettings.update(transform)
        if (app.credentialStore.server.value != null) SyncWorker.schedule(app, settings)
    }
}
