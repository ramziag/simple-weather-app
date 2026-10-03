package io.github.ramziag.weather

import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val shots = System.getProperty("screenshots.dir")?.let(::File)?.apply { mkdirs() }

    /** Widget sizes (dp) on a Pixel with GrapheneOS Launcher3's 5x5 grid. */
    private val cells = mapOf(
        Widgets.Size.COMPACT to (158 to 146),
        Widgets.Size.STRIP to (315 to 146),
        Widgets.Size.SQUARE to (158 to 292),
        Widgets.Size.WIDE to (315 to 292),
        Widgets.Size.TALL to (158 to 438),
        Widgets.Size.LARGE to (315 to 438),
    )

    @Before
    fun resetSingletons() = TestData.resetSingletons()

    @Test
    fun sizeSteps() {
        for ((size, cell) in cells) assertEquals(size, Widgets.sizeFor(cell.first, cell.second))
        assertEquals(Widgets.Size.SQUARE, Widgets.sizeFor(237, 292)) // 3x2
        assertEquals(Widgets.Size.WIDE, Widgets.sizeFor(280, 292))
        assertEquals(Widgets.Size.COMPACT, Widgets.sizeFor(280, 146)) // too narrow for the strip's hours
        assertEquals(Widgets.Size.WIDE, Widgets.sizeFor(600, 292))
        assertEquals(Widgets.Size.COMPACT, Widgets.sizeFor(0, 0)) // size not reported yet
    }

    /**
     * Launchers inflate widgets with their own theme, so any app theme attribute in a widget layout breaks it
     * ("Can't load widget"). Inflate every layout step the way a launcher would, in every kind of theme.
     */
    @Test
    fun everyLayoutInflatesWithoutTheAppTheme() {
        for ((theme, label) in listOf(0 to "auto", 3 to "peach", 7 to "dusk")) {
            TestData.resetSingletons()
            TestData.seed(app, theme)
            val forecast = ForecastFiles.read(ForecastFiles.file(app, TestData.home))!!
            for (size in Widgets.Size.entries) {
                val view = inflateLikeALauncher(Widgets.preview(app, size, TestData.home, forecast), size)
                assertEquals("67°", view.findViewById<TextView>(R.id.temp).text.toString())
                assertEquals("Springfield", view.findViewById<TextView>(R.id.name).text.toString())
                val hours = view.findViewById<View>(R.id.hours)
                val days = view.findViewById<View>(R.id.days)
                val wantHours = size in setOf(Widgets.Size.STRIP, Widgets.Size.WIDE, Widgets.Size.LARGE)
                val wantDays = size in setOf(Widgets.Size.TALL, Widgets.Size.LARGE)
                assertEquals("$size hours", wantHours, hours?.visibility == View.VISIBLE)
                assertEquals("$size days", wantDays, days?.visibility == View.VISIBLE)
                // The strip shows the current temperature big already, so its hours start at the next one.
                if (wantHours) assertEquals(size != Widgets.Size.STRIP, view.findViewById<TextView>(R.id.h0_time).text.toString() == "Now")
                if (theme == 0 || size == Widgets.Size.WIDE || size == Widgets.Size.LARGE) shot(view, "w-$label-${size.name.lowercase()}")
            }
        }
    }

    @Test
    fun placedWidgetIsDrawnFromCacheWithoutFetching() {
        TestData.seed(app, theme = 1)
        val id = shadowOf(AppWidgetManager.getInstance(app)).createWidget(WeatherWidget::class.java, R.layout.widget_message)
        idle()
        val view = shadowOf(AppWidgetManager.getInstance(app)).getViewFor(id)
        assertEquals("67°", view.findViewById<TextView>(R.id.temp).text.toString())
        assertNull("fresh data must not start a fetch", jobs().allPendingJobs.firstOrNull())
    }

    @Test
    fun staleDataSchedulesOneRefreshJob() {
        TestData.seed(app, theme = 1, ageMs = 2 * 60 * 60 * 1000L)
        val mgr = shadowOf(AppWidgetManager.getInstance(app))
        mgr.createWidgets(WeatherWidget::class.java, R.layout.widget_message, 2)
        Widgets.refreshIfStale(app, Widgets.ids(app))
        val job = jobs().allPendingJobs.single()
        assertEquals(WidgetRefreshJob::class.java.name, job.service.className)
        // Calling again while one is pending must not replace (and so cancel) it.
        Widgets.refreshIfStale(app, Widgets.ids(app))
        assertSame(job, jobs().allPendingJobs.single())
    }

    /** Launchers re-apply new RemoteViews over the old views when the layout is the same. */
    @Test
    fun shrinkingAWidgetHidesRowsThatNoLongerFit() {
        TestData.seed(app, theme = 1)
        val forecast = ForecastFiles.read(ForecastFiles.file(app, TestData.home))!!
        val host = app.createPackageContext(app.packageName, 0)
        for ((big, small) in listOf(Widgets.Size.LARGE to Widgets.Size.WIDE, Widgets.Size.TALL to Widgets.Size.SQUARE, Widgets.Size.STRIP to Widgets.Size.COMPACT)) {
            val view = inflateLikeALauncher(Widgets.preview(app, big, TestData.home, forecast), big)
            Widgets.preview(app, small, TestData.home, forecast).reapply(host, view)
            val visible = { id: Int -> view.findViewById<View>(id)?.visibility == View.VISIBLE }
            assertEquals("$big → $small hours", small == Widgets.Size.WIDE, visible(R.id.hours))
            assertEquals("$big → $small days", false, visible(R.id.days))
        }
    }

    @Test
    fun coloursFollowTheThemeAndAutoFollowsNightMode() {
        TestData.seed(app, theme = 3) // Peach
        val forecast = ForecastFiles.read(ForecastFiles.file(app, TestData.home))!!
        val host = app.createPackageContext(app.packageName, 0)
        val view = inflateLikeALauncher(Widgets.preview(app, Widgets.Size.WIDE, TestData.home, forecast), Widgets.Size.WIDE)
        val temp = view.findViewById<TextView>(R.id.temp)
        assertEquals(0xFF36261E.toInt(), temp.currentTextColor)

        // Back to Auto: the Peach colours must not stay behind on the re-applied views.
        Store.get(app).theme = 0
        Widgets.preview(app, Widgets.Size.WIDE, TestData.home, forecast).reapply(host, view)
        assertEquals(0xFF1F2D3A.toInt(), temp.currentTextColor) // Sky

        // A launcher in dark mode picks the night colours from the same RemoteViews.
        val dark = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val night = inflateLikeALauncher(
            Widgets.preview(app, Widgets.Size.WIDE, TestData.home, forecast),
            Widgets.Size.WIDE,
            host.createConfigurationContext(dark),
        )
        assertEquals(0xFFECEEF7.toInt(), night.findViewById<TextView>(R.id.temp).currentTextColor) // Dusk
    }

    @Test
    fun noHometownShowsAHint() {
        TestData.seed(app, theme = 0, withPlaces = false)
        val id = shadowOf(AppWidgetManager.getInstance(app)).createWidget(WeatherWidget::class.java, R.layout.widget_message)
        val view = shadowOf(AppWidgetManager.getInstance(app)).getViewFor(id)
        assertEquals(app.getString(R.string.widget_no_home), view.findViewById<TextView>(R.id.message).text.toString())
    }

    @Test
    @Config(sdk = [30])
    fun android11PicksTheLayoutFromTheReportedSize() {
        TestData.seed(app, theme = 1)
        val mgr = AppWidgetManager.getInstance(app)
        val id = shadowOf(mgr).createWidget(WeatherWidget::class.java, R.layout.widget_message)
        for ((size, cell) in cells) {
            mgr.updateAppWidgetOptions(
                id,
                Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, cell.first)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, cell.second)
                },
            )
            val view = shadowOf(mgr).getViewFor(id)
            assertEquals("67°", view.findViewById<TextView>(R.id.temp).text.toString())
            assertEquals("$size", size == Widgets.Size.LARGE || size == Widgets.Size.TALL, view.findViewById<View>(R.id.days)?.visibility == View.VISIBLE)
        }
        // And the layouts inflate on Android 11 without the app theme too.
        val forecast = ForecastFiles.read(ForecastFiles.file(app, TestData.home))!!
        for (size in Widgets.Size.entries) inflateLikeALauncher(Widgets.preview(app, size, TestData.home, forecast), size)
    }

    @Test
    fun configurationPicksACityAndTheWidgetOpensIt() {
        TestData.seed(app, theme = 1)
        val mgr = AppWidgetManager.getInstance(app)
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, WeatherWidget::class.java) }
        shadowOf(mgr).addBoundWidget(41, info)

        val intent = Intent(app, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 41)
        val activity = Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).setup().get()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, dialog.listView.adapter.count) // hometown, Paris, Tokyo
        shadowOf(dialog.listView).performItemClick(1) // Paris
        idle()
        assertEquals(TestData.paris, Widgets.chosenPlace(app, 41))
        assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertTrue(activity.isFinishing)

        // Tapping the widget opens the app on Paris.
        val open = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(app, MainActivity::class.java).putExtra(Widgets.EXTRA_PLACE, TestData.paris.toJson().toString()),
        ).setup().get()
        idle()
        assertEquals("Paris", open.findViewById<TextView>(R.id.title).text.toString())
    }

    @Test
    fun configurationRejectsWidgetsThatAreNotOurs() {
        val intent = Intent(app, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 99)
        val activity = Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).setup().get()
        assertTrue(activity.isFinishing)
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNotNull(shadowOf(activity).resultIntent)
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private fun jobs() = app.getSystemService(JobScheduler::class.java)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A package context has the platform default theme, not ours: like the launcher's view of our layouts. */
    private fun inflateLikeALauncher(
        views: android.widget.RemoteViews,
        size: Widgets.Size,
        host: Context = app.createPackageContext(app.packageName, 0),
    ): View {
        val view = views.apply(host, FrameLayout(host))
        val (w, h) = cells.getValue(size).let { (wDp, hDp) -> px(wDp) to px(hDp) }
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }

    private fun px(dp: Int) = (dp * app.resources.displayMetrics.density).toInt()

    /** Draws the widget on a wallpaper-ish backdrop with launcher-like margins. */
    private fun shot(view: View, name: String) {
        val dir = shots ?: return
        val margin = px(12)
        val bitmap = Bitmap.createBitmap(view.width + 2 * margin, view.height + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF7D8AA6.toInt())
        canvas.translate(margin.toFloat(), margin.toFloat())
        view.draw(canvas)
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
