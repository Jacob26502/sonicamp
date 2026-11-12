package dev.subsonic.poweramp.lyrics

import dev.subsonic.poweramp.subsonic.StructuredLyrics
import kotlinx.serialization.Serializable
import java.util.Locale

/** Lyrics in the two forms Poweramp's provider columns take. Both null means "the song has none". */
@Serializable
data class TrackLyrics(
    /** LRC (`[mm:ss.xx]line`), for TrackProviderConsts.COLUMN_TRACK_LYRICS_SYNCED. */
    val synced: String? = null,
    /** Plain text, for TrackProviderConsts.COLUMN_TRACK_LYRICS. */
    val plain: String? = null,
) {
    val isEmpty get() = synced == null && plain == null

    companion object {
        val NONE = TrackLyrics()
    }
}

object LyricsFormat {

    /**
     * Picks one set of lyrics (synced first, then the device language, then the first) and converts
     * it. Servers may return one entry per language, or the same lyrics both synced and unsynced.
     */
    fun fromStructured(all: List<StructuredLyrics>, language: String = Locale.getDefault().language): TrackLyrics {
        val usable = all.filter { l -> l.line.any { it.value.isNotBlank() } }
        if (usable.isEmpty()) return TrackLyrics.NONE
        fun inLanguage(l: StructuredLyrics) = l.lang?.lowercase()?.startsWith(language.lowercase()) == true
        val synced = usable.filter { it.synced && it.line.any { l -> l.start != null } }
        val chosenSynced = synced.firstOrNull(::inLanguage) ?: synced.firstOrNull()
        val chosenPlain = chosenSynced ?: usable.firstOrNull(::inLanguage) ?: usable.first()
        return TrackLyrics(
            synced = chosenSynced?.let(::toLrc),
            plain = toPlain(chosenPlain),
        )
    }

    fun fromPlain(text: String?): TrackLyrics =
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { TrackLyrics(plain = it) } ?: TrackLyrics.NONE

    /** Applies the server's offset; lines without a time keep the previous line's time. */
    fun toLrc(lyrics: StructuredLyrics): String {
        var lastMs = 0L
        return lyrics.line.joinToString("\n") { line ->
            val ms = line.start?.let { (it - lyrics.offset).coerceAtLeast(0) } ?: lastMs
            lastMs = ms
            "[${timestamp(ms)}]${line.value}"
        }
    }

    fun toPlain(lyrics: StructuredLyrics): String = lyrics.line.joinToString("\n") { it.value }.trim()

    /** mm:ss.xx (hundredths), the most widely understood LRC form; minutes can exceed 59. */
    fun timestamp(ms: Long): String {
        val totalCs = ms / 10
        val minutes = totalCs / 6000
        val seconds = (totalCs / 100) % 60
        val cs = totalCs % 100
        return "%02d:%02d.%02d".format(Locale.ROOT, minutes, seconds, cs)
    }
}
