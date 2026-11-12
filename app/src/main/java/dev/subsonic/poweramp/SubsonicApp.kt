package dev.subsonic.poweramp

import android.app.Application
import dev.subsonic.poweramp.settings.CredentialStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Holds app-wide singletons. Kept deliberately simple; no DI framework for now. */
class SubsonicApp : Application() {

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val credentialStore: CredentialStore by lazy { CredentialStore(this) }
}
