package io.github.ramziag.weather

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Weather+'s My location through the real activity: the welcome screen, the header, Cities, Radar and Search, with
 * simulated fixes, a stubbed place-name lookup and cached forecasts. Saves the here-*.png screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HereUiTest : HereFixture() {

    @Test
    fun welcomePill() {
        seed(withPlaces = false)
        val a = launch().get()
        assertTrue(a.visible(R.id.now_empty))
        assertEquals("Use my location", a.text(R.id.use_location))
        assertEquals(app.getString(R.string.here_hint), a.text(R.id.use_location_hint))
        assertTrue(a.visible(R.id.choose_home))
        shot(a, "here-welcome")

        a.click(R.id.use_location)
        answer(a, FINE, COARSE)
        assertEquals("Locating…", a.text(R.id.use_location))
        assertFalse(a.findViewById<View>(R.id.use_location).isEnabled)

        // No saved place nearby: named by the lookup, which only gets the rounded spot.
        fixNamed(39.8017, -89.6437, 12f, "Springfield")
        waitFor { !a.visible(R.id.status) }
        assertTrue(a.visible(R.id.now_content))
        assertFalse(a.visible(R.id.now_empty))
        assertEquals("Springfield", a.text(R.id.title))
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Illinois · updated"))
        assertEquals(1, lookups.size)
        assertTrue(lookups[0], "lat=39.80&lon=-89.64&" in lookups[0])
    }

    @Test
    fun nowOnHere() {
        seed()
        grant(FINE, COARSE)
        val a = launch().get()
        assertEquals("Springfield", a.text(R.id.title)) // My location stays off until tapped
        assertEquals("ic_home_small", a.titleMark())
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")

        a.click(R.id.tab_now)
        waitFor { !a.visible(R.id.status) }
        assertEquals("Chicago", a.text(R.id.title))
        assertEquals("ic_here_small", a.titleMark())
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Illinois · updated"))
        assertFalse(a.visible(R.id.back_home))
        shot(a, "here-now")

        // Every forecast tab follows it.
        a.click(R.id.tab_hourly)
        assertEquals("Chicago", a.text(R.id.title))
        a.click(R.id.tab_daily)
        assertEquals("Chicago", a.text(R.id.title))

        // The hometown, viewed: its own mark, and the way back to My location.
        a.openCity("Springfield")
        assertEquals("Springfield", a.text(R.id.title))
        assertEquals("ic_home_small", a.titleMark())
        assertEquals("Back to my location", a.text(R.id.back_home))
        a.click(R.id.back_home)
        assertEquals("Chicago", a.text(R.id.title))

        // Refresh renews a fix over a minute old (and reloads the forecast either way).
        a.click(R.id.refresh)
        assertTrue("a fresh fix is kept", listeners().isEmpty())
        idleFor(3 * MIN) // the GPS's last fix too
        setFixAgo(3 * MIN)
        a.click(R.id.refresh)
        assertEquals(1, listeners().size)
    }

    @Test
    fun approximate() {
        seed()
        grant(COARSE)
        val a = launch().get()
        a.useLocation(41.8781, -87.6298, 3000f, "Chicago")
        assertTrue("approximate takes the first fix", listeners().isEmpty())

        a.click(R.id.tab_now)
        waitFor { !a.visible(R.id.status) }
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Illinois · approximate · updated"))
        shot(a, "here-approx")
        assertTrue("Use precise location" in a.hereMenu().items())
    }

    @Test
    fun citiesHereRowAndMenu() {
        seed()
        grant(FINE, COARSE)
        val a = launch().get()
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")

        a.click(R.id.tab_cities)
        assertEquals(listOf("Chicago", "Springfield", "Paris", "Tokyo"), a.cityNames())
        assertEquals("Illinois, United States", a.cityRow(0).findViewById<TextView>(R.id.area).text.toString())
        waitFor { a.cityRow(0).findViewById<TextView>(R.id.temp).text.toString() == "67°" }
        val buttons = a.citiesButtons().map { it.text.toString() }
        assertTrue(buttons.toString(), "Add city" in buttons && "Use my location" !in buttons) // no pill while it works
        shot(a, "here-cities")

        val menu = a.hereMenu()
        assertEquals(listOf("Make hometown", "Save as city", "Don't look up place names", "Stop using location"), menu.items())

        // Saved as a city: a plain place at the rounded spot, not a second My location.
        menu.pick("Save as city")
        val city = Store.get(app).cities.last()
        assertEquals(Place("Chicago", "Illinois, United States", 41.88, -87.63), city)
        assertFalse(city.here)
        assertEquals(listOf("Chicago", "Springfield", "Paris", "Tokyo", "Chicago"), a.cityNames())

        // Made the hometown: the old one stays as a city, and My location stays the default.
        a.hereMenu().pick("Make hometown")
        assertEquals(city, Store.get(app).home)
        assertTrue(TestData.home in Store.get(app).cities)
        assertEquals("Chicago", a.cityNames()[1])
        assertNotNull(Here.place(app))
        a.click(R.id.tab_now)
        assertEquals("ic_here_small", a.titleMark())

        // Rows open their place; My location's is the default, so no way "back".
        a.openCity("Paris")
        assertEquals("Paris", a.text(R.id.title))
        a.click(R.id.tab_cities)
        a.cityRow(0).performClick()
        idle()
        assertEquals("Chicago", a.text(R.id.title))
        assertFalse(a.visible(R.id.back_home))
    }

    @Test
    fun radarDotAndLocate() {
        seed()
        grant(COARSE)
        val a = launch().get()
        a.useLocation(41.8781, -87.6298, 3000f, "Chicago")

        // Viewing another place: the map stays on it until "locate".
        a.openCity("Paris")
        a.click(R.id.tab_radar)
        val radar = a.findViewById<RadarView>(R.id.radar_map)
        assertEquals(Radar.mercatorX(TestData.paris.lon), field(radar, "cx"), 1e-9)

        a.click(R.id.radar_locate)
        assertTrue(field(radar, "zoom") >= 9.0)
        assertEquals(Radar.mercatorX(-87.6298), field(radar, "cx"), 1e-9)
        assertTrue("a fresh fix is kept", listeners().isEmpty())
        val bitmap = Bitmap.createBitmap(radar.width, radar.height, Bitmap.Config.ARGB_8888)
        radar.draw(Canvas(bitmap))
        assertEquals(color(a, R.attr.wText), bitmap.getPixel(radar.width / 2, radar.height / 2))
        loadRadar(a)
        shot(a, "here-radar")

        // Over a minute old: locate looks again.
        idleFor(11 * MIN) // approximate keeps a last fix up to 10 min
        setFixAgo(11 * MIN)
        a.click(R.id.radar_locate)
        assertEquals(1, listeners().size)
    }

    @Test
    fun searchRowPicksNamedPlainPlace() {
        seed()
        val a = launch().get()
        a.tapInCities("Add city")
        assertTrue(a.visible(R.id.search_here))
        assertEquals("Use my location", a.text(R.id.search_here))

        // Asks first; granted, it locates for this search only.
        a.click(R.id.search_here)
        answer(a, FINE, COARSE)
        assertEquals("Locating…", a.text(R.id.search_status))
        assertFalse(a.findViewById<View>(R.id.search_here).isEnabled)

        simulate(41.8781, -87.6298, 9f)
        waitFor { a.visible(R.id.page_cities) }
        val added = Store.get(app).cities.last()
        assertEquals(Place("Chicago", "Illinois, United States", 41.88, -87.63), added)
        assertFalse(added.here)
        assertNull("search must not turn My location on", Here.place(app))
        waitFor { hereJson() != null }
        assertFalse(hereJson()!!.getBoolean("on"))
        assertEquals(listOf("Springfield", "Paris", "Tokyo", "Chicago"), a.cityNames())
        assertTrue("Use my location" in a.citiesButtons().map { it.text.toString() })

        // No fix this time: it says so and stays on the page.
        idleFor(31 * MIN) // past the last fix
        a.tapInCities("Add city")
        a.click(R.id.search_here)
        assertEquals(1, listeners().size)
        idleFor(45_100)
        assertTrue(listeners().isEmpty())
        assertTrue(a.visible(R.id.page_search))
        assertEquals(app.getString(R.string.here_no_fix), a.text(R.id.search_status))
        assertTrue(a.findViewById<View>(R.id.search_here).isEnabled)
    }

    /** "Use my location" given up on with back: a later fix must not land in the next search, e.g. Change hometown. */
    @Test
    fun abandonedSearchRowPicksNothingLater() {
        seed()
        grant(FINE, COARSE)
        val a = launch().get()
        a.tapInCities("Add city")
        a.click(R.id.search_here)
        assertEquals(1, listeners().size)
        @Suppress("DEPRECATION") a.onBackPressed()
        idle()
        assertTrue("My location is off: the search's attempt stops with the page", listeners().isEmpty())

        a.click(R.id.tab_cities)
        a.cityRow(0).performLongClick() // the hometown
        idle()
        ShadowAlertDialog.getLatestAlertDialog().pick("Change hometown")
        assertTrue(a.visible(R.id.page_search))
        assertTrue(a.findViewById<View>(R.id.search_here).isEnabled)
        assertEquals(app.getString(R.string.search_help), a.text(R.id.search_status))
        simulate(41.8781, -87.6298, 9f)
        idleFor(15_000) // past the naming guard
        assertTrue(a.visible(R.id.page_search))
        assertEquals(TestData.home, Store.get(app).home)
        assertEquals(listOf("Paris", "Tokyo"), Store.get(app).cities.map { it.name })
    }

    /** Leaving the app stops the search page's "Use my location"; coming back, the page doesn't say it's locating. */
    @Test
    fun searchRowStatusClearedAfterPause() {
        seed()
        grant(FINE, COARSE)
        val c = launch()
        val a = c.get()
        a.tapInCities("Add city")
        a.click(R.id.search_here)
        assertEquals("Locating…", a.text(R.id.search_status))
        c.pause()
        assertTrue(listeners().isEmpty())
        c.resume()
        idle()
        assertTrue(a.visible(R.id.page_search))
        assertEquals(app.getString(R.string.search_help), a.text(R.id.search_status))
        assertTrue(a.findViewById<View>(R.id.search_here).isEnabled)
    }

    @Test
    fun stopErasesAndResetsWidgets() {
        seed()
        grant(FINE, COARSE)
        bindWidget(41)
        val a = launch().get()
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")
        Widgets.chooseHere(app, 41)
        val names = File(app.noBackupFilesDir, "names.json")
        val forecast = File(forecasts, "here_41.880_-87.630.json")
        waitFor { names.exists() && hereJson() != null && forecast.exists() }

        // While it is locating again.
        idleFor(3 * MIN)
        setFixAgo(3 * MIN)
        a.click(R.id.tab_now)
        a.click(R.id.refresh)
        assertEquals(1, listeners().size)

        a.stopUsing()
        assertTrue(listeners().isEmpty())
        waitForGone(File(app.noBackupFilesDir, "here.json"))
        waitForGone(names)
        waitForGone(forecast)
        assertNull(Here.place(app))
        assertNull(Here.me())
        waitFor { Widgets.chosenRef(app, 41) == null }

        // Back to the hometown, and to the first tap: nothing is asked or located by itself.
        assertEquals(listOf("Springfield", "Paris", "Tokyo"), a.cityNames())
        assertTrue("Use my location" in a.citiesButtons().map { it.text.toString() })
        a.click(R.id.tab_now)
        assertEquals("Springfield", a.text(R.id.title))
        assertEquals("ic_home_small", a.titleMark())
        idleFor(20 * MIN)
        assertTrue(listeners().isEmpty())
        assertNull(asked(a))
    }

    /**
     * The permission is given back only once the app is left with location still off: Android can't cancel it,
     * and it stays granted until the app is killed, so turning it back on before then would silently fail later.
     */
    @Test
    fun stopGivesPermissionBackOnLeave() {
        seed()
        grant(FINE, COARSE)
        val c = launch()
        val a = c.get()
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")
        a.stopUsing()
        assertEquals("not while the app is open", 0, givenBack())

        // A change of mind before leaving: back on, and nothing is given back.
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")
        c.pause().stop()
        idle()
        assertEquals(0, givenBack())

        // Stopped and left: given back.
        c.restart().resume()
        idle()
        a.stopUsing()
        c.pause().stop()
        idle()
        assertEquals(1, givenBack())

        // Back before Android has killed the app: still granted, but it isn't used, and nothing turns on.
        c.restart().resume()
        idle()
        a.tapInCities("Use my location")
        assertNull("no dialog", asked(a))
        assertTrue(listeners().isEmpty())
        assertNull(Here.place(app))
        assertEquals(app.getString(R.string.here_given_back), ShadowToast.getTextOfLatestToast())
    }

    /** Requests to the permission controller, which is what revokeSelfPermissionsOnKill binds to. */
    private fun givenBack(): Int = shadowOf(app).boundServiceConnections.size

    @Test
    fun savedStateKeepsHereDefault() {
        val chicago = Place("Chicago", "Illinois, United States", 41.88, -87.63, here = true)
        seed()
        seedHere(chicago, fixAgoMs = 5 * MIN)
        grant(FINE, COARSE)
        val c = launch()
        assertEquals("Chicago", c.get().text(R.id.title))

        // Viewing a city survives recreation (theme change, process death); so does My location as the default.
        c.get().openCity("Paris")
        val paris = c.recreate()
        idle()
        assertEquals("Paris", paris.get().text(R.id.title))
        paris.get().click(R.id.back_home)
        val back = paris.recreate()
        idle()
        assertEquals("Chicago", back.get().text(R.id.title))
        assertFalse(back.get().visible(R.id.back_home))

        // A state saved while viewing an older My location shows the current one, never a stale copy.
        val state = Bundle().apply { putString("viewing", SPRINGFIELD_HERE.toJson().toString()) }
        val restored = Robolectric.buildActivity(MainActivity::class.java).setup(state).get()
        idle()
        assertEquals("Chicago", restored.text(R.id.title))
        assertFalse(restored.visible(R.id.back_home))
        assertTrue(lookups.isEmpty())
    }

    /** Stop while My location's forecast and Cities row are still loading: neither comes back when they arrive. */
    @Test
    fun stopWhileLoadingLeavesNothing() {
        seed()
        grant(FINE, COARSE)
        val a = launch().get()
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")
        val key = "here_41.880_-87.630"
        val forecast = File(forecasts, "$key.json")
        assertTrue(forecast.exists())

        val fetching = CountDownLatch(2)
        val release = CountDownLatch(1)
        OpenMeteo.fetch = { url ->
            fetching.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            if (url.endsWith("forecast_days=1")) summariesFor(url) else TestData.forecastJson(System.currentTimeMillis())
        }
        a.click(R.id.tab_now)
        a.click(R.id.refresh)
        a.click(R.id.tab_cities)
        // Cities may still be loading My location's first summary, and Repo skips a refresh while it loads:
        // refresh until the held summaries fetch has started too.
        val end = System.nanoTime() + 5_000_000_000
        while (fetching.count > 0 && System.nanoTime() < end) {
            a.click(R.id.refresh)
            Thread.sleep(50)
        }
        assertEquals(0, fetching.count)

        a.stopUsing()
        waitForGone(forecast)
        release.countDown()
        settleRepo()
        assertFalse(forecast.exists())
        assertFalse(JSONObject(File(app.cacheDir, "summaries.json").readText()).has(key))
        assertNull(Repo.get(app).summary(Place("", "", 41.88, -87.63, here = true)))
    }

    /** After a move, the last spot's forecast stands in only while the new one loads: not if that load fails. */
    @Test
    fun failedLoadAfterMoveDropsOldForecast() {
        seed()
        seedHere(fixAgoMs = 20 * MIN)
        grant(FINE, COARSE)
        val release = CountDownLatch(1)
        OpenMeteo.fetch = {
            assertTrue(release.await(5, TimeUnit.SECONDS))
            throw UnknownHostException()
        }
        val a = launch().get()
        assertFalse(a.visible(R.id.status))
        simulate(40.5, -89.64, 20f) // some 80 km north, where nothing is cached
        assertEquals("here_40.500_-89.640", Here.place(app)?.key)
        assertEquals("Updating…", a.text(R.id.subtitle))
        assertFalse(a.visible(R.id.status))

        release.countDown()
        waitFor { a.visible(R.id.status) }
        assertEquals("Offline", a.text(R.id.status))
        assertEquals("ic_here_small", a.titleMark())
        a.click(R.id.tab_daily)
        assertEquals("Offline", a.text(R.id.status))
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** A summaries answer with a row for each place asked for. */
    private fun summariesFor(url: String): String {
        val n = url.substringAfter("latitude=").substringBefore('&').split(',').size
        val row = JSONArray(javaClass.classLoader!!.getResource("summaries.json")!!.readText()).getJSONObject(0)
        return JSONArray().apply { repeat(n) { put(row) } }.toString()
    }

    /** Waits until Repo's background work, and what it posts back to the main thread, is all done. */
    private fun settleRepo() {
        val io = Repo::class.java.getDeclaredField("io").apply { isAccessible = true }.get(Repo.get(app)) as ThreadPoolExecutor
        waitFor { io.completedTaskCount == io.taskCount && shadowOf(Looper.getMainLooper()).isIdle }
    }

    /** The name of the drawable next to the header's title ("" for none), told apart by drawing it. */
    private fun Activity.titleMark(): String {
        val d = findViewById<TextView>(R.id.title).compoundDrawablesRelative[0] ?: return ""
        val id = listOf(R.drawable.ic_here_small, R.drawable.ic_home_small).firstOrNull { pixels(getDrawable(it)!!).sameAs(pixels(d)) }
        return id?.let(resources::getResourceEntryName) ?: "?"
    }

    private fun pixels(d: Drawable): Bitmap {
        val b = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val bounds = d.copyBounds()
        d.setBounds(0, 0, b.width, b.height)
        d.draw(Canvas(b))
        d.bounds = bounds
        return b
    }

    /** Radar frames and tiles come from the network (in CI), so their "Loading radar…" doesn't cover the dot. */
    private fun loadRadar(a: Activity) {
        val end = System.nanoTime() + 15_000_000_000L
        while (Repo.get(a).radarMaps == null && System.nanoTime() < end) {
            Thread.sleep(50)
            idle()
        }
        if (Repo.get(a).radarMaps == null) return // offline
        repeat(24) {
            draw(a)
            Thread.sleep(250)
            idle()
        }
    }

    private fun field(v: RadarView, name: String) =
        RadarView::class.java.getDeclaredField(name).apply { isAccessible = true }.getDouble(v)

    private fun color(a: Activity, attr: Int) = TypedValue().also { a.theme.resolveAttribute(attr, it, true) }.data
}
