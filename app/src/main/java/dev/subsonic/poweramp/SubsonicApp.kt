package dev.subsonic.poweramp

import android.app.Application
import android.content.pm.ApplicationInfo
import android.provider.DocumentsContract
import android.util.Log
import dev.subsonic.poweramp.library.LibraryDatabase
import dev.subsonic.poweramp.lyrics.LyricsRepository
import dev.subsonic.poweramp.poweramp.PowerampRescans
import dev.subsonic.poweramp.provider.CoverCache
import dev.subsonic.poweramp.provider.SubsonicDocumentsProvider
import dev.subsonic.poweramp.scrobble.Scrobbler
import dev.subsonic.poweramp.stream.AudioCache
import dev.subsonic.poweramp.stream.CacheSettingsStore
import dev.subsonic.poweramp.stream.StreamSessions
import dev.subsonic.poweramp.stream.UpgradeWorker
import java.io.File
import dev.subsonic.poweramp.settings.CredentialStore
import dev.subsonic.poweramp.subsonic.RedactingLogInterceptor
import dev.subsonic.poweramp.subsonic.ServerFeatures
import dev.subsonic.poweramp.subsonic.SubsonicClient
import dev.subsonic.poweramp.sync.LibrarySync
import dev.subsonic.poweramp.sync.SyncNotifications
import dev.subsonic.poweramp.sync.SyncSettingsStore
import dev.subsonic.poweramp.sync.SyncStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Holds app-wide singletons. Kept deliberately simple; no DI framework for now. */
class SubsonicApp : Application() {

    private val debuggable get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (debuggable) addInterceptor(RedactingLogInterceptor { Log.d("SubsonicHttp", it) })
            }
            .build()
    }

    val credentialStore: CredentialStore by lazy { CredentialStore(this) }
    val libraryDatabase: LibraryDatabase by lazy { LibraryDatabase(this) }
    val syncStatus: SyncStatusStore by lazy { SyncStatusStore(this) }
    val syncSettings: SyncSettingsStore by lazy { SyncSettingsStore(this) }

    val librarySync: LibrarySync by lazy {
        LibrarySync(libraryDatabase, { server -> SubsonicClient(server.baseUrl, server.auth, httpClient) })
    }

    val coverCache: CoverCache by lazy { CoverCache(File(cacheDir, "covers"), httpClient) }

    val powerampRescans: PowerampRescans by lazy { PowerampRescans(this, libraryDatabase, syncStatus, syncSettings) }

    val serverFeatures: ServerFeatures by lazy { ServerFeatures() }

    val lyrics: LyricsRepository by lazy { LyricsRepository(File(cacheDir, "lyrics"), serverFeatures) }

    val scrobbler: Scrobbler by lazy { Scrobbler(this) }

    val cacheSettings: CacheSettingsStore by lazy { CacheSettingsStore(this) }

    /** Played tracks, capped by the user's setting. In cacheDir, so Android may also clear it when low on space. */
    val audioCache: AudioCache by lazy { AudioCache(File(cacheDir, "audio")) { cacheSettings.settings.value.maxBytes } }

    val streamSessions: StreamSessions by lazy {
        StreamSessions(
            this, httpClient, audioCache,
            settings = { cacheSettings.settings.value },
            onTranscodePlayed = { if (cacheSettings.settings.value.autoUpgrade) UpgradeWorker.enqueue(this) },
        )
    }

    /** For short background work started from broadcast receivers. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Client for the saved server, or null if none is configured. */
    fun currentClient(): SubsonicClient? {
        val server = credentialStore.server.value ?: credentialStore.load() ?: return null
        return SubsonicClient(server.baseUrl, server.auth, httpClient)
    }

    /** Tells Storage Access Framework clients (pickers, Poweramp's folder dialog) the roots changed. */
    fun notifyProviderRootsChanged() {
        contentResolver.notifyChange(DocumentsContract.buildRootsUri(SubsonicDocumentsProvider.AUTHORITY), null)
    }

    override fun onCreate() {
        super.onCreate()
        SyncNotifications.createChannel(this)
    }
}
