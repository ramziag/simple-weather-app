package io.github.ramziag.weather

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
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
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Drives the real activity on a Pixel-sized screen with cached data (no network needed) and saves a
 * screenshot of every page to build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {

    private val home = Place("Springfield", "Illinois, United States", 39.80172, -89.64371)
    private val paris = Place("Paris", "Île-de-France, France", 48.85341, 2.3488)
    private val tokyo = Place("Tokyo", "Tokyo, Japan", 35.6895, 139.69171)
    private val shots = System.getProperty("screenshots.dir")?.let(::File)?.apply { mkdirs() }

    @Before
    fun resetSingletons() {
        // Store and Repo live for the whole process; give every test a clean one.
        for (c in listOf(Store::class.java, Repo::class.java)) {
            c.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    @Test
    fun allPages() {
        seed(theme = 1)
        val a = launch()
        assertEquals("67°", a.text(R.id.now_temp))
        assertEquals("Springfield", a.text(R.id.title))
        shot(a, "1-now")

        a.click(R.id.tab_hourly)
        assertTrue(a.findViewById<ViewGroup>(R.id.hourly_list).childCount > 2)
        shot(a, "2-hourly")

        a.click(R.id.tab_daily)
        val card = a.findViewById<ViewGroup>(R.id.daily_list).getChildAt(1) as ViewGroup
        assertTrue(card.childCount >= 9)
        card.getChildAt(1).performClick()
        shot(a, "3-daily")

        a.click(R.id.tab_cities)
        val list = a.findViewById<ViewGroup>(R.id.cities_list)
        assertEquals("Springfield", (list.getChildAt(0).findViewById<TextView>(R.id.name)).text.toString())
        shot(a, "4-cities")

        list.getChildAt(1).performClick() // Paris
        waitFor { a.findViewById<View>(R.id.status).visibility != View.VISIBLE }
        assertEquals("Paris", a.text(R.id.title))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.back_home).visibility)
        shot(a, "5-other-city")

        a.click(R.id.back_home)
        assertEquals("Springfield", a.text(R.id.title))

        a.click(R.id.tab_cities)
        val add = a.findViewById<ViewGroup>(R.id.cities_list).let { it.getChildAt(it.childCount - 2) }
        add.performClick()
        idle()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.page_search).visibility)
        a.findViewById<EditText>(R.id.search_input).setText("Portland, OR")
        // Debounce, then the request (real network in CI; an error message is fine too).
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        waitFor { a.text(R.id.search_status) != "Searching…" }
        shot(a, "6-search")
        val results = a.findViewById<ListView>(R.id.search_results)
        if (results.count > 0) {
            results.performItemClick(results.adapter.getView(0, null, results), 0, 0)
            idle()
            assertEquals(View.VISIBLE, a.findViewById<View>(R.id.page_cities).visibility)
            assertEquals(4, store().allPlaces.size)
        }
    }

    @Test
    fun darkTheme() {
        seed(theme = 7)
        val a = launch()
        shot(a, "7-dusk-now")
        a.click(R.id.tab_daily)
        shot(a, "8-dusk-daily")
    }

    @Test
    fun firstRun() {
        seed(theme = 4, withPlaces = false)
        val a = launch()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.now_empty).visibility)
        shot(a, "9-welcome")
        a.click(R.id.choose_home)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.page_search).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.tabs).visibility)
    }

    @Test
    fun unitsToggleWithoutRefetch() {
        seed(theme = 3)
        val a = launch()
        assertEquals("67°", a.text(R.id.now_temp))
        a.click(R.id.units)
        assertEquals("20°", a.text(R.id.now_temp))
        assertEquals("°C", a.text(R.id.units))
        assertEquals(false, store().imperial)
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private fun store() = Store.get(RuntimeEnvironment.getApplication())

    /** Saved places plus fresh cached forecasts, with the fixture's dates moved to "today" at UTC-5. */
    private fun seed(theme: Int, withPlaces: Boolean = true) {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().clear()
        if (withPlaces) {
            prefs.putString("home", home.toJson().toString())
            prefs.putString("cities", Place.listJson(listOf(paris, tokyo)))
        }
        prefs.putBoolean("imperial", true).putInt("theme", theme).commit()

        val now = System.currentTimeMillis()
        val today = LocalDateTime.ofEpochSecond(now / 1000, 0, ZoneOffset.ofHours(-5)).toLocalDate()
        val shift = ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 3), today)
        val body = Regex("2026-10-(\\d\\d)").replace(javaClass.classLoader!!.getResource("forecast.json")!!.readText()) {
            LocalDate.of(2026, 10, it.groupValues[1].toInt()).plusDays(shift).toString()
        }
        val dir = File(app.cacheDir, "forecasts").apply { mkdirs() }
        for (p in listOf(home, paris, tokyo)) File(dir, "${p.key}.json").writeText("$now\n$body")
        File(app.cacheDir, "summaries.json").writeText(
            """{"${paris.key}":{"temp":15.2,"code":3,"day":true,"max":17,"min":9.5,"at":$now},""" +
                """"${tokyo.key}":{"temp":21,"code":61,"day":false,"max":24.1,"min":18.2,"at":$now}}""",
        )
    }

    private fun launch(): Activity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        waitFor { a.findViewById<View>(R.id.status).visibility != View.VISIBLE || store().home == null }
        return a
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Background I/O posts back to the (paused) main looper, so pump it until [done]. */
    private fun waitFor(done: () -> Boolean) {
        repeat(300) {
            idle()
            if (done()) return
            Thread.sleep(20)
        }
    }

    private fun Activity.click(id: Int) {
        findViewById<View>(id).performClick()
        idle()
    }

    private fun Activity.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun shot(a: Activity, name: String) {
        idle()
        val dir = shots ?: return
        val dm = a.resources.displayMetrics
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val bitmap = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
