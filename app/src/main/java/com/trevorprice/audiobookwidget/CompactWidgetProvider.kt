package com.trevorprice.audiobookwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * The 4x1 widget: book info and controls only, no cover art and no progress
 * bar. Button taps are broadcast to [AudiobookWidgetProvider], which handles
 * transport for every size.
 */
class CompactWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetManager.updateAppWidget(
            appWidgetIds, WidgetRender.build(context, WidgetRender.Size.COMPACT)
        )
    }

    override fun onEnabled(context: Context) {
        SessionListenerService.requestRebind(context)
    }
}
