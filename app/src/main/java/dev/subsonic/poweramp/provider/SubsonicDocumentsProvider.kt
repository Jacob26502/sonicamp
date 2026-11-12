package dev.subsonic.poweramp.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import com.maxmpz.poweramp.player.TrackProviderConsts
import dev.subsonic.poweramp.R
import dev.subsonic.poweramp.SubsonicApp
import dev.subsonic.poweramp.library.AlbumFolder
import dev.subsonic.poweramp.library.FolderKind
import dev.subsonic.poweramp.library.PlaylistRow
import dev.subsonic.poweramp.library.TrackRow
import dev.subsonic.poweramp.library.playlistRow
import dev.subsonic.poweramp.library.playlistRows
import dev.subsonic.poweramp.library.playlistTracks
import dev.subsonic.poweramp.lyrics.TrackLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import dev.subsonic.poweramp.library.albumFolder
import dev.subsonic.poweramp.library.albumFolders
import dev.subsonic.poweramp.library.albumFoldersWithoutArtist
import dev.subsonic.poweramp.library.artistFolder
import dev.subsonic.poweramp.library.artistFolders
import dev.subsonic.poweramp.library.libraryFolder
import dev.subsonic.poweramp.library.libraryFolders
import dev.subsonic.poweramp.library.track
import dev.subsonic.poweramp.library.tracksOfAlbum
import dev.subsonic.poweramp.library.tracksWithoutAlbum
import dev.subsonic.poweramp.settings.SavedServer
import dev.subsonic.poweramp.subsonic.SubsonicClient
import java.io.FileNotFoundException

/**
 * Exposes the synced library to Poweramp (and any Storage Access Framework client) as
 * Library → Album artist → Album → Tracks. Everything is answered from the local database; only
 * playback URLs and cover art touch the network.
 *
 * Tracks are "dynamic URL" tracks: Poweramp asks [call] with [TrackProviderConsts.CALL_GET_URL]
 * when playback starts, so login tokens are minted fresh and never stored in Poweramp's database.
 *
 * Every entry point catches Throwable: an exception escaping a provider can take Poweramp down.
 */
class SubsonicDocumentsProvider : DocumentsProvider() {

    private val app: SubsonicApp get() = context!!.applicationContext as SubsonicApp
    private val db get() = app.libraryDatabase

    override fun onCreate(): Boolean = true

