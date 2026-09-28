package com.trevorprice.audiobookwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The last book the player told us about, kept on disk.
 *
 * Players drop their media session once they have been idle for a while or
 * the system kills them, and with no session there is nothing to read. This
 * lets the widget keep showing the book until the player comes back.
 */
object LastPlayed {

    private const val PREFS = "audiobook_widget_last"
    private const val KEY_PKG = "pkg"
    private const val KEY_TITLE = "title"
    private const val KEY_SUBTITLE = "subtitle"
    private const val KEY_DURATION = "duration"
    private const val KEY_POSITION = "position"
    private const val KEY_SPEED = "speed"
    private const val KEY_ART_TITLE = "art_title"
    private const val ART_FILE = "last_cover.png"

    class Snapshot(
        val title: String,
        val subtitle: String?,
        val art: Bitmap?,
        val durationMs: Long,
        val positionMs: Long
    )

    /**
     * Records what a live session is showing. Called on every repaint, so the
     * cover is only rewritten when it belongs to a different book than the one
     * already on disk.
     */
    fun save(ctx: Context, pkg: String, book: Snapshot, playingSpeed: Float?) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString(KEY_PKG, pkg)
            .putString(KEY_TITLE, book.title)
            .putString(KEY_SUBTITLE, book.subtitle)
            .putLong(KEY_DURATION, book.durationMs)
            .putLong(KEY_POSITION, book.positionMs)
        // A paused session reports a speed of 0, so only a playing one tells
        // us what the listener actually chose.
        if (playingSpeed != null && playingSpeed > 0f) editor.putFloat(KEY_SPEED, playingSpeed)

        val art = book.art
        val artFile = File(ctx.filesDir, ART_FILE)
        if (art != null && (prefs.getString(KEY_ART_TITLE, null) != book.title || !artFile.exists())) {
            try {
                artFile.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
                editor.putString(KEY_ART_TITLE, book.title)
            } catch (e: Exception) {
                // Losing the cover is fine; the text is what matters.
            }
        }
        editor.apply()
    }

    /**
     * The speed the listener was last playing at, or null if we never saw one.
     * Some players (Chirp, at least) come back at 1.0x when resumed from
     * outside the app, so this is put back after a resume.
     */
    fun speed(ctx: Context): Float? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_SPEED)) prefs.getFloat(KEY_SPEED, 1f) else null
    }

    /** The saved book, or null if there is none or it came from another player. */
    fun load(ctx: Context, forPkg: String?): Snapshot? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString(KEY_TITLE, null) ?: return null
        if (forPkg != null && prefs.getString(KEY_PKG, null) != forPkg) return null

        val art = if (prefs.getString(KEY_ART_TITLE, null) == title) {
            BitmapFactory.decodeFile(File(ctx.filesDir, ART_FILE).path)
        } else {
            null
        }
        return Snapshot(
            title = title,
            subtitle = prefs.getString(KEY_SUBTITLE, null),
            art = art,
            durationMs = prefs.getLong(KEY_DURATION, 0L),
            positionMs = prefs.getLong(KEY_POSITION, 0L)
        )
    }
}
