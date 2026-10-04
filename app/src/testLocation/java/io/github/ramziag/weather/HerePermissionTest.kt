package io.github.ramziag.weather

import android.app.Activity
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Weather+'s permission flow through the real dialog request and result, from a tap to My location. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class HerePermissionTest : HereFixture() {

    @Test
    fun requestsFineAndCoarse() {
        seed()
        val a = launch().get()
        assertNull("never asks by itself", shadowOf(a).lastRequestedPermission)
        a.tapInCities("Use my location")
        val asked = shadowOf(a).lastRequestedPermission
        assertEquals(REQUEST_LOCATION, asked.requestCode)
        assertEquals(listOf(FINE, COARSE), asked.requestedPermissions.toList())
        val dialog = asked(a)!!
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", dialog.intent.action)
        assertEquals(REQUEST_LOCATION, dialog.requestCode)
    }

    @Test
    fun cancelChangesNothing() {
        seed()
        val a = launch().get()
        a.tapInCities("Use my location")
        // Dismissed (or cancelled because another request was showing): empty result arrays. Even a permission
        // that is held by now must not switch My location on.
        grant(COARSE)
        shadowOf(a).receiveResult(asked(a)!!.intent, Activity.RESULT_CANCELED, null)
        idle()
        assertNull(Here.place(app))
        assertTrue(listeners().isEmpty())
        assertNull(hereJson())
        a.click(R.id.tab_now)
        a.tapInCities("Use my location")
        assertTrue(a.citiesButtons().any { it.text.toString() == "Locating…" }) // held now, so this tap locates
    }

    @Test
    fun grantPreciseShowsHere() {
        seed()
        val a = launch().get()
        a.tapInCities("Use my location")
        answer(a, FINE, COARSE)
        assertTrue(lm.getLocationUpdateListeners(GPS).isNotEmpty())
        assertTrue(a.citiesButtons().any { it.text.toString() == "Locating…" && !it.isEnabled })

        fixNamed(41.8781, -87.6298, 9f, "Chicago")
        assertTrue("one fix under 100 m ends the attempt", listeners().isEmpty())
        assertEquals(listOf("Chicago", "Springfield", "Paris", "Tokyo"), a.cityNames())
        a.click(R.id.tab_now)
        assertEquals("Chicago", a.text(R.id.title))
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Illinois · updated"))

        // Only the rounded spot is stored, never the fix itself.
        waitFor { hereJson()?.optJSONObject("place")?.optString("name") == "Chicago" }
        val saved = hereJson()!!
        assertTrue(saved.getBoolean("on"))
        assertFalse(saved.getBoolean("approx"))
        assertEquals(41.88, saved.getJSONObject("place").getDouble("lat"), 0.0)
        assertEquals(-87.63, saved.getJSONObject("place").getDouble("lon"), 0.0)
        assertFalse("41.8781" in saved.toString())
    }

    @Test
    fun grantApproximateShowsApproximate() {
        seed()
        lm.setProviderEnabled(NETWORK, true)
        val c = launch()
        val a = c.get()
        a.tapInCities("Use my location")
        answer(a, COARSE, rationale = true)
        fixNamed(41.8781, -87.6298, 2000f, "Chicago", NETWORK)
        a.click(R.id.tab_now)
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Illinois · approximate · updated"))
        val menu = a.hereMenu()
        assertTrue(menu.items().toString(), "Use precise location" in menu.items())

        // Upgrade turned down again for good: only App info can grant it now.
        menu.pick("Use precise location")
        assertEquals(listOf(FINE, COARSE), shadowOf(a).lastRequestedPermission.requestedPermissions.toList())
        answer(a, COARSE, rationale = false)
        val info = started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertEquals(Uri.parse("package:io.github.ramziag.weather.location"), info?.data)
        assertEquals("Chicago", Here.place(app)?.name) // still approximate, still there
        waitFor { hereJson()?.optBoolean("preciseBlocked") == true }

        // From then on the menu goes straight there: the dialog wouldn't show any more.
        a.hereMenu().pick("Use precise location")
        assertNull("no dialog", asked(a))
        assertNotNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))

        // Granted there: precise from now on.
        grant(FINE)
        c.pause().resume()
        idle()
        waitFor { hereJson()?.optBoolean("preciseBlocked") == false }
        a.click(R.id.tab_now)
        assertFalse(a.text(R.id.subtitle), "approximate" in a.text(R.id.subtitle))
        assertFalse("Use precise location" in a.hereMenu().items())
    }

    @Test
    fun denyKeepsPill() {
        seed()
        val a = launch().get()
        a.tapInCities("Use my location")
        answer(a, rationale = true)
        assertNull(Here.place(app))
        assertTrue(a.citiesButtons().any { it.text.toString() == "Use my location" })
        a.click(R.id.tab_now)
        assertEquals("Springfield", a.text(R.id.title))
        // It can still ask.
        a.tapInCities("Use my location")
        assertNotNull(asked(a))
    }

    /** Back on the dialog: the full arrays with nothing granted, and no rationale before or after. Not a denial. */
    @Test
    fun dismissKeepsAsking() {
        seed()
        val a = launch().get()
        a.tapInCities("Use my location")
        answer(a, rationale = false)
        assertNull(Here.place(app))
        assertFalse(a.citiesButtons().any { it.text.toString() == "Allow in Settings" })
        assertNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        waitFor { hereJson() != null }
        assertFalse(hereJson()!!.getBoolean("blocked"))

        // Still offered on the search page, and the pill asks again.
        a.tapInCities("Add city")
        assertTrue(a.visible(R.id.search_here))
        @Suppress("DEPRECATION") a.onBackPressed()
        idle()
        a.tapInCities("Use my location")
        assertNotNull(asked(a))
    }

    /** After an "Approximate" grant FINE has no rationale yet, so dismissing the upgrade isn't a refusal either. */
    @Test
    fun dismissedUpgradeOpensNothing() {
        seed()
        lm.setProviderEnabled(NETWORK, true)
        val a = launch().get()
        a.tapInCities("Use my location")
        answer(a, COARSE, rationale = false)
        fixNamed(41.8781, -87.6298, 2000f, "Chicago", NETWORK)

        a.hereMenu().pick("Use precise location")
        answer(a, COARSE, rationale = false) // Back
        assertNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))

        // It asks again; "Keep approximate" once, then a second time, which is for good.
        a.hereMenu().pick("Use precise location")
        answer(a, COARSE, rationale = true)
        assertNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        a.hereMenu().pick("Use precise location")
        answer(a, COARSE, rationale = false)
        assertNotNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
    }

    @Test
    fun secondDenyBlocks() {
        seed()
        val a = launch().get()
        a.tapInCities("Use my location")
        answer(a, rationale = true)
        a.tapInCities("Use my location")
        answer(a, rationale = false) // "Don't ask again": no dialog any more

        a.tapInCities("Allow in Settings")
        val info = started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertNotNull(info)
        assertNull("no dialog", asked(a))
        assertEquals(Uri.parse("package:io.github.ramziag.weather.location"), info!!.data)
        waitFor { hereJson()?.optBoolean("blocked") == true }

        // And the search page doesn't offer it.
        a.tapInCities("Add city")
        assertFalse(a.visible(R.id.search_here))
    }

    /**
     * The second "Don't allow" lands in a new process (the dialog runs in another one, so the app can die behind
     * it): the rationale seen before it is gone, but the first denial is on record in here.json.
     */
    @Test
    fun secondDenyInNewProcessBlocks() {
        seed()
        val a = launch().get()
        a.tapInCities("Use my location")
        answer(a, rationale = true)
        waitFor { hereJson()?.optBoolean("deniedOnce") == true }
        a.tapInCities("Use my location")
        Here::class.java.getDeclaredField("askedRationale").apply { isAccessible = true }.setBoolean(null, false)
        answer(a, rationale = false)

        assertTrue(a.citiesButtons().map { it.text }.toString(), a.citiesButtons().any { it.text.toString() == "Allow in Settings" })
        waitFor { hereJson()?.optBoolean("blocked") == true }
    }

    /**
     * Denied for good where the app never saw it (e.g. restored, or set by policy): every request ends at once,
     * with no rationale before or after. The second such answer in a row, even in a new process, leads to App
     * info rather than leaving a button that does nothing.
     */
    @Test
    fun silentRefusalsLeadToSettings() {
        seed()
        val a = launch().get()
        a.tapInCities("Use my location")
        answer(a, rationale = false)
        assertFalse(a.citiesButtons().any { it.text.toString() == "Allow in Settings" })
        waitFor { hereJson()?.optBoolean("deniedOnce") == true }

        reset()
        val c = launch()
        val b = c.get()
        b.tapInCities("Use my location")
        answer(b, rationale = false)
        b.tapInCities("Allow in Settings")
        assertNotNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        assertNull("no dialog", asked(b))

        // Allowed there: the record goes, so a later dismissal is again just a dismissal.
        grant(FINE, COARSE)
        c.pause().resume()
        idle()
        waitFor { hereJson()?.let { !it.getBoolean("blocked") && !it.getBoolean("deniedOnce") } == true }
    }

    /** The same for "Use precise location": a refusal on record from an earlier process still counts. */
    @Test
    fun secondUpgradeDenyInNewProcessOpensAppInfo() {
        seed()
        lm.setProviderEnabled(NETWORK, true)
        val a = launch().get()
        a.tapInCities("Use my location")
        answer(a, COARSE, rationale = false)
        fixNamed(41.8781, -87.6298, 2000f, "Chicago", NETWORK)
        a.hereMenu().pick("Use precise location")
        answer(a, COARSE, rationale = true) // "Keep approximate"
        waitFor { hereJson()?.optBoolean("preciseDeniedOnce") == true }

        reset()
        val b = launch().get()
        b.hereMenu().pick("Use precise location")
        Here::class.java.getDeclaredField("askedRationale").apply { isAccessible = true }.setBoolean(null, false)
        answer(b, COARSE, rationale = false)
        assertNotNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        waitFor { hereJson()?.optBoolean("preciseBlocked") == true }
    }

    @Test
    fun settingsGrantClearsBlocked() {
        seed()
        seedHere(on = false, blocked = true)
        val c = launch()
        val a = c.get()
        a.tapInCities("Allow in Settings")
        assertNotNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))

        // Allowed there, then back in the app.
        grant(FINE, COARSE)
        c.pause().resume()
        idle()
        waitFor { hereJson()?.optBoolean("blocked") == false }
        a.tapInCities("Use my location") // held now: locates straight away
        assertNull("no dialog", asked(a))
        assertTrue(lm.getLocationUpdateListeners(GPS).isNotEmpty())
    }

    @Test
    fun revokedShowsNotAllowedWithoutPrompt() {
        seed()
        seedHere(SPRINGFIELD_HERE)
        val c = launch() // the permission was taken back ("Only this time" ran out, or in Settings)
        val a = c.get()
        c.pause().resume()
        idle()
        assertNull("never asks by itself", shadowOf(a).lastRequestedPermission)
        assertNull(asked(a))
        assertTrue(listeners().isEmpty())
        assertEquals("Springfield", a.text(R.id.title)) // the last My location stays
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Location not allowed · updated"))
        a.click(R.id.tab_cities)
        assertEquals("Location not allowed", a.cityRow(0).findViewById<TextView>(R.id.area).text.toString())

        // A tap asks again; granted, it locates.
        a.tapInCities("Allow location")
        answer(a, FINE, COARSE)
        assertTrue(lm.getLocationUpdateListeners(GPS).isNotEmpty())
        a.click(R.id.tab_now)
        assertTrue(a.text(R.id.subtitle), a.text(R.id.subtitle).startsWith("Locating…"))
    }
}
