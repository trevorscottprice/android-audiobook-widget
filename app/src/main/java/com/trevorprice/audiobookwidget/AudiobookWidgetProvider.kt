package com.trevorprice.audiobookwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

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
     * Both sizes aim their buttons here, so this handles transport for all of
     * them and then repaints every placed widget.
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetRender.ACTION_PLAY_PAUSE,
            WidgetRender.ACTION_REWIND,
            WidgetRender.ACTION_FORWARD -> {
                WidgetRender.handleTransport(context, intent.action!!)
                // Nudge the widgets right away; the session callback corrects
                // them a moment later once the player has actually reacted.
                WidgetRender.updateAll(context)
            }
        }
        super.onReceive(context, intent)
    }
}
