package dev.subsonic.poweramp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.library.LibraryCounts
import dev.subsonic.poweramp.sync.SyncSettings
import dev.subsonic.poweramp.sync.SyncStatus
import dev.subsonic.poweramp.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class LibraryUiState(
    val configured: Boolean = false,
    val status: SyncStatus = SyncStatus(),
    val settings: SyncSettings = SyncSettings(),
    val counts: LibraryCounts? = null,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SubsonicApp

    /** Re-read counts whenever the sync status changes (i.e. after every sync or wipe). */
    private val counts = app.syncStatus.status
        .map { app.libraryDatabase.counts() }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<LibraryUiState> = combine(
        app.credentialStore.server,
        app.syncStatus.status,
        app.syncSettings.settings,
        counts,
    ) { server, status, settings, counts ->
        LibraryUiState(configured = server != null, status = status, settings = settings, counts = counts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun syncNow() = SyncWorker.syncNow(app, app.syncSettings.settings.value)

    fun setInterval(hours: Int) = updateSettings { it.copy(intervalHours = hours) }

    fun setWifiOnly(wifiOnly: Boolean) = updateSettings { it.copy(wifiOnly = wifiOnly) }

    private fun updateSettings(transform: (SyncSettings) -> SyncSettings) {
        val settings = app.syncSettings.update(transform)
        if (app.credentialStore.server.value != null) SyncWorker.schedule(app, settings)
    }
}
