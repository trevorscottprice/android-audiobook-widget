package com.trevorprice.audiobookwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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

class AudiobookWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetManager.updateAppWidget(appWidgetIds, buildViews(context))
    }

    override fun onEnabled(context: Context) {
        SessionListenerService.requestRebind(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY_PAUSE, ACTION_REWIND, ACTION_FORWARD -> {
                handleTransport(context, intent.action!!)
                // Nudge the widget right away; the session callback corrects it
                // a moment later once the player has actually reacted.
                updateAll(context)
            }
        }
        super.onReceive(context, intent)
    }

    private fun handleTransport(context: Context, action: String) {
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

    companion object {
        const val ACTION_PLAY_PAUSE = "com.trevorprice.audiobookwidget.PLAY_PAUSE"
        const val ACTION_REWIND = "com.trevorprice.audiobookwidget.REWIND"
        const val ACTION_FORWARD = "com.trevorprice.audiobookwidget.FORWARD"

        /** Cover art travels to the launcher over IPC, so keep it small. */
        private const val ART_PX = 256

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AudiobookWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, buildViews(context))
        }

        fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            val skip = MediaSessions.skipSeconds(context)
            views.setTextViewText(R.id.rewind_label, skip.toString())
            views.setTextViewText(R.id.forward_label, skip.toString())

            if (!MediaSessions.isListenerEnabled(context)) {
                renderSetupNeeded(context, views)
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
            // Some players put an internal chapter id in DISPLAY_SUBTITLE and
            // the publisher in ARTIST, so prefer the publisher and keep the
            // id only as a last resort.
            views.setTextViewText(
                R.id.subtitle,
                metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?: context.getString(R.string.open_player_hint)
            )

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

            views.setImageViewResource(
                R.id.play_pause,
                if (MediaSessions.isPlaying(state)) R.drawable.ic_pause else R.drawable.ic_play
            )

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

            views.setOnClickPendingIntent(R.id.rewind_btn, broadcast(context, ACTION_REWIND))
            views.setOnClickPendingIntent(R.id.play_pause_btn, broadcast(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.forward_btn, broadcast(context, ACTION_FORWARD))
            views.setOnClickPendingIntent(R.id.cover, openPlayer(context))
            views.setOnClickPendingIntent(R.id.text_block, openPlayer(context))
            return views
        }

        private fun renderSetupNeeded(context: Context, views: RemoteViews) {
            views.setTextViewText(R.id.title, context.getString(R.string.setup_needed_title))
            views.setTextViewText(R.id.subtitle, context.getString(R.string.setup_needed_body))
            views.setImageViewResource(R.id.cover, R.drawable.ic_cover_placeholder)
            views.setImageViewResource(R.id.play_pause, R.drawable.ic_play)
            views.setProgressBar(R.id.progress, 1000, 0, false)
            views.setTextViewText(R.id.time_left, "")
            val settings = openSettings(context)
            for (id in intArrayOf(
                R.id.cover, R.id.text_block, R.id.rewind_btn,
                R.id.play_pause_btn, R.id.forward_btn
            )) {
                views.setOnClickPendingIntent(id, settings)
            }
        }

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
}
