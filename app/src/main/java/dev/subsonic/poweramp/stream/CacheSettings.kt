package dev.subsonic.poweramp.stream

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CacheSettings(
    val maxGb: Int = DEFAULT_MAX_GB,
    /** Download whole tracks on mobile data too (otherwise only 8 MB ahead of the play position). */
    val wholeTracksOnMobile: Boolean = false,
    /** MP3 bitrate in kbps to stream at on Wi-Fi, or [ORIGINAL]. */
    val wifiQuality: Int = ORIGINAL,
    /** MP3 bitrate in kbps to stream at on mobile data, or [ORIGINAL]. */
    val mobileQuality: Int = ORIGINAL,
    /** Replace cached transcodes with the original in the background (Wi-Fi, charging). */
    val autoUpgrade: Boolean = true,
) {
    val maxBytes: Long get() = maxGb * 1024L * 1024 * 1024

    fun quality(metered: Boolean) = if (metered) mobileQuality else wifiQuality

    companion object {
        const val DEFAULT_MAX_GB = 2
        val MAX_GB_CHOICES = listOf(1, 2, 5, 10)
        const val ORIGINAL = 0
        /** Original, or constant-bitrate MP3 (its size is predictable, which streaming needs). */
        val QUALITY_CHOICES = listOf(ORIGINAL, 320, 256, 192, 128)
    }
}

class CacheSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cache_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        CacheSettings(
            maxGb = prefs.getInt(KEY_MAX_GB, CacheSettings.DEFAULT_MAX_GB),
            wholeTracksOnMobile = prefs.getBoolean(KEY_WHOLE_ON_MOBILE, false),
            wifiQuality = prefs.getInt(KEY_WIFI_QUALITY, CacheSettings.ORIGINAL),
            mobileQuality = prefs.getInt(KEY_MOBILE_QUALITY, CacheSettings.ORIGINAL),
            autoUpgrade = prefs.getBoolean(KEY_AUTO_UPGRADE, true),
        )
    )
    val settings: StateFlow<CacheSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (CacheSettings) -> CacheSettings): CacheSettings {
        val new = transform(_settings.value)
        prefs.edit()
            .putInt(KEY_MAX_GB, new.maxGb)
            .putBoolean(KEY_WHOLE_ON_MOBILE, new.wholeTracksOnMobile)
            .putInt(KEY_WIFI_QUALITY, new.wifiQuality)
            .putInt(KEY_MOBILE_QUALITY, new.mobileQuality)
            .putBoolean(KEY_AUTO_UPGRADE, new.autoUpgrade)
            .apply()
        _settings.value = new
        return new
    }

    private companion object {
        const val KEY_MAX_GB = "max_gb"
        const val KEY_WHOLE_ON_MOBILE = "whole_tracks_on_mobile"
        const val KEY_WIFI_QUALITY = "wifi_quality"
        const val KEY_MOBILE_QUALITY = "mobile_quality"
        const val KEY_AUTO_UPGRADE = "auto_upgrade"
    }
}
