package io.github.ramziag.weather

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.util.SizeF
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.RemoteViews
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Home-screen widget: drawing, which city each widget shows, and background refresh.
 *
 * Widgets are drawn by the launcher from RemoteViews, so the layouts use only RemoteViews-safe views and no
 * app theme attributes. Their colours default to the Auto theme through @color resources (with values-night);
 * for any other theme the colours are pushed from here.
 */
object Widgets {
    const val EXTRA_PLACE = "io.github.ramziag.weather.extra.PLACE"

    /** Refresh when cached data is older than this; the system's widget update tick comes every 30 min. */
    const val MAX_AGE_MS = 25 * 60 * 1000L

    private const val PREFS = "widgets"
    private const val JOB_REFRESH = 7301

    /**
     * Layout steps in dp. On Android 12+ the launcher picks the closest step that fits, so the steps form a
     * full width x height lattice; Android 11 gets the same choice from [sizeFor].
     */
    enum class Size(val width: Int, val height: Int) {
        COMPACT(110, 50), STRIP(250, 50), SQUARE(110, 200), WIDE(250, 200), TALL(110, 380), LARGE(250, 380)
    }

    fun sizeFor(widthDp: Int, heightDp: Int): Size {
        val wide = widthDp >= 250
        return when {
            heightDp >= 380 -> if (wide) Size.LARGE else Size.TALL
            heightDp >= 200 -> if (wide) Size.WIDE else Size.SQUARE
            else -> if (wide) Size.STRIP else Size.COMPACT
        }
    }

