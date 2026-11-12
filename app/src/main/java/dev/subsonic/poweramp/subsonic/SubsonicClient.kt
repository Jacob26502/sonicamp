package dev.subsonic.poweramp.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal Subsonic / OpenSubsonic REST client.
 *
 * @param baseUrl from [ServerUrl.parse]; endpoints are `<baseUrl>rest/<method>.view`.
 * @param auth null only for calls that need no login ([openSubsonicExtensions]).
 */
class SubsonicClient(
    val baseUrl: HttpUrl,
    private val auth: SubsonicAuth?,
    private val http: OkHttpClient,
    private val clientName: String = CLIENT_NAME,
) {

    /** Checks the credentials. Throws [SubsonicApiException] (e.g. code 40) if they're rejected. */
    suspend fun ping(): BaseResponse = get("ping").let { json.decodeFromJsonElement(it) }

    /**
     * Lists OpenSubsonic extensions. The spec requires this endpoint to work without
     * authentication, so it can be called before the user has logged in.
     */
    suspend fun openSubsonicExtensions(): List<OpenSubsonicExtension> {
        val body = get("getOpenSubsonicExtensions", authenticated = false)
        val list = body["openSubsonicExtensions"] ?: return emptyList()
        return json.decodeFromJsonElement(list)
    }

    /** URL that streams a song. Contains credentials: treat it as a secret. */
    fun streamUrl(songId: String, maxBitRateKbps: Int? = null, format: String? = null): HttpUrl =
        endpoint(
            "stream",
            buildList {
                add("id" to songId)
                maxBitRateKbps?.let { add("maxBitRate" to it.toString()) }
                format?.let { add("format" to it) }
            },
        )

    /** URL for cover art. Contains credentials: treat it as a secret. */
    fun coverArtUrl(coverArtId: String, sizePx: Int? = null): HttpUrl =
        endpoint(
            "getCoverArt",
            buildList {
                add("id" to coverArtId)
                sizePx?.let { add("size" to it.toString()) }
            },
        )

    internal fun endpoint(
        method: String,
        params: List<Pair<String, String>> = emptyList(),
        authenticated: Boolean = true,
    ): HttpUrl {
        val builder = baseUrl.newBuilder()
            .addPathSegment("rest")
            .addPathSegment("$method.view")
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", clientName)
            .addQueryParameter("f", "json")
        if (authenticated) {
            val a = checkNotNull(auth) { "$method needs authentication but no credentials were given" }
            a.queryParams().forEach { (k, v) -> builder.addQueryParameter(k, v) }
        }
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    /** Performs a JSON call and returns the inside of `subsonic-response` if status is ok. */
    private suspend fun get(
        method: String,
        params: List<Pair<String, String>> = emptyList(),
        authenticated: Boolean = true,
    ): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint(method, params, authenticated)).get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            // Some servers send Subsonic errors with HTTP 4xx, so try to parse the body first.
            val payload = parseEnvelope(text)
                ?: throw UnexpectedResponseException(
                    if (response.isSuccessful) "The server didn't return a Subsonic response. Check the address."
                    else "HTTP ${response.code} from server. Check the address."
                )
            val base: BaseResponse = json.decodeFromJsonElement(payload)
            if (base.status != "ok") {
                val err = base.error
                throw SubsonicApiException(err?.code ?: SubsonicApiException.GENERIC, err?.message, err?.helpUrl)
            }
            payload
        }
    }

    private fun parseEnvelope(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObject["subsonic-response"]?.jsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        /** Highest classic Subsonic API version; also what Navidrome reports. */
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "SubsonicPoweramp"

        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
