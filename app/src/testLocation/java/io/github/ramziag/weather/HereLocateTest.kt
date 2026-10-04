package io.github.ramziag.weather

import android.content.Intent
import android.location.LocationManager
import android.location.LocationRequest
import android.provider.Settings
import android.view.View
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** How Weather+ gets a fix (providers, last-known fixes, timeouts) and turns it into My location. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class HereLocateTest : HereFixture() {

    @Test
    fun locationOffNoListener() {
        seed()
        grant(FINE, COARSE)
        lm.setLocationEnabled(false) // Robolectric's providers still deliver then: the app has to check
        val c = launch()
        val a = c.get()
        a.tapInCities("Use my location")
        assertTrue(listeners().isEmpty())
        a.tapInCities("Turn on location")
        assertNotNull(started(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

        // Switched on in Settings, then back: it locates.
        lm.setLocationEnabled(true)
        c.pause().resume()
        idle()
        assertEquals(1, lm.getLocationUpdateListeners(GPS).size)
    }

    /** Switched on from Quick Settings: pulling down the shade doesn't pause the app, so the switch is watched. */
    @Test
    fun locationOnFromQuickSettings() {
        seed(withPlaces = false)
        grant(FINE, COARSE)
        lm.setLocationEnabled(false)
        val c = launch()
        val a = c.get()
        a.click(R.id.use_location)
        assertEquals("Turn on location", a.text(R.id.use_location))
        assertTrue(listeners().isEmpty())

        lm.setLocationEnabled(true)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        idle()
        assertEquals(1, listeners().size)
        assertEquals("Locating…", a.text(R.id.use_location))

        // Watched only while resumed, and only once.
        assertEquals(1, modeReceivers())
        c.pause()
        assertEquals(0, modeReceivers())
        c.resume()
        Here.resume()
        assertEquals(1, modeReceivers())
    }

    private fun modeReceivers() =
        shadowOf(app).registeredReceivers.count { it.intentFilter.hasAction(LocationManager.MODE_CHANGED_ACTION) }

    /** Once a fix has failed, the ticker waits 15 min too: no GPS request every few minutes while on screen. */
    @Test
    fun tickerDoesNotPollAfterFailure() {
        seed()
        seedHere(fixAgoMs = 20 * MIN)
        grant(FINE, COARSE)
        launch()
        assertEquals("a 20 min old fix is renewed on resume", 1, listeners().size)
        idleFor(45_100)
        assertTrue(listeners().isEmpty())

        setFailAgo(3 * MIN)
        idleFor(30_000) // the ticker runs at 60 s
        assertTrue("no retry 3 min after a failure", listeners().isEmpty())
        setFailAgo(16 * MIN)
        idleFor(50_000) // and at 120 s
        assertEquals(1, listeners().size)
    }

    @Test
    fun noFusedUsesGps() {
        seed()
        grant(FINE, COARSE)
        lm.setProviderEnabled(NETWORK, true)
        val a = launch().get()
        assertFalse(app.getSystemService(LocationManager::class.java).hasProvider(FUSED))
        a.tapInCities("Use my location")
        assertEquals(1, lm.getLocationUpdateListeners(GPS).size)
        assertTrue(lm.getLocationUpdateListeners(NETWORK).isEmpty())
        fixNamed(41.8781, -87.6298, 9f, "Chicago")
    }

    @Test
    fun fusedPreferredHighAccuracy() {
        seed()
        grant(FINE, COARSE)
        lm.setProviderEnabled(FUSED, true)
        lm.setProviderEnabled(NETWORK, true)
        val a = launch().get()
        a.tapInCities("Use my location")
        val request = lm.getLocationRequests(FUSED).single()
        assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, request.quality)
        assertEquals(1000L, request.intervalMillis)
        assertEquals(45_000L, request.durationMillis)
        assertTrue(lm.getLocationRequests(GPS).isEmpty())
        assertTrue(lm.getLocationRequests(NETWORK).isEmpty())

        // A rough fix keeps it going; the first one under 100 m ends it.
        simulate(41.8781, -87.6298, 500f, FUSED)
        assertEquals(1, lm.getLocationUpdateListeners(FUSED).size)
        idleFor(1_000) // updates come at most once a second
        fixNamed(41.8781, -87.6298, 9f, "Chicago", FUSED)
        assertTrue(listeners().isEmpty())
    }

    @Test
    fun approximateUsesNetworkThenGpsNeverFused() {
        seed()
        grant(COARSE)
        lm.setProviderEnabled(FUSED, true)
        lm.setProviderEnabled(NETWORK, true)
        val c = launch()
        val a = c.get()
        a.tapInCities("Use my location")
        assertEquals(1, lm.getLocationUpdateListeners(NETWORK).size)
        assertTrue(lm.getLocationUpdateListeners(GPS).isEmpty())

        // No network location (GrapheneOS's default): GPS.
        c.pause()
        lm.setProviderEnabled(NETWORK, false)
        c.resume()
        idle()
        assertEquals(1, lm.getLocationUpdateListeners(GPS).size)
        assertTrue(lm.getLocationUpdateListeners(NETWORK).isEmpty())
        assertTrue("approximate never asks fused", lm.getLocationRequests(FUSED).isEmpty())

        // Approximate fixes are 2 km wide: the first one is all there is.
        fixNamed(41.8781, -87.6298, 2000f, "Chicago")
        assertTrue(listeners().isEmpty())
        assertTrue(Here.approximate())
    }

    @Test
    fun freshLastKnownNoRequest() {
        seed()
        grant(FINE, COARSE)
        lm.setLastKnownLocation(GPS, location(41.8781, -87.6298, 15f, ageMs = 30_000))
        val a = launch().get()
        a.tapInCities("Use my location")
        waitFor { Here.place(app)?.name == "Chicago" }
        assertTrue(lm.getLocationRequests(GPS).isEmpty())
        assertTrue(listeners().isEmpty())
    }

    @Test
    fun oldLastKnownShownWhileLocating() {
        seed()
        grant(FINE, COARSE)
        lm.setLastKnownLocation(GPS, location(41.8781, -87.6298, 15f, ageMs = 20 * MIN))
        val a = launch().get()
        a.tapInCities("Use my location")
        waitFor { Here.place(app)?.name == "Chicago" }
        assertEquals(1, lm.getLocationUpdateListeners(GPS).size) // still looking for a current one
        a.click(R.id.tab_now)
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Locating… · updated"))
        simulate(41.8781, -87.6298, 9f)
        assertTrue(listeners().isEmpty())
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Illinois · updated"))
    }

    @Test
    fun slowHintAt8s() {
        seed(withPlaces = false)
        grant(FINE, COARSE)
        val a = launch().get()
        a.click(R.id.use_location)
        assertEquals("Locating…", a.text(R.id.use_location))
        assertFalse(a.findViewById<TextView>(R.id.use_location).isEnabled)
        assertEquals(app.getString(R.string.here_hint), a.text(R.id.use_location_hint))
        idleFor(7_900)
        assertEquals(app.getString(R.string.here_hint), a.text(R.id.use_location_hint))
        idleFor(200)
        assertEquals("Still locating…", a.text(R.id.use_location_hint))
    }

    @Test
    fun timeout45sFirstTimeShowsNoFix() {
        seed(withPlaces = false)
        grant(FINE, COARSE)
        val a = launch().get()
        a.click(R.id.use_location)
        idleFor(44_900)
        assertEquals(1, listeners().size)
        idleFor(200)
        assertTrue(listeners().isEmpty())
        assertEquals(app.getString(R.string.here_no_fix), a.text(R.id.use_location_hint))
        assertEquals("Try again", a.text(R.id.use_location))
        waitFor { (hereJson()?.optLong("failAt") ?: 0) > 0 }

        a.click(R.id.use_location)
        assertEquals(1, listeners().size)
        assertEquals("Locating…", a.text(R.id.use_location))
    }

    @Test
    fun timeoutWithHereShowsNoNewFix() {
        seed()
        seedHere(fixAgoMs = 20 * MIN)
        grant(FINE, COARSE)
        val a = launch().get()
        assertEquals("a 20 min old fix is renewed on resume", 1, lm.getLocationUpdateListeners(GPS).size)
        assertEquals("Springfield", a.text(R.id.title))
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Locating… · updated"))
        idleFor(8_000)
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Still locating… · updated"))
        idleFor(37_000)
        assertTrue(listeners().isEmpty())
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("No new fix · updated"))
        assertEquals(SPRINGFIELD_HERE, Here.place(app))
        a.click(R.id.tab_cities)
        assertEquals("No new fix", a.cityRow(0).findViewById<TextView>(R.id.area).text.toString())
        assertFalse("no pill while My location works", a.citiesButtons().any { it.text.toString() == "Try again" })
    }

    @Test
    fun pauseRemovesListener() {
        seed()
        grant(FINE, COARSE)
        val c = launch()
        c.get().tapInCities("Use my location")
        assertEquals(1, listeners().size)
        c.pause()
        assertTrue(listeners().isEmpty())
        idleFor(60_000) // nothing starts again while paused
        assertTrue(listeners().isEmpty())
        c.resume()
        idle()
        assertEquals(1, listeners().size)
    }

    /** A cold start with an old fix locates, but only once the window is up: no LocationManager work before. */
    @Test
    fun notBeforeFirstFrame() {
        seed()
        seedHere(fixAgoMs = 20 * MIN)
        grant(FINE, COARSE)
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        idle()
        assertTrue(listeners().isEmpty())
        c.visible()
        idle()
        assertEquals(1, listeners().size)
    }

    @Test
    fun move500mKeepsKey() {
        seed()
        seedHere(fixAgoMs = 20 * MIN)
        grant(FINE, COARSE)
        val before = hereJson()!!.getLong("fixAt")
        launch()
        simulate(39.8055, -89.64, 20f) // about 600 m north: rounds to 39.81, but within 1 km
        assertTrue(listeners().isEmpty())
        assertEquals(SPRINGFIELD_HERE.key, Here.place(app)?.key)
        waitFor { hereJson()!!.getLong("fixAt") > before }
        assertTrue(File(forecasts, "${SPRINGFIELD_HERE.key}.json").exists())
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun move2kmNewKeyDeletesOldFile() {
        seed()
        seedHere(fixAgoMs = 20 * MIN)
        grant(FINE, COARSE)
        val a = launch().get()
        simulate(39.818, -89.64, 20f)
        assertEquals("here_39.820_-89.640", Here.place(app)?.key)
        waitForGone(File(forecasts, "${SPRINGFIELD_HERE.key}.json"))
        waitFor { a.findViewById<TextView>(R.id.status).visibility != View.VISIBLE }
        // Still named after the hometown, 2 km away.
        assertEquals("Springfield", Here.place(app)?.name)
        assertEquals("Springfield", a.text(R.id.title))
        assertTrue(lookups.isEmpty())
        waitFor { hereJson()?.getJSONObject("place")?.getDouble("lat") == 39.82 }
    }

    @Test
    fun tickerSilentWhileViewingCity() {
        seed()
        seedHere(fixAgoMs = 5 * MIN)
        grant(FINE, COARSE)
        val a = launch().get()
        assertTrue("a 5 min old fix is kept on resume", listeners().isEmpty())
        a.click(R.id.tab_cities)
        a.cityRow(2).performClick() // Paris
        idle()
        assertEquals("Paris", a.text(R.id.title))

        setFixAgo(20 * MIN)
        idleFor(61_000)
        assertTrue("never locates for a city", listeners().isEmpty())
        a.click(R.id.back_home)
        assertEquals("Springfield", a.text(R.id.title))
        idleFor(60_000)
        assertEquals(1, listeners().size)
    }

    @Test
    fun nearbySavedPlaceNamesWithoutNetwork() {
        seed()
        grant(FINE, COARSE)
        val a = launch().get()
        a.tapInCities("Use my location")
        simulate(39.8101, -89.6502, 10f)
        val here = Here.place(app)!!
        assertEquals("Springfield", here.name) // at once: no lookup
        assertEquals("Illinois, United States", here.area)
        assertEquals("here_39.810_-89.650", here.key)
        Thread.sleep(200)
        idle()
        assertTrue(lookups.isEmpty())
        assertFalse(File(app.noBackupFilesDir, "names.json").exists())
    }

    @Test
    fun offlineFallsBackToCoordinates() {
        nominatim = { throw IOException("offline") }
        seed()
        grant(FINE, COARSE)
        val a = launch().get()
        a.tapInCities("Use my location")
        simulate(41.8781, -87.6298, 9f)
        waitFor { lookups.size == 1 }
        Thread.sleep(100)
        idle()
        assertEquals("My location", Here.place(app)?.name)
        assertEquals("41.88° N, 87.63° W", Here.place(app)?.area)
        a.click(R.id.tab_now)
        waitFor { a.findViewById<TextView>(R.id.status).visibility != View.VISIBLE }
        assertEquals("My location", a.text(R.id.title))
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("41.88° N, 87.63° W · updated"))
        // Failures aren't cached, and the 15 min pause after a network error lasts only as long as the process.
        assertFalse(File(app.noBackupFilesDir, "names.json").exists())
        lookups.clear()
        setFixAgo(2 * MIN)
        Here.refresh() // the same spot, still unnamed: paused (the same steps look it up below)
        simulate(41.8781, -87.6298, 9f)
        Thread.sleep(100)
        idle()
        assertTrue(lookups.toString(), lookups.isEmpty())

        // Back online in a new process: named at once.
        nominatim = { 200 to CHICAGO }
        reset()
        launch()
        setFixAgo(2 * MIN)
        Here.refresh()
        simulate(41.8781, -87.6298, 9f)
        waitFor { Here.place(app)?.name == "Chicago" }
        assertEquals(1, lookups.size)
    }

    @Test
    fun namesOffNoFetch() {
        seed(withPlaces = false)
        seedHere(Place("Chicago", "Illinois, United States", 41.88, -87.63, here = true), fixAgoMs = 5 * MIN)
        grant(FINE, COARSE)
        val a = launch().get()
        a.hereMenu().pick("Don't look up place names")
        waitFor { hereJson()?.optBoolean("names", true) == false }

        a.click(R.id.tab_now)
        a.click(R.id.refresh) // a 5 min old fix: locate again
        simulate(41.9001, -87.6298, 9f) // 2 km north
        assertEquals("here_41.900_-87.630", Here.place(app)?.key)
        Thread.sleep(300)
        idle()
        assertTrue(lookups.isEmpty())
        assertEquals("My location", Here.place(app)?.name)
        assertEquals("41.90° N, 87.63° W", Here.place(app)?.area)

        a.hereMenu().pick("Look up place names")
        waitFor { Here.place(app)?.name == "Chicago" }
        assertEquals(1, lookups.size)
        assertTrue(lookups[0], "lat=41.90&lon=-87.63&" in lookups[0])
    }

    @Test
    @Config(sdk = [30])
    fun android11UsesGps() {
        seed()
        grant(FINE, COARSE)
        lm.setProviderEnabled(NETWORK, true)
        val a = launch().get()
        a.tapInCities("Use my location")
        assertEquals(1, lm.getLocationUpdateListeners(GPS).size)
        fixNamed(41.8781, -87.6298, 9f, "Chicago")
        assertTrue(listeners().isEmpty())
        a.click(R.id.tab_now)
        assertEquals("Chicago", a.text(R.id.title))
    }
}
