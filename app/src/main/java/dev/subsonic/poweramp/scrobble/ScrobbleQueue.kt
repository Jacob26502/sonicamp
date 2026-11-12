package dev.subsonic.poweramp.scrobble

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plays waiting to be sent to the server. Kept apart from the library database, which is a cache that
 * may be dropped and rebuilt; these are the user's listening history and must survive until sent.
 *
 * @param name null for an in-memory database (tests).
 */
class ScrobbleQueue(context: Context, name: String? = "scrobbles.db") : SQLiteOpenHelper(context, name, null, 1) {

    data class Item(val id: Long, val server: String, val songId: String, val playedAt: Long, val attempts: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                server TEXT NOT NULL,
                song_id TEXT NOT NULL,
                played_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** @param server identity of the server the song belongs to; plays are never sent to another one. */
    fun add(server: String, songId: String, playedAt: Long) {
        writableDatabase.insertOrThrow("queue", null, ContentValues().apply {
            put("server", server)
            put("song_id", songId)
            put("played_at", playedAt)
        })
    }

    /** Oldest first: servers expect submissions in listening order. */
    fun pending(limit: Int = 200): List<Item> =
        readableDatabase.rawQuery(
            "SELECT id, server, song_id, played_at, attempts FROM queue ORDER BY played_at, id LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Item(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))) }
        }

    fun remove(id: Long) {
        writableDatabase.delete("queue", "id = ?", arrayOf(id.toString()))
    }

    fun markAttempt(id: Long) {
        writableDatabase.execSQL("UPDATE queue SET attempts = attempts + 1 WHERE id = ?", arrayOf(id))
    }

    /** Plays for any server other than [server] (e.g. after switching servers) can never be sent. */
    fun removeOtherServers(server: String?) {
        if (server == null) writableDatabase.delete("queue", null, null)
        else writableDatabase.delete("queue", "server != ?", arrayOf(server))
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use { it.moveToFirst(); it.getInt(0) }

    fun clear() {
        writableDatabase.delete("queue", null, null)
    }
}
