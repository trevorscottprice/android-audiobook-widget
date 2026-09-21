package com.trevorprice.audiobookwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.widget.RemoteViews
import java.util.Locale

/**
 * Rendering and transport handling shared by every widget size.
 *
 * Each size differs only in its layout and which optional pieces that layout
 * contains, described by [Size]. RemoteViews throws if you address a view id
 * the layout does not have, so the flags are load-bearing, not cosmetic.
 */
object WidgetRender {

    const val ACTION_PLAY_PAUSE = "com.trevorprice.audiobookwidget.PLAY_PAUSE"
    const val ACTION_REWIND = "com.trevorprice.audiobookwidget.REWIND"
    const val ACTION_FORWARD = "com.trevorprice.audiobookwidget.FORWARD"

    /** Cover art travels to the launcher over IPC, so keep it small. */
    private const val ART_PX = 256

    enum class Size(
        val layout: Int,
        val provider: Class<*>,
        val hasCover: Boolean,
        val hasProgress: Boolean
    ) {
        /** 4x2: cover art, titles, controls, progress and time remaining. */
        FULL(R.layout.widget_player, AudiobookWidgetProvider::class.java, true, true),

        /** 4x1: titles and controls only. */
        COMPACT(R.layout.widget_compact, CompactWidgetProvider::class.java, false, false)
    }

    /** Repaints every placed instance of every size. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        for (size in Size.entries) {
            val ids = manager.getAppWidgetIds(ComponentName(context, size.provider))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(context, size))
        }
    }

    fun build(context: Context, size: Size): RemoteViews {
        val views = RemoteViews(context.packageName, size.layout)
        val skip = MediaSessions.skipSeconds(context)
        views.setTextViewText(R.id.rewind_label, skip.toString())
        views.setTextViewText(R.id.forward_label, skip.toString())

        if (!MediaSessions.isListenerEnabled(context)) {
            renderSetupNeeded(context, views, size)
            return views
        }

        val controller = MediaSessions.controller(context)
        val metadata = controller?.metadata
        val state = controller?.playbackState

        views.setTextViewText(
            R.id.title,
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: context.getString(R.string.nothing_playing)
        )
        // Some players put an internal chapter id in DISPLAY_SUBTITLE and the
        // publisher in ARTIST, so prefer the publisher and keep the id only as
        // a last resort.
        views.setTextViewText(
            R.id.subtitle,
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: context.getString(R.string.open_player_hint)
        )

        if (size.hasCover) {
            val art = metadata?.let {
                it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
            if (art != null) {
                views.setImageViewBitmap(R.id.cover, prepareArt(art))
            } else {
                views.setImageViewResource(R.id.cover, R.drawable.ic_cover_placeholder)
            }
        }

        views.setImageViewResource(
            R.id.play_pause,
            if (MediaSessions.isPlaying(state)) R.drawable.ic_pause else R.drawable.ic_play
        )

        if (size.hasProgress) {
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = MediaSessions.currentPosition(state)
            if (duration > 0) {
                val pct = ((position.toDouble() / duration) * 1000).toInt().coerceIn(0, 1000)
                views.setProgressBar(R.id.progress, 1000, pct, false)
                views.setTextViewText(
                    R.id.time_left,
                    context.getString(R.string.time_left, formatDuration(duration - position))
                )
            } else {
                views.setProgressBar(R.id.progress, 1000, 0, false)
                views.setTextViewText(R.id.time_left, "")
            }
        }

        views.setOnClickPendingIntent(R.id.rewind_btn, broadcast(context, ACTION_REWIND))
        views.setOnClickPendingIntent(R.id.play_pause_btn, broadcast(context, ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.forward_btn, broadcast(context, ACTION_FORWARD))
        views.setOnClickPendingIntent(R.id.text_block, openPlayer(context))
        if (size.hasCover) views.setOnClickPendingIntent(R.id.cover, openPlayer(context))
        return views
    }

    private fun renderSetupNeeded(context: Context, views: RemoteViews, size: Size) {
        views.setTextViewText(R.id.title, context.getString(R.string.setup_needed_title))
        views.setTextViewText(R.id.subtitle, context.getString(R.string.setup_needed_body))
        views.setImageViewResource(R.id.play_pause, R.drawable.ic_play)
        if (size.hasCover) views.setImageViewResource(R.id.cover, R.drawable.ic_cover_placeholder)
        if (size.hasProgress) {
            views.setProgressBar(R.id.progress, 1000, 0, false)
            views.setTextViewText(R.id.time_left, "")
        }
        val settings = openSettings(context)
        views.setOnClickPendingIntent(R.id.text_block, settings)
        views.setOnClickPendingIntent(R.id.rewind_btn, settings)
        views.setOnClickPendingIntent(R.id.play_pause_btn, settings)
        views.setOnClickPendingIntent(R.id.forward_btn, settings)
        if (size.hasCover) views.setOnClickPendingIntent(R.id.cover, settings)
    }

    // ---- transport ---------------------------------------------------------

    fun handleTransport(context: Context, action: String) {
        val controller = MediaSessions.controller(context) ?: return
        val transport = controller.transportControls
        val state = controller.playbackState
        val available = state?.actions ?: 0L
        val skipMs = MediaSessions.skipSeconds(context) * 1000L

        when (action) {
            ACTION_PLAY_PAUSE ->
                if (MediaSessions.isPlaying(state)) transport.pause() else transport.play()

            ACTION_REWIND -> seekBy(controller, -skipMs, available) { transport.rewind() }
            ACTION_FORWARD -> seekBy(controller, skipMs, available) { transport.fastForward() }
        }
    }

    /**
     * Prefer an explicit seek so the jump is exactly the configured amount.
     * A player may define its own rewind as a different interval, so that is
     * only the fallback for sessions that do not support seeking.
     */
    private inline fun seekBy(
        controller: MediaController,
        deltaMs: Long,
        available: Long,
        fallback: () -> Unit
    ) {
        val state = controller.playbackState
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (available and PlaybackState.ACTION_SEEK_TO != 0L && state != null) {
            var target = MediaSessions.currentPosition(state) + deltaMs
            if (target < 0) target = 0
            if (duration > 0 && target > duration) target = duration
            controller.transportControls.seekTo(target)
        } else {
            fallback()
        }
    }

    // ---- intents -----------------------------------------------------------

    /**
     * Always aimed at [AudiobookWidgetProvider]: a receiver is live whether or
     * not an instance of its size is on screen, and the handling is identical,
     * so both sizes can share one set of pending intents.
     */
    private fun broadcast(context: Context, action: String): PendingIntent {
        val intent = Intent(context, AudiobookWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openSettings(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openPlayer(context: Context): PendingIntent {
        val pkg = MediaSessions.targetPackage(context)
        val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: return openSettings(context)
        return PendingIntent.getActivity(
            context, 1, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- helpers -----------------------------------------------------------

    /** Scale down and round the corners so the art matches the widget shape. */
    private fun prepareArt(source: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(source, ART_PX, ART_PX, true)
        val out = Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = ART_PX * 0.09f
        canvas.drawRoundRect(
            RectF(0f, 0f, ART_PX.toFloat(), ART_PX.toFloat()), radius, radius, paint
        )
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return out
    }

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
