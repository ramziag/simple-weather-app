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
        val a = launch().get()
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

        // Granted after all: precise from now on.
        a.hereMenu().pick("Use precise location")
        answer(a, FINE, COARSE)
        assertNull(started(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
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
