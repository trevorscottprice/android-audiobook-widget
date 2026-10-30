package com.trevorprice.audiobookwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** The 4x2 widget: cover art, titles, controls, progress and time remaining. */
class AudiobookWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetManager.updateAppWidget(
            appWidgetIds, WidgetRender.build(context, WidgetRender.Size.FULL)
        )
    }

    override fun onEnabled(context: Context) {
        SessionListenerService.requestRebind(context)
    }

    /**
     * Every size aims its buttons here, so this handles transport for all of
     * them and then repaints every placed widget.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val button = when (intent.action) {
            WidgetRender.ACTION_PLAY_PAUSE -> Feedback.Button.PLAY_PAUSE
            WidgetRender.ACTION_REWIND -> Feedback.Button.REWIND
            WidgetRender.ACTION_FORWARD -> Feedback.Button.FORWARD
            else -> null
        }
        if (button != null) {
            Haptics.tap(context)
            // Held open so the tint can come back down and the player's
            // answer can be checked after the receiver would normally return.
            val pending = goAsync()
            // The widget may have been drawn hours ago, while the player
            // still had a session it has since lost to being killed. So
            // decide here, at tap time, rather than trusting the drawing.
            if (button == Feedback.Button.PLAY_PAUSE && MediaSessions.controller(context) == null) {
                resume(context, pending)
            } else {
                transport(context, intent.action!!, button, pending)
            }
        }
        super.onReceive(context, intent)
    }

    private fun transport(
        context: Context,
        action: String,
        button: Feedback.Button,
        pending: BroadcastReceiver.PendingResult
    ) {
        if (!WidgetRender.handleTransport(context, action)) {
            // The session went away, or came back empty, since the drawing.
            fail(
                context, button,
                if (MediaSessions.controller(context) == null) R.string.player_not_running
                else R.string.player_idle
            )
            settle(context, pending)
            return
        }
        // Draw what we asked for straight away; the session callback
        // corrects it once the player has actually reacted.
        Feedback.flash(button, ok = true)
        if (button != Feedback.Button.PLAY_PAUSE) Feedback.skipped(button)
        WidgetRender.updateAll(context)
        if (button != Feedback.Button.PLAY_PAUSE) {
            settle(context, pending)
            return
        }
        // Play and pause always change the session's state, so a player that
        // has not moved by now did not hear us.
        handler.postDelayed({
            val state = MediaSessions.controller(context)?.playbackState
            if (Feedback.playUnanswered(MediaSessions.isActive(state))) {
                fail(context, button, R.string.player_no_response)
            }
            settle(context, pending)
        }, Feedback.EXPECT_MS)
    }

    private fun resume(context: Context, pending: BroadcastReceiver.PendingResult) {
        Feedback.resuming = true
        WidgetRender.updateAll(context)
        // Waking the player takes a few seconds of binding, longer
        // than onReceive may block, hence the held broadcast.
        MediaSessions.resume(context) {
            Feedback.resuming = false
            if (MediaSessions.controller(context) != null) {
                Feedback.flash(Feedback.Button.PLAY_PAUSE, ok = true)
            } else {
                fail(context, Feedback.Button.PLAY_PAUSE, R.string.player_wont_start)
            }
            settle(context, pending)
        }
    }

    private fun fail(context: Context, button: Feedback.Button, message: Int) {
        Feedback.flash(button, ok = false)
        Feedback.message(context.getString(message, MediaSessions.playerLabel(context)))
        Haptics.error(context)
    }

    /**
     * Paints the current tint, then clears it, and any error message after
     * it, before letting the broadcast go.
     */
    private fun settle(context: Context, pending: BroadcastReceiver.PendingResult) {
        WidgetRender.updateAll(context)
        handler.postDelayed({ WidgetRender.updateAll(context) }, Feedback.FLASH_MS)
        val glowing = Feedback.glowing()
        if (glowing) handler.postDelayed({ WidgetRender.updateAll(context) }, Feedback.GLOW_MS)
        val hold = maxOf(
            if (Feedback.message() != null) Feedback.MESSAGE_MS else Feedback.FLASH_MS,
            if (glowing) Feedback.GLOW_MS else 0L
        )
        handler.postDelayed({
            WidgetRender.updateAll(context)
            pending.finish()
        }, hold)
    }

    private companion object {
        val handler = Handler(Looper.getMainLooper())
    }
}
