package io.github.ramziag.weather

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLocationManager
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.Collections

/**
 * Shared set-up for Weather+'s Robolectric tests: places and cached forecasts (also for the spots My location goes
 * to, so nothing needs the network), a stubbed place-name lookup, simulated fixes and permission-dialog answers.
 */
abstract class HereFixture {

    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected val lm: ShadowLocationManager get() = shadowOf(app.getSystemService(LocationManager::class.java))
    protected val forecasts get() = File(app.cacheDir, "forecasts")
    private val shots = System.getProperty("screenshots.dir")?.let(::File)?.apply { mkdirs() }

    /** Nominatim URLs asked for (from Here's lookup thread). */
    protected val lookups: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** What the stubbed Nominatim answers. */
    protected var nominatim: (String) -> Pair<Int, String> = { url -> 200 to if ("lat=41.88" in url || "lat=41.90" in url) CHICAGO else SPRINGFIELD }

    @Before
    fun resetHere() {
        reset()
        // Room for fixes "minutes ago" on the elapsed-realtime clock, which starts near zero.
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
    }

    /** A fresh process, as far as the app's singletons go (files stay). */
    protected fun reset() {
        TestData.resetSingletons()
        Nominatim.fetch = { url ->
            lookups += url
            nominatim(url)
        }
    }

    // ---- Data ------------------------------------------------------------------------------------------

    /**
     * Places (unless not [withPlaces]) and forecasts; summaries for every spot so Cities never fetches; and a
     * leftover My location forecast that the startup sweep must delete.
     */
    protected fun seed(withPlaces: Boolean = true) {
        TestData.seed(app, theme = 1, withPlaces = withPlaces)
        val now = System.currentTimeMillis()
        val s = ForecastFiles.read(ForecastFiles.file(app, TestData.home))!!.summary()
        val summaries = JSONObject(File(app.cacheDir, "summaries.json").readText())
        for (key in listOf(TestData.home.key) + SPOTS) {
            summaries.put(key, JSONObject().put("temp", s.temp).put("code", s.code).put("day", s.isDay).put("max", s.max).put("min", s.min).put("at", now))
        }
        File(app.cacheDir, "summaries.json").writeText(summaries.toString())
        File(forecasts, "here_0.000_0.000.json").writeText("0\n{}")
    }

    /** My location as left by an earlier run: on, at [place], last fixed [fixAgoMs] ago; its forecast cached. */
    protected fun seedHere(
        place: Place = SPRINGFIELD_HERE,
        fixAgoMs: Long = 20 * MIN,
        approx: Boolean = false,
        blocked: Boolean = false,
        on: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("on", on).put("blocked", blocked).put("names", true)
            .put("fixAt", now - fixAgoMs).put("failAt", 0).put("approx", approx).put("place", place.toJson())
        File(app.noBackupFilesDir, "here.json").writeText(o.toString())
        cache(place.key)
    }

