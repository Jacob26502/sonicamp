package dev.subsonic.poweramp.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    suspend fun ping(): BaseResponse = json.decodeFromJsonElement(get("ping"))

    /**
     * Lists OpenSubsonic extensions. The spec requires this endpoint to work without
     * authentication, so it can be called before the user has logged in.
     */
    suspend fun openSubsonicExtensions(): List<OpenSubsonicExtension> =
        decodeList(get("getOpenSubsonicExtensions", authenticated = false)["openSubsonicExtensions"], OpenSubsonicExtension.serializer())

    suspend fun musicFolders(): List<MusicFolder> =
        decodeList(get("getMusicFolders").obj("musicFolders")?.get("musicFolder"), MusicFolder.serializer())

    /**
     * One page of every song in the library (search3 with an empty query; supported by Navidrome).
     * Pages are not a consistent snapshot if the library changes mid-sync, so callers should dedupe.
     */
    suspend fun allSongsPage(offset: Int, count: Int, musicFolderId: Int? = null): List<Song> {
        val params = buildList {
            add("query" to "")
            add("artistCount" to "0")
            add("albumCount" to "0")
            add("songCount" to count.toString())
            add("songOffset" to offset.toString())
            musicFolderId?.let { add("musicFolderId" to it.toString()) }
        }
        return decodeList(get("search3", params).obj("searchResult3")?.get("song"), Song.serializer())
    }

    suspend fun playlists(): List<Playlist> =
        decodeList(get("getPlaylists").obj("playlists")?.get("playlist"), Playlist.serializer())

    suspend fun playlist(id: String): Playlist {
        val element = get("getPlaylist", listOf("id" to id))["playlist"]
            ?: throw UnexpectedResponseException("getPlaylist returned no playlist")
        return json.decodeFromJsonElement(Playlist.serializer(), element)
    }

    suspend fun scanStatus(): ScanStatus {
        val element = get("getScanStatus")["scanStatus"] ?: return ScanStatus()
        return json.decodeFromJsonElement(ScanStatus.serializer(), element)
    }

    /** Lyrics embedded in or next to the song file (OpenSubsonic `songLyrics`). Empty if there are none. */
    suspend fun lyricsBySongId(songId: String): List<StructuredLyrics> =
        decodeList(
            get("getLyricsBySongId", listOf("id" to songId)).obj("lyricsList")?.get("structuredLyrics"),
            StructuredLyrics.serializer(),
        )

    /** Classic Subsonic lyrics search by artist and title; plain text, or null if none. */
    suspend fun lyrics(artist: String?, title: String): String? {
        val params = buildList {
            artist?.let { add("artist" to it) }
            add("title" to title)
        }
        val value = (get("getLyrics", params).obj("lyrics")?.get("value") as? JsonPrimitive)?.content
        return value?.takeIf { it.isNotBlank() }
    }

    /**
     * Counts a play ([submission] true, with the time it started) or sets "now playing" (false).
     * Works on every Subsonic server.
     */
    suspend fun scrobble(songId: String, playedAtMs: Long?, submission: Boolean) {
        val params = buildList {
            add("id" to songId)
            playedAtMs?.let { add("time" to it.toString()) }
            add("submission" to submission.toString())
        }
        get("scrobble", params)
    }

    /**
     * Live playback state (OpenSubsonic `playbackReport`), shown by the server as "now playing".
     * [ignoreScrobble] true leaves play counting to [scrobble].
     */
    suspend fun reportPlayback(songId: String, state: String, positionMs: Long, ignoreScrobble: Boolean = true) {
        get(
            "reportPlayback",
            listOf(
                "mediaId" to songId,
                "mediaType" to "song",
                "positionMs" to positionMs.toString(),
                "state" to state,
                "ignoreScrobble" to ignoreScrobble.toString(),
            ),
        )
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
    ): JsonObject {
        val request = Request.Builder().url(endpoint(method, params, authenticated)).get().build()
        // await() makes the request cancellable; body reading is blocking, so it runs on IO.
        val response = http.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                val text = it.body?.string().orEmpty()
                // Some servers send Subsonic errors with HTTP 4xx, so try to parse the body first.
                val payload = parseEnvelope(text)
                    ?: throw UnexpectedResponseException(
                        if (it.isSuccessful) "The server didn't return a Subsonic response. Check the address."
                        else "HTTP ${it.code} from server. Check the address."
                    )
                val base: BaseResponse = json.decodeFromJsonElement(payload)
                if (base.status != "ok") {
                    val err = base.error
                    throw SubsonicApiException(err?.code ?: SubsonicApiException.GENERIC, err?.message, err?.helpUrl)
                }
                payload
            }
        }
    }

    private fun parseEnvelope(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObject["subsonic-response"]?.jsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /**
     * Decodes a list that may be missing (empty result) or, on some servers' JSON output,
     * a single object instead of a one-element array.
     */
    private fun <T> decodeList(element: JsonElement?, item: KSerializer<T>): List<T> = when (element) {
        null -> emptyList()
        is JsonArray -> json.decodeFromJsonElement(ListSerializer(item), element)
        is JsonObject -> listOf(json.decodeFromJsonElement(item, element))
        else -> emptyList()
    }

    companion object {
        /** Highest classic Subsonic API version; also what Navidrome reports. */
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Sonic-Amp"

        /**
         * isLenient stays on deliberately: some servers send IDs as JSON numbers, and strict mode
         * refuses to read a number into a String field.
         */
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
