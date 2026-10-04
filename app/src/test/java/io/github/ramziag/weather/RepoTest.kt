package io.github.ramziag.weather

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Repo's cache bookkeeping, with Open-Meteo answered by fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepoTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val berlin = Place("Berlin", "Germany", 52.52437, 13.41053)
    private val file get() = File(app.cacheDir, "forecasts/${berlin.key}.json")

    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @Before
    fun setUp() {
        TestData.resetSingletons()
        TestData.seed(app, theme = 0)
    }

    /** Open-Meteo answers with fixtures; with [hold], only once [release] is counted down. */
    private fun answer(hold: Boolean) {
        OpenMeteo.fetch = { url ->
            started.countDown()
            if (hold) assertTrue(release.await(5, TimeUnit.SECONDS))
            if (url.endsWith("forecast_days=1")) { // the summaries request
                JSONArray(javaClass.classLoader!!.getResource("summaries.json")!!.readText()).getJSONObject(0).toString()
            } else {
                TestData.forecastJson(System.currentTimeMillis())
            }
        }
    }

    /** Waits until Repo's background work and what it posts back to the main thread are all done. */
    private fun settle(repo: Repo) {
        val io = Repo::class.java.getDeclaredField("io").apply { isAccessible = true }.get(repo) as ThreadPoolExecutor
        val main = shadowOf(Looper.getMainLooper())
        val end = System.nanoTime() + 5_000_000_000
        do {
            main.idle()
            Thread.sleep(20)
        } while ((io.completedTaskCount < io.taskCount || !main.isIdle) && System.nanoTime() < end)
    }

    private fun savedKeys(): Set<String> = JSONObject(File(app.cacheDir, "summaries.json").readText()).keys().asSequence().toSet()

    /** Removing a city (or My location moving away) while its forecast is still loading. */
    @Test
    fun forgetDuringForecastFetchSticks() {
        val repo = Repo.get(app)
        answer(hold = true)
        repo.loadForecast(berlin, force = true)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        repo.forget(berlin)
        release.countDown()
        settle(repo)

        assertNull(repo.cached(berlin))
        assertNull(repo.summary(berlin))
        assertFalse(file.exists())
    }

    @Test
    fun forgetDuringSummariesFetchSticks() {
        val repo = Repo.get(app)
        answer(hold = true)
        repo.loadSummaries(listOf(berlin), force = true)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        repo.forget(berlin)
        release.countDown()
        settle(repo)

        assertNull(repo.summary(berlin))
        assertFalse(berlin.key in savedKeys())
        assertTrue(TestData.paris.key in savedKeys())
    }

    /** Weather+: a My location spot left in summaries.json (the app died mid-move) is dropped, not shown or kept. */
    @Test
    fun leftoverMyLocationSpotIsDropped() {
        val spot = Place("Chicago", "Illinois", 41.88, -87.63, here = true)
        val f = File(app.cacheDir, "summaries.json")
        val o = JSONObject(f.readText())
        f.writeText(o.put(spot.key, o.getJSONObject(TestData.paris.key)).toString())

        val repo = Repo.get(app)
        answer(hold = false)
        repo.loadSummaries(listOf(TestData.paris), force = false)
        settle(repo)

        assertNull(repo.summary(spot))
        assertFalse(spot.key in savedKeys())
        assertTrue(TestData.paris.key in savedKeys())
    }

    /** Adding the city back (or returning to a spot) loads it as usual. */
    @Test
    fun loadingAgainAfterForget() {
        val repo = Repo.get(app)
        answer(hold = false)
        repo.forget(berlin)
        repo.loadForecast(berlin, force = false)
        settle(repo)
        assertNotNull(repo.cached(berlin))
        assertTrue(file.exists())

        repo.forget(berlin)
        repo.loadSummaries(listOf(berlin), force = false)
        settle(repo)
        assertNotNull(repo.summary(berlin))
        assertTrue(berlin.key in savedKeys())
    }
}