    private val io = Executors.newSingleThreadExecutor()

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, WeatherWidget::class.java))

    /** Called by the app whenever forecasts or settings change: redraws every widget off the main thread. */
    fun update(context: Context) {
        val app = context.applicationContext
        io.execute {
            val ids = runCatching { ids(app) }.getOrDefault(IntArray(0))
            if (ids.isNotEmpty()) {
                render(app, ids)
                refreshIfStale(app, ids)
            }
        }
    }

    /** Draws [ids] from the forecast cache. Blocking but quick (no network); safe on any thread. */
    fun render(context: Context, ids: IntArray) {
        val mgr = AppWidgetManager.getInstance(context)
        val cache = HashMap<String, Forecast?>()
        for (id in ids) runCatching { mgr.updateAppWidget(id, build(context, mgr, id, cache)) }
    }

    fun build(context: Context, mgr: AppWidgetManager, id: Int, cache: MutableMap<String, Forecast?> = HashMap()): RemoteViews {
        val store = Store.get(context)
        val chosen = chosenPlace(context, id)
        val place = chosen ?: store.home
        val open = openApp(context, id, chosen)
        val palette = Palette.of(context, store.theme)
        if (place == null) return message(context, R.string.widget_no_home, palette, open)
        val forecast = cache.getOrPut(place.key) { ForecastFiles.read(ForecastFiles.file(context, place)) }
            ?: return message(context, R.string.widget_loading, palette, open)
        val fmt = Fmt(store.imperial, DateFormat.is24HourFormat(context))
        if (Build.VERSION.SDK_INT >= 31) {
            // One RemoteViews per step (each must be its own instance); the launcher picks as it resizes.
            return RemoteViews(
                Size.entries.associate { SizeF(it.width.toFloat(), it.height.toFloat()) to views(context, it, place, forecast, fmt, palette, open) },
            )
        }
        val options = mgr.getAppWidgetOptions(id)
        val size = sizeFor(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
        return views(context, size, place, forecast, fmt, palette, open)
    }

    /** One layout step, for tests and screenshots. */
    internal fun preview(context: Context, size: Size, place: Place, forecast: Forecast): RemoteViews {
        val store = Store.get(context)
        val fmt = Fmt(store.imperial, DateFormat.is24HourFormat(context))
        return views(context, size, place, forecast, fmt, Palette.of(context, store.theme), openApp(context, 0, null))
    }

    private fun views(
        context: Context,
        size: Size,
        place: Place,
        f: Forecast,
        fmt: Fmt,
        palette: Palette?,
        open: PendingIntent,
    ): RemoteViews {
        val layout = when (size) {
            Size.COMPACT, Size.STRIP -> R.layout.widget_compact
            Size.SQUARE, Size.TALL -> R.layout.widget_narrow
            Size.WIDE, Size.LARGE -> R.layout.widget_full
        }
        val v = RemoteViews(context.packageName, layout)
        val primary = mutableListOf(R.id.name, R.id.temp)
        val secondary = mutableListOf(R.id.hilo)
        val icons = mutableListOf(R.id.icon)

        val c = f.current
        val today = f.today()
        v.setTextViewText(R.id.name, place.name)
        v.setTextViewText(R.id.temp, fmt.temp(c.temp))
        v.setImageViewResource(R.id.icon, Wmo.widgetIcon(c.code, c.isDay))
        v.setContentDescription(R.id.icon, Wmo.label(c.code))
        v.setTextViewText(R.id.hilo, "H ${fmt.temp(today?.max)}  L ${fmt.temp(today?.min)}")
        if (layout != R.layout.widget_compact) {
            v.setTextViewText(R.id.desc, Wmo.label(c.code))
            primary += R.id.desc
        }
        if (layout == R.layout.widget_full) {
            v.setTextViewText(R.id.updated, fmt.clock(f.fetchedAt))
            secondary += R.id.updated
        }

        val hourCount = when (size) {
            Size.STRIP -> 4
            Size.WIDE, Size.LARGE -> 6
            else -> 0
        }
        if (hourCount > 0) {
            v.setViewVisibility(R.id.hours, View.VISIBLE)
            val hours = f.upcomingHours(hourCount)
            for (i in 0 until hourCount) {
                val h = hours.getOrNull(i)
                val now = i == 0
                v.setTextViewText(HOUR_TIME[i], if (now) "Now" else h?.let { fmt.hour(it.time) } ?: "")
                v.setTextViewText(HOUR_TEMP[i], if (now) fmt.temp(c.temp) else h?.let { fmt.temp(it.temp) } ?: "")
                val icon = when {
                    now -> Wmo.widgetIcon(c.code, c.isDay)
                    h != null -> Wmo.widgetIcon(h.code, h.isDay)
                    else -> 0
                }
                v.setImageViewResource(HOUR_ICON[i], icon)
                secondary += HOUR_TIME[i]
                primary += HOUR_TEMP[i]
                icons += HOUR_ICON[i]
            }
        }

        val dayCount = when (size) {
            Size.TALL -> 3
            Size.LARGE -> 4
            else -> 0
        }
        if (dayCount > 0) {
            v.setViewVisibility(R.id.days, View.VISIBLE)
            val days = f.upcomingDays().drop(1).take(dayCount) // today is already on top
            val dayName = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            for (i in 0 until dayCount) {
                val d = days.getOrNull(i)
                v.setTextViewText(DAY_NAME[i], d?.let { dayName.format(it.date) } ?: "")
                v.setImageViewResource(DAY_ICON[i], d?.let { Wmo.widgetIcon(it.code, true) } ?: 0)
                d?.let { v.setContentDescription(DAY_ICON[i], Wmo.label(it.code)) }
                v.setTextViewText(DAY_POP[i], if (d != null && d.pop >= 20) fmt.percent(d.pop) else "")
                v.setTextViewText(DAY_TEMPS[i], d?.let { "${fmt.temp(it.min)} / ${fmt.temp(it.max)}" } ?: "")
                primary += DAY_NAME[i]
                primary += DAY_TEMPS[i]
                secondary += DAY_POP[i]
                icons += DAY_ICON[i]
            }
        }

        palette?.apply(v, primary, secondary, icons)
        v.setOnClickPendingIntent(android.R.id.background, open)
        return v
    }

    private fun message(context: Context, text: Int, palette: Palette?, open: PendingIntent): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_message)
        v.setTextViewText(R.id.message, context.getString(text))
        palette?.apply(v, listOf(R.id.message), emptyList(), emptyList())
        v.setOnClickPendingIntent(android.R.id.background, open)
        return v
    }

    /** Opens the app on the widget's city (an empty extra means the hometown). One PendingIntent per widget. */
    private fun openApp(context: Context, id: Int, chosen: Place?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_PLACE, chosen?.toJson()?.toString() ?: "")
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---- Which city each widget shows --------------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The city a widget was set to, or null if it follows the hometown. */
    fun chosenPlace(context: Context, id: Int): Place? = prefs(context).getString("place_$id", null)?.let(Place::parse)

    fun choose(context: Context, id: Int, place: Place?) {
        val e = prefs(context).edit()
        if (place == null) e.remove("place_$id") else e.putString("place_$id", place.toJson().toString())
        e.apply()
    }

    fun forget(context: Context, ids: IntArray) {
        val e = prefs(context).edit()
        ids.forEach { e.remove("place_$it") }
        e.apply()
    }

    /** After a backup is restored the launcher hands out new widget ids; carry the city choices over. */
    fun restore(context: Context, oldIds: IntArray, newIds: IntArray) {
        val p = prefs(context)
        val moved = oldIds.zip(newIds).mapNotNull { (old, new) -> p.getString("place_$old", null)?.let { new to it } }
        val e = p.edit()
        oldIds.forEach { e.remove("place_$it") }
        moved.forEach { (new, json) -> e.putString("place_$new", json) }
        e.apply()
    }

    fun places(context: Context, ids: IntArray): List<Place> {
        val home = Store.get(context).home
        return ids.asList().mapNotNull { chosenPlace(context, it) ?: home }.distinct()
    }

    // ---- Background refresh ------------------------------------------------------------------------------

    /** Starts [WidgetRefreshJob] if any widget's forecast is missing or older than [MAX_AGE_MS]. */
    fun refreshIfStale(context: Context, ids: IntArray) {
        val now = System.currentTimeMillis()
        val stale = places(context, ids).any { place ->
            val fetchedAt = ForecastFiles.fetchedAt(ForecastFiles.file(context, place))
            fetchedAt == null || now - fetchedAt > MAX_AGE_MS
        }
        if (!stale) return
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        // Re-scheduling an existing id would cancel a fetch that is already running.
        if (jobs.getPendingJob(JOB_REFRESH) != null) return
        // No network constraint: that would need an extra permission (ACCESS_NETWORK_STATE). The job simply
        // fails quietly when offline and the next 30-minute tick tries again.
        runCatching { jobs.schedule(JobInfo.Builder(JOB_REFRESH, ComponentName(context, WidgetRefreshJob::class.java)).build()) }
    }

    fun cancelRefresh(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_REFRESH)
    }

    // ---- Colours ----------------------------------------------------------------------------------------

    /** Colours for a fixed theme; null for Auto, whose day/night colours come from @color resources. */
    private class Palette(val bg: Int, val text: Int, val sub: Int) {
        fun apply(v: RemoteViews, primary: List<Int>, secondary: List<Int>, icons: List<Int>) {
            v.setInt(R.id.widget_bg, "setColorFilter", bg)
            primary.forEach { v.setTextColor(it, text) }
            secondary.forEach { v.setTextColor(it, sub) }
            icons.forEach { v.setInt(it, "setColorFilter", text) }
        }

        companion object {
            fun of(context: Context, theme: Int): Palette? {
                if (theme == 0) return null
                val t = ContextThemeWrapper(context, Themes.style(theme, context.resources.configuration)).theme
                fun color(attr: Int) = TypedValue().also { t.resolveAttribute(attr, it, true) }.data
                return Palette(color(R.attr.wBg), color(R.attr.wText), color(R.attr.wSub))
            }
        }
    }

    private val HOUR_TIME = intArrayOf(R.id.h0_time, R.id.h1_time, R.id.h2_time, R.id.h3_time, R.id.h4_time, R.id.h5_time)
    private val HOUR_ICON = intArrayOf(R.id.h0_icon, R.id.h1_icon, R.id.h2_icon, R.id.h3_icon, R.id.h4_icon, R.id.h5_icon)
    private val HOUR_TEMP = intArrayOf(R.id.h0_temp, R.id.h1_temp, R.id.h2_temp, R.id.h3_temp, R.id.h4_temp, R.id.h5_temp)
    private val DAY_NAME = intArrayOf(R.id.d0_day, R.id.d1_day, R.id.d2_day, R.id.d3_day)
    private val DAY_ICON = intArrayOf(R.id.d0_icon, R.id.d1_icon, R.id.d2_icon, R.id.d3_icon)
    private val DAY_POP = intArrayOf(R.id.d0_pop, R.id.d1_pop, R.id.d2_pop, R.id.d3_pop)
    private val DAY_TEMPS = intArrayOf(R.id.d0_temps, R.id.d1_temps, R.id.d2_temps, R.id.d3_temps)
}
