package io.github.ramziag.weather

import android.app.job.JobParameters
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** Widgets set to My location: the picker, what they show and open, and that they never locate by themselves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HereWidgetTest : HereFixture() {

    private val mgr: AppWidgetManager get() = AppWidgetManager.getInstance(app)

    @Test
    fun optionOnlyWhenHerePresent() {
        seed()
        bindWidget(41)
        assertEquals(listOf("Hometown · Springfield", "Paris, Île-de-France", "Tokyo, Tokyo"), pickerLabels(41))

        // Set to My location while it isn't there (yet, or any more): still listed, as picked.
        Widgets.chooseHere(app, 41)
        assertEquals(listOf("Hometown · Springfield", "My location", "Paris, Île-de-France", "Tokyo, Tokyo"), pickerLabels(41))
        assertEquals(1, ShadowAlertDialog.getLatestAlertDialog().listView.checkedItemPosition)
        Widgets.choose(app, 41, null)

        // Present: offered after the hometown, with its name.
        grant(FINE, COARSE)
        launch().get().useLocation(41.8781, -87.6298, 9f, "Chicago")
        assertEquals(listOf("Hometown · Springfield", "My location · Chicago", "Paris, Île-de-France", "Tokyo, Tokyo"), pickerLabels(41))
        assertEquals(0, ShadowAlertDialog.getLatestAlertDialog().listView.checkedItemPosition)
    }

    @Test
    fun markerStoredAndRendered() {
        seed()
        seedHere(CHICAGO_HERE, fixAgoMs = 5 * MIN)
        cacheForecast(CHICAGO_HERE.key)
        bindWidget(41)
        pickerLabels(41)
        shadowOf(ShadowAlertDialog.getLatestAlertDialog().listView).performItemClick(1)
        idle()

        // Stored as the marker, not as a copy of the place: it follows My location wherever it goes.
        assertEquals(HERE_MARKER, Widgets.chosenRef(app, 41))
        assertNull(Widgets.chosenPlace(app, 41))
        assertEquals(listOf(CHICAGO_HERE), Widgets.places(app, intArrayOf(41)))
        val view = inflate(Widgets.build(app, mgr, 41))
        assertEquals("Chicago", view.findViewById<TextView>(R.id.name).text.toString())
        assertEquals("67°", view.findViewById<TextView>(R.id.temp).text.toString())
        widgetShot(view, "here-widget")

        // Moved and renamed: the same widget shows the new spot.
        val moved = Place("Evanston", "Illinois, United States", 42.05, -87.69, here = true)
        reset()
        seedHere(moved, fixAgoMs = MIN)
        cacheForecast(moved.key)
        assertEquals(listOf(moved), Widgets.places(app, intArrayOf(41)))
        assertEquals("Evanston", inflate(Widgets.build(app, mgr, 41)).findViewById<TextView>(R.id.name).text.toString())
    }

    @Test
    fun openIntentCarriesHere() {
        seed()
        seedHere(CHICAGO_HERE, fixAgoMs = MIN)
        grant(FINE, COARSE)
        bindWidget(41)
        Widgets.chooseHere(app, 41)
        bindWidget(42) // follows the hometown
        cacheForecast(CHICAGO_HERE.key)
        val open = tap(41)
        assertEquals(HERE_MARKER, open.getStringExtra(Widgets.EXTRA_PLACE))
        assertEquals("", tap(42).getStringExtra(Widgets.EXTRA_PLACE))

        // A cold start from it: My location, and a fix a minute old is good enough.
        val c = launch(Intent(open)) // the app takes the extra off the intent it gets
        val a = c.get()
        assertEquals("Chicago", a.text(R.id.title))
        assertTrue(listeners().isEmpty())

        // From a city, with the fix over 2 min old: back on My location, and it looks again.
        a.openCity("Paris")
        setFixAgo(3 * MIN)
        c.newIntent(Intent(open))
        idle()
        assertEquals("Chicago", a.text(R.id.title))
        assertEquals(1, listeners().size)

        // The usual case on a phone: the app is in the background, so the tap arrives while it is paused. It
        // looks again once resumed, never before.
        simulate(41.8781, -87.6298, 9f)
        assertTrue(listeners().isEmpty())
        idleFor(3 * MIN) // too old by then to stand in for a new fix
        a.openCity("Paris")
        setFixAgo(3 * MIN)
        c.pause()
        c.newIntent(Intent(open))
        idle()
        assertTrue(listeners().isEmpty())
        c.resume()
        idle()
        assertEquals("Chicago", a.text(R.id.title))
        assertEquals(1, listeners().size)

        // The hometown widget opens the hometown itself, even though My location is the default.
        c.newIntent(tap(42))
        idle()
        assertEquals("Springfield", a.text(R.id.title))
        assertTrue(a.visible(R.id.back_home))
    }

    @Test
    fun defaultFallsBackToHereWithoutHome() {
        seed(withPlaces = false)
        val id = shadowOf(mgr).createWidget(WeatherWidget::class.java, R.layout.widget_message)
        assertEquals(app.getString(R.string.widget_no_home), shadowOf(mgr).getViewFor(id).findViewById<TextView>(R.id.message).text.toString())
        assertEquals("Open Weather+ to choose a hometown or use your location", app.getString(R.string.widget_no_home))

        // My location found, still no hometown: an unconfigured widget shows it.
        reset()
        seedHere(CHICAGO_HERE, fixAgoMs = 5 * MIN)
        cacheForecast(CHICAGO_HERE.key)
        assertEquals(listOf(CHICAGO_HERE), Widgets.places(app, intArrayOf(id)))
        assertEquals("Chicago", inflate(Widgets.build(app, mgr, id)).findViewById<TextView>(R.id.name).text.toString())
        assertEquals("", tap(id).getStringExtra(Widgets.EXTRA_PLACE))

        // The hometown, once there is one, comes first.
        TestData.seed(app, theme = 1)
        reset()
        seedHere(CHICAGO_HERE, fixAgoMs = 5 * MIN)
        assertEquals(listOf(TestData.home), Widgets.places(app, intArrayOf(id)))
    }

    @Test
    fun stopResetsToHometown() {
        seed()
        grant(FINE, COARSE)
        bindWidget(41)
        bindWidget(42)
        val a = launch().get()
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")
        Widgets.chooseHere(app, 41)
        Widgets.choose(app, 42, TestData.paris)
        assertEquals("Chicago", inflate(Widgets.build(app, mgr, 41)).findViewById<TextView>(R.id.name).text.toString())

        a.stopUsing()
        waitFor { Widgets.chosenRef(app, 41) == null }
        assertEquals(TestData.paris, Widgets.chosenPlace(app, 42)) // other choices stay
        assertEquals(listOf(TestData.home, TestData.paris), Widgets.places(app, intArrayOf(41, 42)))
        assertEquals("Springfield", inflate(Widgets.build(app, mgr, 41)).findViewById<TextView>(R.id.name).text.toString())
        assertEquals("", tap(41).getStringExtra(Widgets.EXTRA_PLACE))
        assertEquals(listOf("Hometown · Springfield", "Paris, Île-de-France", "Tokyo, Tokyo"), pickerLabels(41))
    }

    @Test
    fun refreshJobRegistersNoLocationListener() {
        seed()
        seedHere(CHICAGO_HERE, fixAgoMs = 60 * MIN) // long due for a new fix
        cacheForecast(CHICAGO_HERE.key)
        grant(FINE, COARSE)
        lm.setProviderEnabled(NETWORK, true)
        lm.setLastKnownLocation(GPS, location(48.8534, 2.3488, 10f)) // the phone is in Paris by now
        bindWidget(41)
        Widgets.chooseHere(app, 41)
        val saved = File(app.noBackupFilesDir, "here.json").readText()

        // The widget's own update and the background refresh job: neither locates nor asks.
        WeatherWidget().onUpdate(app, mgr, intArrayOf(41))
        val job = Robolectric.buildService(WidgetRefreshJob::class.java).create().get()
        assertTrue(job.onStartJob(params()))
        waitFor { shadowOf(job).isJobFinished }
        Thread.sleep(100)
        idle()
        assertTrue(listeners().isEmpty())
        for (p in listOf(GPS, NETWORK, FUSED)) assertTrue(p, lm.getLocationRequests(p).isEmpty())
        assertEquals(CHICAGO_HERE, Here.place(app))
        assertEquals(saved, File(app.noBackupFilesDir, "here.json").readText())
        assertFalse(File(app.noBackupFilesDir, "names.json").exists())
        assertTrue(lookups.isEmpty())
        assertEquals("Chicago", shadowOf(mgr).getViewFor(41).findViewById<TextView>(R.id.name).text.toString())
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** Opens the widget picker for [id] and returns its options. */
    private fun pickerLabels(id: Int): List<String> {
        val config = Intent(app, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        Robolectric.buildActivity(WidgetConfigActivity::class.java, config).setup()
        return ShadowAlertDialog.getLatestAlertDialog().items()
    }

    /** What tapping widget [id] starts. */
    private fun tap(id: Int): Intent {
        inflate(Widgets.build(app, mgr, id)).findViewById<View>(android.R.id.background).performClick()
        return shadowOf(app).nextStartedActivity
    }

    private fun cacheForecast(key: String) {
        val now = System.currentTimeMillis()
        ForecastFiles.writeAtomic(File(forecasts.apply { mkdirs() }, "$key.json"), "$now\n${TestData.forecastJson(now)}")
    }

    /** The wide layout (4x2 cells), as a launcher picks it from the sized RemoteViews, outside the app theme. */
    private fun inflate(views: RemoteViews): View {
        val host = app.createPackageContext(app.packageName, 0)
        val size = Widgets.Size.WIDE
        // Not in the public SDK.
        val pick = RemoteViews::class.java.getMethod("getRemoteViewsToApply", Context::class.java, SizeF::class.java)
        val one = pick.invoke(views, host, SizeF(size.width.toFloat(), size.height.toFloat())) as RemoteViews
        val view = one.apply(host, FrameLayout(host))
        val density = app.resources.displayMetrics.density
        val w = (315 * density).toInt() // a 5x5 grid's 4x2 cells
        val h = (292 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }

    /** Draws the widget on a wallpaper-ish backdrop, as WidgetTest does. */
    private fun widgetShot(view: View, name: String) {
        val margin = (12 * app.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(view.width + 2 * margin, view.height + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF7D8AA6.toInt())
        canvas.translate(margin.toFloat(), margin.toFloat())
        view.draw(canvas)
        save(bitmap, name)
    }

    /** JobParameters has no public constructor. */
    private fun params(): JobParameters {
        val c = JobParameters::class.java.declaredConstructors.first { it.parameterCount == 13 }
        return c.newInstance(null, null, 1, null, null, null, 0, false, false, false, null, null, null) as JobParameters
    }

    private companion object {
        val CHICAGO_HERE = Place("Chicago", "Illinois, United States", 41.88, -87.63, here = true)
    }
}
