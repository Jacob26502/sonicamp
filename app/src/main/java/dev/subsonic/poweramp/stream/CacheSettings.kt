package dev.subsonic.poweramp.stream

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CacheSettings(
    val maxGb: Int = DEFAULT_MAX_GB,
    /** Download whole tracks on mobile data too (otherwise only 8 MB ahead of the play position). */
    val wholeTracksOnMobile: Boolean = false,
) {
    val maxBytes: Long get() = maxGb * 1024L * 1024 * 1024

    companion object {
        const val DEFAULT_MAX_GB = 2
        val MAX_GB_CHOICES = listOf(1, 2, 5, 10)
    }
}

class CacheSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cache_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        CacheSettings(
            maxGb = prefs.getInt(KEY_MAX_GB, CacheSettings.DEFAULT_MAX_GB),
            wholeTracksOnMobile = prefs.getBoolean(KEY_WHOLE_ON_MOBILE, false),
        )
    )
    val settings: StateFlow<CacheSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (CacheSettings) -> CacheSettings): CacheSettings {
        val new = transform(_settings.value)
        prefs.edit().putInt(KEY_MAX_GB, new.maxGb).putBoolean(KEY_WHOLE_ON_MOBILE, new.wholeTracksOnMobile).apply()
        _settings.value = new
        return new
    }

    private companion object {
        const val KEY_MAX_GB = "max_gb"
        const val KEY_WHOLE_ON_MOBILE = "whole_tracks_on_mobile"
    }
}
