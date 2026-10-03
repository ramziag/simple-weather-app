package io.github.ramziag.weather

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper

/**
 * Picks the city a widget shows: the hometown (default) or one of the saved cities. Shown when the widget is
 * placed on Android 11, and from the widget's "reconfigure" (touch & hold) option on Android 12+.
 */
class WidgetConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        val mgr = AppWidgetManager.getInstance(this)
        // Only configure our own widgets (the activity is exported so any launcher can start it).
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID ||
            mgr.getAppWidgetInfo(id)?.provider != ComponentName(this, WeatherWidget::class.java)
        ) {
            finish()
            return
        }

        val store = Store.get(this)
        val home = store.home
        val cities = store.cities
        val homeLabel = getString(R.string.widget_follow_home) + (home?.let { " · ${it.name}" } ?: "")
        val labels = arrayOf(homeLabel) + cities.map { if (it.area.isEmpty()) it.name else "${it.name}, ${it.area.substringBefore(',')}" }
        val current = Widgets.chosenPlace(this, id)?.let { cities.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0

        val themed = ContextThemeWrapper(this, Themes.style(store.theme, resources.configuration))
        AlertDialog.Builder(themed)
            .setTitle(R.string.widget_pick)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Widgets.choose(this, id, if (which == 0) null else cities[which - 1])
                mgr.updateAppWidget(id, Widgets.build(this, mgr, id))
                Widgets.refreshIfStale(this, intArrayOf(id))
                setResult(RESULT_OK, result)
                dialog.dismiss()
            }
            .setOnDismissListener { finish() }
            .show()
    }
}
