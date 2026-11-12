package dev.subsonic.poweramp.subsonic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remembers which OpenSubsonic extensions the current server supports, so lyrics and scrobbling can
 * pick the right endpoint without asking on every call. Re-checked after [ttlMs] or a server change.
 */
class ServerFeatures(
    private val ttlMs: Long = 6 * 60 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cachedFor: String? = null
    private var cachedAt = 0L
    private var extensions: Set<String> = emptySet()

    /**
     * [serverKey] identifies the server (e.g. its base URL). A failed check (classic Subsonic server,
     * or offline) counts as "no extensions" and is retried after [RETRY_FAILED_MS].
     */
    suspend fun supports(serverKey: String, client: SubsonicClient, extension: String): Boolean = mutex.withLock {
        val now = clock()
        if (cachedFor != serverKey || now - cachedAt > ttlMs) {
            try {
                extensions = client.openSubsonicExtensions().map { it.name }.toSet()
                cachedAt = now
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                extensions = emptySet()
                cachedAt = now - ttlMs + RETRY_FAILED_MS
            }
            cachedFor = serverKey
        }
        extension in extensions
    }

    private companion object {
        const val RETRY_FAILED_MS = 60_000L
    }

    fun clear() {
        cachedFor = null
    }
}
