package dev.subsonic.poweramp.subsonic

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Suspends until the call completes. Cancelling the coroutine cancels the HTTP call itself,
 * so a stopped sync job doesn't leave requests running until they time out.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // Ignored by the continuation if it was already cancelled.
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // If the coroutine was cancelled in the meantime, close the response instead of leaking it.
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}
