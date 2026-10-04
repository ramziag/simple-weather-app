package io.github.ramziag.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Properties

/** Weather, the original app, must never ask for location or be able to locate (CI checks the APK too). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoLocationTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetSingletons() = TestData.resetSingletons()

    @Test
    fun requestedPermissionsAreInternetOnly() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(listOf(Manifest.permission.INTERNET), info.requestedPermissions?.toList())
    }

    /** The manifest this variant was built with, after merging in the flavor's and any library's. */
    @Test
    fun mergedManifestHasNoLocation() {
        val config = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use(config::load)
        assertFalse(File(config.getProperty("android_merged_manifest")).readText().contains("LOCATION"))
    }

    @Test
    fun hereIsInert() {
        assertFalse(Here.ENABLED)
        assertNull(Here.place(app))
    }

    /** Weather+'s placeholders stay hidden on the welcome screen and the search page. */
    @Test
    fun noLocationViews() {
        TestData.seed(app, theme = 1, withPlaces = false)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.now_empty).visibility)
        a.findViewById<View>(R.id.choose_home).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        for (id in intArrayOf(R.id.use_location, R.id.use_location_hint, R.id.search_here)) {
            assertEquals(View.GONE, a.findViewById<View>(id).visibility)
        }
    }
}
