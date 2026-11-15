package dev.subsonic.poweramp.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.subsonic.poweramp.subsonic.ServerUrl
import dev.subsonic.poweramp.server.ServerAuth
import dev.subsonic.poweramp.server.ServerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the one configured server. The URL and username are stored as-is; the password or
 * API key is encrypted with [SecretCipher] before it touches disk.
 *
 * [server] reflects the saved server once [load] has run (it decrypts, so call it off the main thread).
 */
class CredentialStore(
    context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _server = MutableStateFlow<SavedServer?>(null)
    val server: StateFlow<SavedServer?> = _server.asStateFlow()

    @Synchronized
    fun load(): SavedServer? {
        val loaded = readFromPrefs()
        _server.value = loaded
        return loaded
    }

    private fun readFromPrefs(): SavedServer? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val secretEnc = prefs.getString(KEY_SECRET, null) ?: return null
        return try {
            val baseUrl = ServerUrl.parse(url)
            val secret = cipher.decrypt(secretEnc)
            val auth = when (prefs.getString(KEY_AUTH_TYPE, null)) {
                TYPE_API_KEY -> ServerAuth.ApiKey(secret)
                TYPE_PASSWORD -> ServerAuth.Password(prefs.getString(KEY_USERNAME, null) ?: return null, secret)
                else -> return null
            }
            // Saved before server types existed: Subsonic.
            val type = prefs.getString(KEY_SERVER_TYPE, null)?.let { runCatching { ServerType.valueOf(it) }.getOrNull() }
                ?: ServerType.SUBSONIC
            SavedServer(baseUrl, auth, type)
        } catch (e: Exception) {
            // Keystore key lost (e.g. device restore or lock-screen reset): the secret is unrecoverable.
            Log.w(TAG, "Saved credentials unreadable; clearing", e)
            prefs.edit().clear().apply()
            null
        }
    }

    @Synchronized
    fun save(server: SavedServer) {
        val editor = prefs.edit()
            .putString(KEY_URL, server.baseUrl.toString())
            .putString(KEY_SERVER_TYPE, server.type.name)
        when (val auth = server.auth) {
            is ServerAuth.Password -> editor
                .putString(KEY_AUTH_TYPE, TYPE_PASSWORD)
                .putString(KEY_USERNAME, auth.username)
                .putString(KEY_SECRET, cipher.encrypt(auth.password))
            is ServerAuth.ApiKey -> editor
                .putString(KEY_AUTH_TYPE, TYPE_API_KEY)
                .remove(KEY_USERNAME)
                .putString(KEY_SECRET, cipher.encrypt(auth.key))
        }
        editor.apply()
        _server.value = server
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _server.value = null
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val PREFS = "server"
        const val KEY_URL = "url"
        const val KEY_SERVER_TYPE = "server_type"
        const val KEY_AUTH_TYPE = "auth_type"
        const val KEY_USERNAME = "username"
        const val KEY_SECRET = "secret"
        const val TYPE_PASSWORD = "password"
        const val TYPE_API_KEY = "api_key"
    }
}