    // ---- roots ----

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_PROJECTION)
        return guard(c) {
            val server = currentServer() ?: return@guard c
            val libraries = db.libraryFolders()
            libraries.forEach { lib ->
                val id = DocId.Library(lib.id).encoded
                c.newRow().apply {
                    add(Root.COLUMN_ROOT_ID, id)
                    add(Root.COLUMN_DOCUMENT_ID, id)
                    add(Root.COLUMN_TITLE, if (libraries.size > 1) "Subsonic: ${lib.name}" else "Subsonic")
                    add(Root.COLUMN_SUMMARY, "${lib.name} · ${server.baseUrl.host}")
                    add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD) // required by Poweramp's folder picker
                    add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                }
            }
            c
        }
    }

    // ---- documents ----

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        logProjection("queryDocument", projection)
        val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        return guard(c) {
            when (val doc = DocId.parse(documentId)) {
                is DocId.Library -> {
                    val lib = db.libraryFolder(doc.libraryId) ?: notFound(documentId)
                    addFolder(c, doc, lib.name, lib.lastModified, TrackProviderConsts.FLAG_HAS_SUBDIRS, thumbnail = false)
                }
                is DocId.Artist -> {
                    val artist = db.artistFolder(doc.libraryId, doc.artistId) ?: notFound(documentId)
                    addFolder(c, doc, artist.name, artist.lastModified, TrackProviderConsts.FLAG_HAS_SUBDIRS, thumbnail = false)
                }
                is DocId.Album -> addAlbum(c, db.albumFolder(doc.albumId) ?: notFound(documentId))
                is DocId.Track -> {
                    val track = db.track(doc.trackId) ?: notFound(documentId)
                    // Poweramp's Lyrics view asks for one track with the lyrics columns; only then fetch.
                    val lyrics = if (wantsLyrics(projection)) lyricsFor(track) else null
                    addTrack(c, track, position = null, lyrics = lyrics)
                }
                DocId.Playlists -> if (playlistsParent() != null) addPlaylistsFolder(c) else notFound(documentId)
                is DocId.Playlist -> addPlaylist(c, db.playlistRow(doc.playlistId) ?: notFound(documentId))
                null -> notFound(documentId)
            }
            c
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        logProjection("queryChildDocuments", projection)
        val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        return guard(c) {
            when (val doc = DocId.parse(parentDocumentId)) {
                is DocId.Library -> {
                    if (playlistsParent() == doc) addPlaylistsFolder(c)
                    db.artistFolders(doc.libraryId).forEach {
                        addFolder(c, DocId.Artist(it.libraryId, it.id), it.name, it.lastModified, TrackProviderConsts.FLAG_HAS_SUBDIRS, thumbnail = false)
                    }
                    db.albumFoldersWithoutArtist(doc.libraryId).forEach { addAlbum(c, it) }
                    db.tracksWithoutAlbum(doc.libraryId).forEachIndexed { i, t -> addTrack(c, t, position = i + 1) }
                }
                is DocId.Artist -> db.albumFolders(doc.libraryId, doc.artistId).forEach { addAlbum(c, it) }
                is DocId.Album -> db.tracksOfAlbum(doc.albumId).forEachIndexed { i, t -> addTrack(c, t, position = i + 1) }
                DocId.Playlists -> db.playlistRows().forEach { addPlaylist(c, it) }
                is DocId.Track, is DocId.Playlist, null -> notFound(parentDocumentId)
            }
            c
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = guard(false) {
        var current = DocId.parse(documentId)
        repeat(MAX_DEPTH) {
            current = current?.let(::parentOf) ?: return@guard false
            if (current?.encoded == parentDocumentId) return@guard true
        }
        false
    }

    // ---- data ----

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("$documentId is read-only")
        val doc = DocId.parse(documentId)
        if (doc is DocId.Playlist) {
            try {
                val file = writePlaylistFile(doc.playlistId) ?: notFound(documentId)
                Log.i(TAG, "Playlist $documentId opened (${file.length()} bytes)")
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: FileNotFoundException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "openDocument $documentId", t)
                throw FileNotFoundException(t.message)
            }
        }
        if (doc is DocId.Track) {
            try {
                return openTrack(doc.trackId)
            } catch (e: FileNotFoundException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "openDocument $documentId", t)
                throw FileNotFoundException(t.message)
            }
        }
        throw FileNotFoundException("$documentId is not a file")
    }

    /**
     * A seekable descriptor for the original file, served from the audio cache and filled from the
     * server as needed. Returns at once: the size is known from the library, and nothing is fetched
     * until Poweramp reads (it times out slow opens).
     */
    private fun openTrack(trackId: String): ParcelFileDescriptor {
        val track = db.track(trackId) ?: notFound("track:$trackId")
        val size = track.sizeBytes?.takeIf { it > 0 } ?: notFound("track:$trackId (unknown size)")
        val server = currentServer() ?: notFound("track:$trackId (no server)")
        val sessions = app.streamSessions
        val client = client(server)
        val source = sessions.chooseSource(server, trackId, size, track.durationSec, track.bitRate) { kbps, format ->
            client.streamUrl(trackId, maxBitRateKbps = kbps, format = format)
        }
        val session = sessions.acquire(server, trackId, source)
        val thread = HandlerThread("fd-$trackId").apply { start() }
        val callback = RemoteFileCallback(session) {
            sessions.release(session)
            thread.quitSafely()
        }
        Log.d(TAG, "Opening track $trackId (${source.variant}, ${source.size} bytes${if (session.isComplete) ", cached" else ""})")
        return try {
            context!!.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
        } catch (t: Throwable) {
            sessions.release(session)
            thread.quitSafely()
            throw t
        }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        try {
            val coverArt = when (val doc = DocId.parse(documentId)) {
                // Navidrome gives every track its own cover id even when the image is the album's, so
                // per-track ids meant one download per track (seen on device: ~1 MB each). Use the
                // album's cover so a whole album shares one cached image.
                is DocId.Track -> db.track(doc.trackId)?.let { t ->
                    t.albumId?.let { db.albumFolder(it)?.coverArt } ?: t.coverArt
                }
                is DocId.Album -> db.albumFolder(doc.albumId)?.coverArt
                else -> null
            } ?: notFound(documentId)
            val server = currentServer() ?: notFound(documentId)
            val size = sizeHint?.let { maxOf(it.x, it.y) } ?: 0
            val file = app.coverCache.get(client(server), coverArt, size, signal)
            Log.d(TAG, "Cover for $documentId (${sizeHint?.x}x${sizeHint?.y}) -> ${file.length()} bytes")
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
        } catch (e: FileNotFoundException) {
            throw e // API-defined: "no thumbnail"
        } catch (t: Throwable) {
            Log.e(TAG, "openDocumentThumbnail $documentId", t)
            throw FileNotFoundException(t.message)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = guard(null) {
        when (method) {
            TrackProviderConsts.CALL_GET_URL -> streamUrl(arg)
            TrackProviderConsts.CALL_RESCAN -> {
                Log.i(TAG, "Poweramp is starting a scan (provider=${extras?.getString("provider")}, path=${extras?.getString("path")})")
                app.powerampRescans.onPowerampScanStarting()
                null
            }
            TrackProviderConsts.CALL_GET_DIR_METADATA -> dirMetadata(arg)
            else -> super.call(method, arg, extras)
        }
    }

    private fun streamUrl(arg: String?): Bundle? {
        val docId = arg?.let { DocumentsContract.getDocumentId(Uri.parse(it)) } ?: return null
        val track = DocId.parse(docId) as? DocId.Track ?: return null
        val server = currentServer() ?: return null
        Log.i(TAG, "Playback URL requested for ${track.trackId}") // never log the URL itself: it carries a token
        return Bundle().apply {
            // Fresh salt/token for every playback; original quality (transcoded streams can't seek).
            putString(TrackProviderConsts.COLUMN_URL, client(server).streamUrl(track.trackId).toString())
        }
    }

    /** Lets Poweramp show the full Library/Artist/Album path even if the user picked a sub-folder. */
    private fun dirMetadata(arg: String?): Bundle {
        val docId = arg?.let { DocumentsContract.getDocumentId(Uri.parse(it)) } ?: return Bundle.EMPTY
        val ancestors = ArrayDeque<String>()
        var current = DocId.parse(docId)?.let(::parentOf)
        while (current != null && ancestors.size < MAX_DEPTH) {
            ancestors.addFirst(current.encoded)
            current = parentOf(current)
        }
        if (ancestors.isEmpty()) return Bundle.EMPTY
        return Bundle().apply { putStringArray(TrackProviderConsts.EXTRA_ANCESTORS, ancestors.toTypedArray()) }
    }

    // ---- rows ----

    private fun addFolder(c: MatrixCursor, doc: DocId, name: String, lastModified: Long, subdirs: Int, thumbnail: Boolean) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, doc.encoded)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_DISPLAY_NAME, cleanName(name))
            add(Document.COLUMN_LAST_MODIFIED, shown(lastModified))
            add(Document.COLUMN_FLAGS, if (thumbnail) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
            // Lets Poweramp's folder picker skip probing for sub-folders.
            add(TrackProviderConsts.COLUMN_FLAGS, subdirs)
        }
    }

    private fun addAlbum(c: MatrixCursor, album: AlbumFolder) = addFolder(
        c, DocId.Album(album.id), album.name, album.lastModified,
        TrackProviderConsts.FLAG_NO_SUBDIRS, thumbnail = album.coverArt != null,
    )

    private fun addPlaylistsFolder(c: MatrixCursor) =
        addFolder(c, DocId.Playlists, PLAYLISTS_FOLDER_NAME, playlistsLastModified(), TrackProviderConsts.FLAG_NO_SUBDIRS, thumbnail = false)

    private fun addPlaylist(c: MatrixCursor, p: PlaylistRow) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, DocId.Playlist(p.id).encoded)
            add(Document.COLUMN_MIME_TYPE, PLAYLIST_MIME)
            add(Document.COLUMN_DISPLAY_NAME, cleanName(p.name) + DocId.PLAYLIST_EXT)
            add(Document.COLUMN_LAST_MODIFIED, shown(p.lastModified))
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    /**
     * @param position 1-based order within its folder, for Poweramp's "By Track #" folder sorting.
     * @param lyrics only when the caller asked for the lyrics columns.
     */
    private fun addTrack(c: MatrixCursor, t: TrackRow, position: Int?, lyrics: TrackLyrics? = null) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, DocId.Track(t.id).encoded)
            add(Document.COLUMN_MIME_TYPE, t.contentType ?: t.suffix?.let { "audio/$it" } ?: "audio/*")
            add(Document.COLUMN_DISPLAY_NAME, displayName(t))
            add(Document.COLUMN_LAST_MODIFIED, shown(t.lastModified))
            add(Document.COLUMN_SIZE, t.sizeBytes)
            add(Document.COLUMN_FLAGS, if (t.coverArt != null) Document.FLAG_SUPPORTS_THUMBNAIL else 0)

            // No COLUMN_URL: tracks are files, read through openDocument (seekable, no token in Poweramp).
            // Having TITLE/DURATION tells Poweramp to trust these tags and not read the file.
            add(MediaStore.MediaColumns.TITLE, t.title)
            add(MediaStore.Audio.AudioColumns.DURATION, (t.durationSec ?: 0) * 1000L)
            add(MediaStore.Audio.AudioColumns.ARTIST, t.artist)
            add(MediaStore.Audio.AudioColumns.ALBUM, t.album)
            add(MediaStore.Audio.AudioColumns.YEAR, t.year)
            add(TrackProviderConsts.COLUMN_ALBUM_ARTIST, t.albumArtist)
            add(MediaStore.Audio.AudioColumns.COMPOSER, t.composer)
            add(TrackProviderConsts.COLUMN_GENRE, t.genre)
            // Poweramp never asks for a disc number, so multi-disc order is carried by this alone.
            add(MediaStore.Audio.AudioColumns.TRACK, trackNumber(t))
            if (position != null) add(TrackProviderConsts.COLUMN_TRACK_ALT, position)
            add(MediaFormat.KEY_SAMPLE_RATE, t.sampleRate)
            add(MediaFormat.KEY_CHANNEL_COUNT, t.channels)
            add(MediaFormat.KEY_BIT_RATE, t.bitRate?.let { it * 1000 }) // server reports kbps
            add(TrackProviderConsts.COLUMN_BITS_PER_SAMPLE, t.bitDepth)
            // Empty wave: use Poweramp's default seekbar instead of downloading every track to scan it.
            add(TrackProviderConsts.COLUMN_TRACK_WAVE, ByteArray(0))
            if (lyrics != null) {
                add(TrackProviderConsts.COLUMN_TRACK_LYRICS, lyrics.plain)
                add(TrackProviderConsts.COLUMN_TRACK_LYRICS_SYNCED, lyrics.synced)
                if (!lyrics.isEmpty) add(TrackProviderConsts.COLUMN_FLAGS, TrackProviderConsts.FLAG_HAS_LYRICS)
            }
        }
    }

    // ---- lyrics ----

    private fun wantsLyrics(projection: Array<out String>?) = projection?.any {
        it == TrackProviderConsts.COLUMN_TRACK_LYRICS || it == TrackProviderConsts.COLUMN_TRACK_LYRICS_SYNCED
    } == true

    /** Blocks the binder thread, as providers do; bounded so a dead server can't hang Poweramp's Lyrics view. */
    private fun lyricsFor(t: TrackRow): TrackLyrics? {
        val server = currentServer() ?: return null
        val result = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(LYRICS_TIMEOUT_MS) { app.lyrics.get(client(server), t.id, t.artist, t.title) }
        }
        Log.i(TAG, "Lyrics for ${t.id}: ${result?.let { if (it.isEmpty) "none" else if (it.synced != null) "synced" else "plain" } ?: "unavailable"}")
        return result
    }

    // ---- playlists ----

    /** The Playlists folder sits in the first library's root, and only exists when there are playlists. */
    private fun playlistsParent(): DocId.Library? {
        if (db.counts().playlists == 0) return null
        return db.libraryFolders().firstOrNull()?.let { DocId.Library(it.id) }
    }

    private fun playlistsLastModified(): Long = maxOf(
        db.folderLastModified(FolderKind.PLAYLISTS, FolderKind.PLAYLISTS_ID) ?: 0,
        db.playlistRows().maxOfOrNull { it.lastModified } ?: 0,
    )

    /** Writes the playlist as a cache file (Poweramp may seek in it, so not a pipe). */
    private fun writePlaylistFile(playlistId: String): File? {
        val playlist = db.playlistRow(playlistId) ?: return null
        val text = playlistText(playlist.name, db.playlistTracks(playlistId))
        val dir = File(context!!.cacheDir, "playlists").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-256").digest(playlistId.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$name${DocId.PLAYLIST_EXT}")
        val tmp = File.createTempFile(name, ".tmp", dir)
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Could not write playlist file")
        }
        return file
    }

    /**
     * Entries are Poweramp's own path for each library track, "@authority/<root doc id>/<track doc id>"
     * (URL-encoded), as it reports in track broadcasts. Found by probing on device (Poweramp build 1035):
     * it stores provider files under the root folder by document id, so name-based entries
     * ("Album/file.flac", "../Artist/Album/file.flac") never match, and document URIs play as
     * outside files (Poweramp downloads the whole file first).
     */
    private fun playlistText(name: String, tracks: List<TrackRow>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name.replace('\n', ' ')).append('\n')
        tracks.forEach { t ->
            val label = listOfNotNull(t.artist, t.title).joinToString(" - ").replace('\n', ' ')
            append("#EXTINF:").append(t.durationSec ?: -1).append(',').append(label).append('\n')
            append(playlistEntry(t)).append('\n')
        }
    }

    // ---- helpers ----

    private fun parentOf(doc: DocId): DocId? = when (doc) {
        is DocId.Library -> null
        is DocId.Artist -> DocId.Library(doc.libraryId)
        is DocId.Album -> db.albumFolder(doc.albumId)?.let { a ->
            a.albumArtistId?.let { DocId.Artist(a.libraryId, it) } ?: DocId.Library(a.libraryId)
        }
        is DocId.Track -> db.track(doc.trackId)?.let { t ->
            t.albumId?.let { DocId.Album(it) } ?: DocId.Library(t.libraryId)
        }
        DocId.Playlists -> playlistsParent()
        is DocId.Playlist -> DocId.Playlists
    }

    private fun currentServer(): SavedServer? = app.credentialStore.server.value ?: app.credentialStore.load()

    private fun client(server: SavedServer) = SubsonicClient(server.baseUrl, server.auth, app.httpClient)

    private fun notFound(id: String): Nothing = throw FileNotFoundException("No such document: $id")

    /** Logs each distinct column set a caller asks for (once), to learn which columns Poweramp reads. */
    private fun logProjection(method: String, projection: Array<out String>?) {
        val key = "$method:${projection?.joinToString(",")}"
        if (seenProjections.add(key)) Log.i(TAG, "$method asked for columns: ${projection?.joinToString() ?: "(default)"}")
    }

    private val seenProjections: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: FileNotFoundException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "Provider call failed", t)
        fallback
    }

    companion object {
        const val AUTHORITY = "dev.subsonic.poweramp.provider"
        private const val TAG = "SubsonicProvider"
        private const val MAX_DEPTH = 4
        private const val LYRICS_TIMEOUT_MS = 10_000L

        /**
         * When the provider last changed how it presents existing data (here: track file names built
         * from tags, tracks as files instead of URLs, playlist entries by doc id, 2026-11-12). Poweramp
         * only re-reads folders whose time changed, so no folder or track is reported as older than
         * this. Raise it whenever names, playlist contents or columns change.
         */
        const val PRESENTATION_CHANGED_AT = 1_794_492_000_000L

        fun shown(lastModified: Long) = maxOf(lastModified, PRESENTATION_CHANGED_AT)
        const val PLAYLISTS_FOLDER_NAME = "Playlists"
        private const val PLAYLIST_MIME = "audio/x-mpegurl"

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )

        /** Used when the caller passes no projection (e.g. the system file picker). */
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )

        /**
         * Poweramp's "By Track #" sort in album views uses this. For multi-disc albums it follows the
         * MediaStore convention (disc × 1000 + track) so disc 2 sorts after disc 1.
         */
        fun trackNumber(t: TrackRow): Int? {
            val track = t.trackNo ?: return null
            val disc = t.discNo
            return if (t.albumDiscCount > 1 && disc != null && disc > 0) disc * 1000 + track else track
        }

        /**
         * File-like name Poweramp shows in Folders and uses to match playlist entries (parent folder +
         * file name). Built from the tags ("01. Title.flac"): Navidrome's `path` is normally not the
         * real file but one it makes up ("Artist/Album/01-01 - Title.flac"). Its last segment is the
         * fallback when the track has no title.
         */
        fun displayName(t: TrackRow): String {
            val ext = t.suffix?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
            if (t.title.isNotBlank()) {
                val number = t.trackNo?.takeIf { it > 0 }?.let { "%02d. ".format(java.util.Locale.ROOT, it) } ?: ""
                return cleanName("$number${t.title.trim()}$ext")
            }
            val fromPath = t.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            return cleanName(fromPath ?: "")
        }

        /** Poweramp's path for a track of ours: "@authority/lib%3A1/track%3A<id>". */
        fun playlistEntry(t: TrackRow): String =
            "@$AUTHORITY/" + Uri.encode(DocId.Library(t.libraryId).encoded) + "/" + Uri.encode(DocId.Track(t.id).encoded)

        /** '/' would break Poweramp's path-style matching. */
        fun cleanName(name: String) = name.replace('/', '∕').ifBlank { "Unknown" }
    }
}
