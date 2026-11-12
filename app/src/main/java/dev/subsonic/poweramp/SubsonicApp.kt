package dev.subsonic.poweramp

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import dev.subsonic.poweramp.library.LibraryDatabase
import dev.subsonic.poweramp.settings.CredentialStore
import dev.subsonic.poweramp.subsonic.RedactingLogInterceptor
import dev.subsonic.poweramp.subsonic.SubsonicClient
import dev.subsonic.poweramp.sync.LibrarySync
import dev.subsonic.poweramp.sync.SyncNotifications
import dev.subsonic.poweramp.sync.SyncSettingsStore
import dev.subsonic.poweramp.sync.SyncStatusStore
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

    override fun onCreate() {
        super.onCreate()
        SyncNotifications.createChannel(this)
    }
}
