package io.github.ramziag.weather

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * The home-screen widget. The system calls [onUpdate] when a widget is placed, every 30 minutes
 * (updatePeriodMillis), after a reboot and after an app update: we redraw at once from the cached forecast,
 * then let [WidgetRefreshJob] fetch fresh data if it's stale.
 */
class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Widgets.render(context, appWidgetIds)
        Widgets.refreshIfStale(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Android 12+ switches layouts itself as the widget is resized; Android 11 needs a redraw.
        if (Build.VERSION.SDK_INT < 31) Widgets.render(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = Widgets.forget(context, appWidgetIds)

    override fun onDisabled(context: Context) = Widgets.cancelRefresh(context)

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) =
        Widgets.restore(context, oldWidgetIds, newWidgetIds)
}
