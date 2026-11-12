package dev.subsonic.poweramp.poweramp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.library.LibraryDatabase
import dev.subsonic.poweramp.provider.SubsonicDocumentsProvider
import dev.subsonic.poweramp.sync.SyncStatusStore
import dev.subsonic.poweramp.sync.SyncSettingsStore
import dev.subsonic.poweramp.sync.SyncWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps Poweramp's copy of the library in step with ours.
 *
 * Poweramp only rescans a provider when asked, and (Android 8+) ignores scan requests unless it or
 * the sender is in the foreground. So:
 * - after a sync that changed something, ask straight away (works if Poweramp is playing or our app is open);
 * - remember the request is pending until Poweramp actually scans us (it calls CALL_RESCAN first);
 * - when Poweramp starts playing (STATUS_CHANGED_EXPLICIT reaches us even in the background), retry;
 * - when Poweramp scans on its own (e.g. the user taps Rescan) and our last sync is old, sync first and
 *   ask again afterwards, so "Rescan" in Poweramp also pulls new music from the server.
 */
class PowerampRescans(
    private val context: Context,
    private val db: LibraryDatabase,
    private val syncStatus: SyncStatusStore,
    private val syncSettings: SyncSettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("poweramp", Context.MODE_PRIVATE)

    /** Library version Poweramp last scanned. */
    private var scannedVersion: Long
        get() = prefs.getLong(KEY_SCANNED_VERSION, -1)
        set(v) = prefs.edit().putLong(KEY_SCANNED_VERSION, v).apply()

    private var lastRequestAt: Long
        get() = prefs.getLong(KEY_LAST_REQUEST_AT, 0)
        set(v) = prefs.edit().putLong(KEY_LAST_REQUEST_AT, v).apply()

    val pending: Boolean get() = db.libraryVersion() > scannedVersion

    /** Poweramp is about to scan us (TrackProviderConsts.CALL_RESCAN). Must return quickly. */
    fun onPowerampScanStarting() {
        scannedVersion = db.libraryVersion()
        val lastSync = syncStatus.status.value.lastSuccessAt ?: 0
        if (clock() - lastSync > STALE_AFTER_MS && !syncStatus.status.value.running) {
            Log.i(TAG, "Poweramp is scanning and our library is stale: syncing")
            SyncWorker.syncNow(context, syncSettings.settings.value)
        }
    }

    /** Called after every successful sync. */
    fun onSyncFinished(changed: Boolean) {
        if (changed || pending) request(force = changed)
    }

    /** Poweramp started/resumed playback: it is in the foreground, so a request will be honoured. */
    fun onPowerampActive() {
        if (pending) request(force = false)
    }

    /**
     * Asks Poweramp to rescan just this provider. Silently ignored by Poweramp if neither app is in
     * the foreground; that's why the request stays pending until Poweramp actually scans.
     */
    fun request(force: Boolean = true) {
        val now = clock()
        if (!force && now - lastRequestAt < MIN_RETRY_INTERVAL_MS) return
        lastRequestAt = now
        val intent = Intent(PowerampApi.Scanner.ACTION_SCAN_DIRS)
            .setComponent(ComponentName(PowerampApi.PACKAGE, PowerampApi.API_RECEIVER))
            .putExtra(PowerampApi.Scanner.EXTRA_PATH, "@${SubsonicDocumentsProvider.AUTHORITY}/")
        try {
            context.sendBroadcast(intent)
            Log.i(TAG, "Asked Poweramp to rescan (library version ${db.libraryVersion()})")
        } catch (e: Exception) {
            Log.w(TAG, "Could not reach Poweramp", e)
        }
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "PowerampRescans"
        const val KEY_SCANNED_VERSION = "scanned_version"
        const val KEY_LAST_REQUEST_AT = "last_request_at"
        /** "Rescan" in Poweramp triggers a server sync if ours is older than this. */
        const val STALE_AFTER_MS = 10 * 60_000L
        /** Don't re-send on every pause/resume. */
        const val MIN_RETRY_INTERVAL_MS = 5 * 60_000L
    }
}

/**
 * Receives Poweramp's explicit status and track broadcasts (delivered even when we're in the
 * background): retries pending rescans, and feeds the scrobbler.
 */
class PowerampEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val app = context.applicationContext as SubsonicApp
            if (intent.action == PowerampApi.ACTION_STATUS_CHANGED_EXPLICIT) app.powerampRescans.onPowerampActive()
            val outputs = app.scrobbler.handle(intent)
            if (outputs.isEmpty()) return
            // Keep the process alive while reports go out; the receiver's time limit is ~10 s.
            val pending = goAsync()
            app.appScope.launch {
                try {
                    withTimeoutOrNull(DELIVERY_TIMEOUT_MS) { app.scrobbler.deliver(outputs) }
                } finally {
                    pending.finish()
                }
            }
        } catch (t: Throwable) {
            Log.e("PowerampEventReceiver", "onReceive", t)
        }
    }

    private companion object {
        const val DELIVERY_TIMEOUT_MS = 8_000L
    }
}
