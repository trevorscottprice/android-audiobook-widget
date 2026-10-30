package com.trevorprice.audiobookwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * A size that only draws. Button taps are broadcast to
 * [AudiobookWidgetProvider], which handles transport for every size.
 */
abstract class DrawOnlyWidgetProvider(private val size: WidgetRender.Size) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetManager.updateAppWidget(appWidgetIds, WidgetRender.build(context, size))
    }

    override fun onEnabled(context: Context) {
        SessionListenerService.requestRebind(context)
    }
}

/** The 4x1 widget: book info, controls and the clock, no cover art. */
class CompactWidgetProvider : DrawOnlyWidgetProvider(WidgetRender.Size.COMPACT)

/** 2x2: title, controls and clock. */
class SquareWidgetProvider : DrawOnlyWidgetProvider(WidgetRender.Size.SQUARE)

/** 2x2 cover tile: the plain 2x2 laid over the cover art. */
class SquareCoverWidgetProvider : DrawOnlyWidgetProvider(WidgetRender.Size.SQUARE_COVER)
