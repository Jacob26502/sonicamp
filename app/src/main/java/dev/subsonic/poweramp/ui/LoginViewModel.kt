package dev.subsonic.poweramp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.scrobble.ScrobbleWorker
import dev.subsonic.poweramp.settings.SavedServer
import dev.subsonic.poweramp.subsonic.ConnectionTester
import dev.subsonic.poweramp.subsonic.ServerInfo
import dev.subsonic.poweramp.subsonic.ServerUrl
import dev.subsonic.poweramp.subsonic.SubsonicAuth
import dev.subsonic.poweramp.sync.SyncNotifications
import dev.subsonic.poweramp.sync.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AuthMode { PASSWORD, API_KEY }

data class LoginForm(
    val serverUrl: String = "",
    val authMode: AuthMode = AuthMode.PASSWORD,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
)

sealed interface TestResult {
    /** [server] is exactly what was tested, so Save stores what actually worked. */
    data class Success(val info: ServerInfo, val server: SavedServer) : TestResult
    data class Failure(val message: String) : TestResult
}

data class LoginUiState(
    val form: LoginForm = LoginForm(),
    val testing: Boolean = false,
    val result: TestResult? = null,
    /** True when the form matches what's saved on the device. */
    val saved: Boolean = false,
) {
    val insecureUrl: Boolean
        get() = runCatching { ServerUrl.isInsecure(ServerUrl.parse(form.serverUrl)) }.getOrDefault(false)

    val canTest: Boolean
        get() = !testing && form.serverUrl.isNotBlank() && when (form.authMode) {
            AuthMode.PASSWORD -> form.username.isNotBlank() && form.password.isNotEmpty()
            AuthMode.API_KEY -> form.apiKey.isNotBlank()
        }

    val canSave: Boolean get() = result is TestResult.Success && !saved
}

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SubsonicApp
    private val tester = ConnectionTester(app.httpClient)

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            val (saved, libraryEmpty) = withContext(Dispatchers.IO) {
                app.credentialStore.load() to (app.libraryDatabase.identity() == null)
            }
            if (saved == null) return@launch
            _state.update { it.copy(form = saved.toForm(), saved = true) }
            // A server is configured but there's no synced library: the database was rebuilt after an
            // app update changed its format, or a first sync never finished. Sync now rather than wait.
            if (libraryEmpty) {
                val settings = app.syncSettings.settings.value
                SyncWorker.schedule(app, settings)
                SyncWorker.syncNow(app, settings)
            }
        }
    }

    /** Any edit invalidates the previous test result. */
    fun edit(transform: (LoginForm) -> LoginForm) {
        testJob?.cancel()
        _state.update {
            it.copy(form = transform(it.form), testing = false, result = null, saved = false)
        }
    }

    fun testConnection() {
        val form = _state.value.form
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _state.update { it.copy(testing = true, result = null) }
            val result = try {
                val server = SavedServer(ServerUrl.parse(form.serverUrl), form.toAuth())
                TestResult.Success(tester.test(server.baseUrl, server.auth), server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestResult.Failure(describeConnectionError(e))
            }
            _state.update { it.copy(testing = false, result = result) }
        }
    }

    /** Saves the tested server and starts syncing. A different server/account replaces the old library. */
    fun save() {
        val success = _state.value.result as? TestResult.Success ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.credentialStore.save(success.server) }
            app.serverFeatures.clear()
            // Plays queued while the login was rejected can go now (other servers' plays are dropped).
            ScrobbleWorker.enqueue(app)
            app.syncStatus.clearAuthFailure()
            SyncNotifications.cancelAuthFailed(app)
            val settings = app.syncSettings.settings.value
            SyncWorker.schedule(app, settings)
            SyncWorker.syncNow(app, settings)
            _state.update { it.copy(saved = true) }
        }
    }

    /** Removes the server, its credentials, the synced library and all scheduled syncs. */
    fun forgetServer() {
        testJob?.cancel()
        viewModelScope.launch {
            SyncWorker.cancelAll(app)
            ScrobbleWorker.cancel(app)
            withContext(Dispatchers.IO) {
                app.credentialStore.clear()
                app.libraryDatabase.wipe()
                app.coverCache.clear()
                app.lyrics.clear()
                app.audioCache.clear(app.streamSessions.inUse())
                app.scrobbler.reset()
            }
            app.serverFeatures.clear()
            app.syncStatus.clear()
            SyncNotifications.cancelAuthFailed(app)
            // Let Poweramp drop the tracks; we're in the foreground, so the request is honoured.
            app.notifyProviderRootsChanged()
            app.powerampRescans.reset()
            app.powerampRescans.request()
            _state.value = LoginUiState()
        }
    }

    private fun LoginForm.toAuth(): SubsonicAuth = when (authMode) {
        AuthMode.PASSWORD -> SubsonicAuth.Password(username.trim(), password)
        AuthMode.API_KEY -> SubsonicAuth.ApiKey(apiKey.trim())
    }

    private fun SavedServer.toForm(): LoginForm = when (val a = auth) {
        is SubsonicAuth.Password -> LoginForm(baseUrl.toString(), AuthMode.PASSWORD, a.username, a.password)
        is SubsonicAuth.ApiKey -> LoginForm(baseUrl.toString(), AuthMode.API_KEY, apiKey = a.key)
    }
}