    protected fun hereJson(): JSONObject? = File(app.noBackupFilesDir, "here.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }

    private fun cache(key: String) {
        val now = System.currentTimeMillis()
        val file = File(forecasts.apply { mkdirs() }, "$key.json")
        if (!file.exists()) ForecastFiles.writeAtomic(file, "$now\n${TestData.forecastJson(now)}")
    }

    // ---- Activity --------------------------------------------------------------------------------------

    /** Starts the app (with [intent]), waits for the startup sweep, then caches forecasts for the spots tests go to. */
    protected fun launch(intent: Intent? = null): ActivityController<MainActivity> {
        val c = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        waitFor { !File(forecasts, "here_0.000_0.000.json").exists() }
        SPOTS.forEach(::cache)
        idle()
        return c
    }

    /** Places widget [id] on the home screen (unconfigured: it follows the hometown). */
    protected fun bindWidget(id: Int) {
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, WeatherWidget::class.java) }
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(id, info)
    }

    /** Opens the place called [name] from Cities (the first row of that name). */
    protected fun Activity.openCity(name: String) {
        click(R.id.tab_cities)
        val list = findViewById<ViewGroup>(R.id.cities_list)
        val row = (0 until list.childCount).map(list::getChildAt).first { it.findViewById<TextView>(R.id.name)?.text?.toString() == name }
        row.performClick()
        idle()
    }

    /** Taps a pill or button in the Cities list by its label. */
    protected fun Activity.tapInCities(label: String) {
        click(R.id.tab_cities)
        val pill = citiesButtons().firstOrNull { it.text.toString() == label }
        assertNotNull("no \"$label\" in ${citiesButtons().map { it.text }}", pill)
        pill!!.performClick()
        idle()
    }

    /** Pills and buttons in the Cities list (rows excluded), e.g. "Use my location", "Add city". */
    protected fun Activity.citiesButtons(): List<TextView> {
        val list = findViewById<ViewGroup>(R.id.cities_list)
        return (0 until list.childCount).map(list::getChildAt).filterIsInstance<TextView>()
    }

    protected fun Activity.cityNames(): List<String> {
        val list = findViewById<ViewGroup>(R.id.cities_list)
        return (0 until list.childCount).mapNotNull { list.getChildAt(it).findViewById<TextView>(R.id.name)?.text?.toString() }
    }

    protected fun Activity.cityRow(i: Int): View = findViewById<ViewGroup>(R.id.cities_list).getChildAt(i)

    /** Taps "Use my location" in Cities (the permission is already held) and waits for a fix named [name]. */
    protected fun Activity.useLocation(lat: Double, lon: Double, accM: Float, name: String) {
        tapInCities("Use my location")
        fixNamed(lat, lon, accM, name)
    }

    /** "Stop using location" from the Cities menu. Robolectric can't bind the controller that gives the permission back. */
    protected fun Activity.stopUsing() {
        shadowOf(app).declareActionUnbindable("android.permission.PermissionControllerService")
        hereMenu().pick("Stop using location")
    }

    /** Touch & hold on My location's Cities row; returns the menu. */
    protected fun Activity.hereMenu(): AlertDialog {
        click(R.id.tab_cities)
        cityRow(0).performLongClick()
        idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    protected fun AlertDialog.items(): List<String> = (0 until listView.adapter.count).map { listView.adapter.getItem(it).toString() }

    protected fun AlertDialog.pick(label: String) {
        val at = items().indexOf(label)
        assertTrue("no \"$label\" in ${items()}", at >= 0)
        shadowOf(listView).performItemClick(at)
        idle()
    }

    protected fun Activity.click(id: Int) {
        findViewById<View>(id).performClick()
        idle()
    }

    protected fun Activity.text(id: Int) = findViewById<TextView>(id).text.toString()

    protected fun Activity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    // ---- Permission ------------------------------------------------------------------------------------

    /**
     * Answers the pending permission dialog the way the system does: grants [granted], sets what the rationale
     * check says afterwards, and delivers the result. Returns the dialog's request.
     */
    protected fun answer(a: Activity, vararg granted: String, rationale: Boolean = false): Intent {
        val request = asked(a)
        assertNotNull("no permission request", request)
        assertEquals(REQUEST_LOCATION, request!!.requestCode)
        if (granted.isNotEmpty()) shadowOf(app).grantPermissions(*granted)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(FINE, rationale)
        val names = arrayOf(FINE, COARSE)
        val results = IntArray(names.size) { if (names[it] in granted) 0 else -1 }
        shadowOf(a).receiveResult(
            request.intent,
            Activity.RESULT_OK,
            Intent()
                .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", names)
                .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", results),
        )
        idle()
        return request.intent
    }

    /** The next permission dialog [a] started (skipping other activities it started), or null. */
    protected fun asked(a: Activity): ShadowActivity.IntentForResult? {
        while (true) {
            val i = shadowOf(a).nextStartedActivityForResult ?: return null
            if (i.intent.action == "android.content.pm.action.REQUEST_PERMISSIONS") return i
        }
    }

    protected fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    /** The next activity started with [action] (skipping others), or null. */
    protected fun started(action: String): Intent? {
        while (true) {
            val i = shadowOf(app).nextStartedActivity ?: return null
            if (i.action == action) return i
        }
    }

    // ---- Location --------------------------------------------------------------------------------------

    protected fun location(lat: Double, lon: Double, accM: Float, provider: String = GPS, ageMs: Long = 0) =
        Location(provider).apply {
            latitude = lat
            longitude = lon
            accuracy = accM
            time = System.currentTimeMillis() - ageMs
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000
        }

    /** A fix from [provider] (to its listeners, and as its last known location). */
    protected fun simulate(lat: Double, lon: Double, accM: Float, provider: String = GPS) {
        lm.simulateLocation(location(lat, lon, accM, provider))
        idle()
    }

    protected fun listeners() = lm.locationUpdateListeners

    /** As if My location's last fix were [ms] old (the wall clock doesn't move in tests). */
    protected fun setFixAgo(ms: Long) {
        Here::class.java.getDeclaredField("fixAt").apply { isAccessible = true }.setLong(null, System.currentTimeMillis() - ms)
    }

    /** A fix at [lat], [lon] named by the stub (or a saved place), then waits for the name. */
    protected fun fixNamed(lat: Double, lon: Double, accM: Float, name: String, provider: String = GPS) {
        simulate(lat, lon, accM, provider)
        waitFor { Here.place(app)?.name == name }
    }

    // ---- Looper and screenshots ------------------------------------------------------------------------

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    protected fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Background work (files, the name lookup) posts back to the main looper, so pump it until [done]. */
    protected fun waitFor(timeoutMs: Long = 6_000, done: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            idle()
            if (done()) return
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    protected fun waitForGone(file: File) = waitFor { !file.exists() }.also { assertFalse(file.exists()) }

    protected fun shot(a: Activity, name: String) {
        idle()
        save(draw(a), name)
    }

    /** The whole screen as it is now. */
    protected fun draw(a: Activity): Bitmap {
        val dm = a.resources.displayMetrics
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val bitmap = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    protected fun save(bitmap: Bitmap, name: String) {
        val dir = shots ?: return
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected companion object {
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
        const val GPS = LocationManager.GPS_PROVIDER
        const val NETWORK = LocationManager.NETWORK_PROVIDER
        const val FUSED = "fused"
        const val MIN = 60_000L

        /** My location in Springfield, named after the hometown (within 3 km). */
        val SPRINGFIELD_HERE = Place("Springfield", "Illinois, United States", 39.80, -89.64, here = true)

        /** Every spot the tests send My location to. */
        val SPOTS = listOf(
            "here_39.800_-89.640", "here_39.810_-89.650", "here_39.820_-89.640",
            "here_41.880_-87.630", "here_41.900_-87.630",
        )

        const val SPRINGFIELD = """{"addresstype":"city","name":"Springfield","address":{"city":"Springfield",""" +
            """"county":"Sangamon County","state":"Illinois","country":"United States","country_code":"us"}}"""
        const val CHICAGO = """{"addresstype":"city","name":"Chicago","address":{"city":"Chicago",""" +
            """"county":"Cook County","state":"Illinois","country":"United States","country_code":"us"}}"""
    }
}
