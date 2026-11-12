package dev.subsonic.poweramp.subsonic

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Logs one line per request (method, URL, status, time) with every credential parameter masked.
 * Only installed in debug builds.
 */
class RedactingLogInterceptor(private val log: (String) -> Unit) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = redact(request.url)
        val start = System.nanoTime()
        try {
            val response = chain.proceed(request)
            log("${request.method} $url -> ${response.code} (${elapsedMs(start)} ms)")
            return response
        } catch (e: IOException) {
            log("${request.method} $url -> failed after ${elapsedMs(start)} ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000

    companion object {
        /** Subsonic auth parameters. `u` is masked too: usernames are personal data. */
        val SECRET_PARAMS = setOf("u", "p", "t", "s", "apiKey")

        fun redact(url: HttpUrl): String {
            if (url.querySize == 0) return url.toString()
            val builder = url.newBuilder().query(null)
            for (i in 0 until url.querySize) {
                val name = url.queryParameterName(i)
                val value = if (name in SECRET_PARAMS) "***" else url.queryParameterValue(i)
                builder.addQueryParameter(name, value)
            }
            return builder.build().toString()
        }
    }
}
