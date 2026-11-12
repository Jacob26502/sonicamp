package dev.subsonic.poweramp.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
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
import dev.subsonic.poweramp.library.TrackRow
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
                is DocId.Track -> addTrack(c, db.track(doc.trackId) ?: notFound(documentId), position = null)
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
                    db.artistFolders(doc.libraryId).forEach {
                        addFolder(c, DocId.Artist(it.libraryId, it.id), it.name, it.lastModified, TrackProviderConsts.FLAG_HAS_SUBDIRS, thumbnail = false)
                    }
                    db.albumFoldersWithoutArtist(doc.libraryId).forEach { addAlbum(c, it) }
                    db.tracksWithoutAlbum(doc.libraryId).forEachIndexed { i, t -> addTrack(c, t, position = i + 1) }
                }
                is DocId.Artist -> db.albumFolders(doc.libraryId, doc.artistId).forEach { addAlbum(c, it) }
                is DocId.Album -> db.tracksOfAlbum(doc.albumId).forEachIndexed { i, t -> addTrack(c, t, position = i + 1) }
                is DocId.Track, null -> notFound(parentDocumentId)
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
        // Poweramp plays our tracks from their URL and never calls this. Byte-level access for other
        // apps (and offline caching) comes with phase 5.
        throw FileNotFoundException("$documentId is streamed by URL; play it in Poweramp")
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
            add(Document.COLUMN_LAST_MODIFIED, lastModified)
            add(Document.COLUMN_FLAGS, if (thumbnail) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
            // Lets Poweramp's folder picker skip probing for sub-folders.
            add(TrackProviderConsts.COLUMN_FLAGS, subdirs)
        }
    }

    private fun addAlbum(c: MatrixCursor, album: AlbumFolder) = addFolder(
        c, DocId.Album(album.id), album.name, album.lastModified,
        TrackProviderConsts.FLAG_NO_SUBDIRS, thumbnail = album.coverArt != null,
    )

    /** @param position 1-based order within its folder, for Poweramp's "By Track #" folder sorting. */
    private fun addTrack(c: MatrixCursor, t: TrackRow, position: Int?) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, DocId.Track(t.id).encoded)
            add(Document.COLUMN_MIME_TYPE, t.contentType ?: t.suffix?.let { "audio/$it" } ?: "audio/*")
            add(Document.COLUMN_DISPLAY_NAME, displayName(t))
            add(Document.COLUMN_LAST_MODIFIED, t.lastModified)
            add(Document.COLUMN_SIZE, t.sizeBytes)
            add(Document.COLUMN_FLAGS, if (t.coverArt != null) Document.FLAG_SUPPORTS_THUMBNAIL else 0)

            // Having TITLE/DURATION tells Poweramp to trust these tags and not read the file.
            add(TrackProviderConsts.COLUMN_URL, TrackProviderConsts.DYNAMIC_URL)
            add(MediaStore.MediaColumns.TITLE, t.title)
            // Milliseconds; > 0 makes it a seekable remote file rather than a live stream.
            add(MediaStore.Audio.AudioColumns.DURATION, (t.durationSec ?: 0) * 1000L)
            add(MediaStore.Audio.AudioColumns.ARTIST, t.artist)
            add(MediaStore.Audio.AudioColumns.ALBUM, t.album)
            add(MediaStore.Audio.AudioColumns.YEAR, t.year)
            add(TrackProviderConsts.COLUMN_ALBUM_ARTIST, t.albumArtist)
            add(MediaStore.Audio.AudioColumns.COMPOSER, t.composer)
            add(TrackProviderConsts.COLUMN_GENRE, t.genre)
            add(MediaStore.Audio.AudioColumns.TRACK, trackNumber(t))
            // Not in Poweramp's documented provider columns; standard MediaStore name, sent in case it's read.
            add(MediaStore.Audio.AudioColumns.DISC_NUMBER, t.discNo)
            if (position != null) add(TrackProviderConsts.COLUMN_TRACK_ALT, position)
            add(MediaFormat.KEY_SAMPLE_RATE, t.sampleRate)
            add(MediaFormat.KEY_CHANNEL_COUNT, t.channels)
            add(MediaFormat.KEY_BIT_RATE, t.bitRate?.let { it * 1000 }) // server reports kbps
            add(TrackProviderConsts.COLUMN_BITS_PER_SAMPLE, t.bitDepth)
            // Empty wave: use Poweramp's default seekbar instead of downloading every track to scan it.
            add(TrackProviderConsts.COLUMN_TRACK_WAVE, ByteArray(0))
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
         * File-like name Poweramp shows with "Show file names" and uses to match playlist entries
         * (parent folder + file name). Navidrome's own file name is stable and has the extension.
         */
        fun displayName(t: TrackRow): String {
            val fromPath = t.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            if (fromPath != null) return cleanName(fromPath)
            val number = t.trackNo?.let { "%02d - ".format(it) } ?: ""
            val ext = t.suffix?.let { ".$it" } ?: ""
            return cleanName("$number${t.title}$ext")
        }

        /** '/' would break Poweramp's path-style matching. */
        fun cleanName(name: String) = name.replace('/', '∕').ifBlank { "Unknown" }
    }
}
