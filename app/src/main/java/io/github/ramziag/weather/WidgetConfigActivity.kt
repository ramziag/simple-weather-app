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
        val chosen = Widgets.chosenPlace(this, id)
        // null = follow the hometown. A widget can be set to a city that has since become the hometown; keep
        // that choice on the list so it shows as picked.
        val options: List<Place?> = listOf(null) + cities + listOfNotNull(chosen?.takeIf { it !in cities })
        val labels = options.map { p ->
            when {
                p == null -> getString(R.string.widget_follow_home) + (home?.let { " · ${it.name}" } ?: "")
                p.area.isEmpty() -> p.name
                else -> "${p.name}, ${p.area.substringBefore(',')}"
            }
        }.toTypedArray()
        val current = options.indexOf(chosen)

        val themed = ContextThemeWrapper(this, Themes.style(store.theme, resources.configuration))
        AlertDialog.Builder(themed)
            .setTitle(R.string.widget_pick)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Widgets.choose(this, id, options[which])
                mgr.updateAppWidget(id, Widgets.build(this, mgr, id))
                Widgets.refreshIfStale(this, intArrayOf(id))
                setResult(RESULT_OK, result)
                dialog.dismiss()
            }
            .setOnDismissListener { finish() }
            .show()
    }
}
