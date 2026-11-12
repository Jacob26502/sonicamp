package dev.subsonic.poweramp.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SyncStatus(
    val running: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastSummary: String? = null,
    val lastError: String? = null,
    /** Login was rejected. Scheduled syncs stop until the user saves working credentials. */
    val authFailed: Boolean = false,
)

/** Last sync outcome, persisted so the UI can show it after a restart. [SyncStatus.running] is not persisted. */
class SyncStatusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sync_status", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(read())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private fun read() = SyncStatus(
        lastSuccessAt = prefs.getLong(KEY_SUCCESS_AT, 0).takeIf { it > 0 },
        lastSummary = prefs.getString(KEY_SUMMARY, null),
        lastError = prefs.getString(KEY_ERROR, null),
        authFailed = prefs.getBoolean(KEY_AUTH_FAILED, false),
    )

    fun markRunning() = _status.update { it.copy(running = true) }

    @Synchronized
    fun markSuccess(at: Long, summary: String) {
        prefs.edit()
            .putLong(KEY_SUCCESS_AT, at)
            .putString(KEY_SUMMARY, summary)
            .remove(KEY_ERROR)
            .putBoolean(KEY_AUTH_FAILED, false)
            .apply()
        _status.value = read()
    }

    @Synchronized
    fun markError(message: String, authFailed: Boolean) {
        prefs.edit().putString(KEY_ERROR, message).putBoolean(KEY_AUTH_FAILED, authFailed).apply()
        _status.value = read()
    }

    /** New credentials were saved: let scheduled syncs run again. */
    @Synchronized
    fun clearAuthFailure() {
        prefs.edit().putBoolean(KEY_AUTH_FAILED, false).remove(KEY_ERROR).apply()
        _status.update { read().copy(running = it.running) }
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _status.value = SyncStatus()
    }

    private companion object {
        const val KEY_SUCCESS_AT = "last_success_at"
        const val KEY_SUMMARY = "last_summary"
        const val KEY_ERROR = "last_error"
        const val KEY_AUTH_FAILED = "auth_failed"
    }
}

data class SyncSettings(
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
    val wifiOnly: Boolean = false,
) {
    companion object {
        const val DEFAULT_INTERVAL_HOURS = 12
        val INTERVAL_CHOICES = listOf(6, 12, 24)
    }
}

class SyncSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        SyncSettings(
            intervalHours = prefs.getInt(KEY_INTERVAL, SyncSettings.DEFAULT_INTERVAL_HOURS),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
        )
    )
    val settings: StateFlow<SyncSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (SyncSettings) -> SyncSettings): SyncSettings {
        val new = transform(_settings.value)
        prefs.edit().putInt(KEY_INTERVAL, new.intervalHours).putBoolean(KEY_WIFI_ONLY, new.wifiOnly).apply()
        _settings.value = new
        return new
    }

    private companion object {
        const val KEY_INTERVAL = "interval_hours"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
