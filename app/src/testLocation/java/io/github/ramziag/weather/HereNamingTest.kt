package io.github.ramziag.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

/** What My location and the search page's "Use my location" save, and when they may ask Nominatim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HereNamingTest : HereFixture() {

    /** Saved before it has a name: kept under its coordinates, never as a second "My location". */
    @Test
    fun savedBeforeNamedKeepsCoordinates() {
        nominatim = { 503 to "" }
        seed()
        grant(FINE, COARSE)
        val a = launch().get()
        a.tapInCities("Use my location")
        simulate(41.8781, -87.6298, 9f)
        waitFor { lookups.size == 1 }
        Thread.sleep(100)
        idle()
        assertEquals("My location", Here.place(app)?.name)

        a.hereMenu().pick("Make hometown")
        waitFor { Store.get(app).home?.key == "41.880_-87.630" }
        val home = Store.get(app).home!!
        assertEquals("41.88° N, 87.63° W", home.name)
        assertEquals("", home.area)
        assertFalse(home.here)
        assertEquals(listOf("My location", "41.88° N, 87.63° W", "Springfield", "Paris", "Tokyo"), a.cityNames())

        // Already there: no second row.
        a.hereMenu().pick("Save as city")
        Thread.sleep(100)
        idleFor(11_000)
        assertEquals(listOf(TestData.home, TestData.paris, TestData.tokyo), Store.get(app).cities)
    }

    /** A placeholder an older version saved ("My location" at its coordinates) no longer names My location for good. */
    @Test
    fun unnamedSavedPlaceDoesNotShadowTheName() {
        seed()
        Store.get(app).cities = Store.get(app).cities + Place("My location", "41.88° N, 87.63° W", 41.88, -87.63)
        grant(FINE, COARSE)
        val a = launch().get()
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")
        assertEquals(1, lookups.size)
        assertEquals("Illinois, United States", Here.place(app)?.area)
    }

    /** "Use my location" on the search page near a saved place picks that place, not a copy of its name a bit away. */
    @Test
    fun searchRowNearSavedPlacePicksIt() {
        seed()
        val a = launch().get()
        a.tapInCities("Add city")
        a.click(R.id.search_here)
        answer(a, FINE, COARSE)
        simulate(39.8101, -89.6502, 10f) // 1 km from the hometown
        waitFor { a.visible(R.id.page_cities) }
        assertEquals(listOf(TestData.paris, TestData.tokyo), Store.get(app).cities)
        assertEquals(listOf("Springfield", "Paris", "Tokyo"), a.cityNames())

        // As the hometown, near a saved city: that city moves up, it isn't copied.
        idleFor(31 * MIN) // past the last fix
        a.click(R.id.tab_cities)
        a.cityRow(0).performLongClick()
        idle()
        ShadowAlertDialog.getLatestAlertDialog().pick("Change hometown")
        a.click(R.id.search_here)
        simulate(48.8701, 2.3601, 10f) // 2 km from Paris
        waitFor { Store.get(app).home == TestData.paris }
        assertEquals("Paris", Store.get(app).home?.name)
        assertEquals(listOf(TestData.tokyo), Store.get(app).cities)
        assertTrue(lookups.isEmpty())
    }

    /** "Don't look up place names" outlives "Stop using location", and covers the search page's "Use my location". */
    @Test
    fun namesOffSurvivesStopAndCoversSearchRow() {
        seed()
        grant(FINE, COARSE)
        val a = launch().get()
        a.useLocation(41.8781, -87.6298, 9f, "Chicago")
        a.hereMenu().pick("Don't look up place names")
        a.stopUsing()
        waitFor { hereJson()?.let { !it.getBoolean("on") && !it.optBoolean("names", true) } == true }
        lookups.clear()

        idleFor(31 * MIN) // past the last fix
        a.tapInCities("Add city")
        a.click(R.id.search_here)
        simulate(41.9001, -87.6298, 9f)
        waitFor { a.visible(R.id.page_cities) }
        assertTrue(lookups.toString(), lookups.isEmpty())
        assertEquals("41.90° N, 87.63° W", Store.get(app).cities.last().name)

        // And in a new process.
        reset()
        val b = launch().get()
        idleFor(31 * MIN)
        b.tapInCities("Add city")
        b.click(R.id.search_here)
        simulate(41.8781, -87.6298, 9f)
        waitFor { b.visible(R.id.page_cities) }
        assertTrue(lookups.toString(), lookups.isEmpty())
        assertEquals("41.88° N, 87.63° W", Store.get(app).cities.last().name)
    }
}
