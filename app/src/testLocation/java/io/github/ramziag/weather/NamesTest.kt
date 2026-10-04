package io.github.ramziag.weather

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class NamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private val calls = ArrayList<Pair<Long, String>>()
    private var reply: () -> Pair<Int, String> = { 200 to town("Springfield") }

    private val file get() = tmp.root.resolve("names.json")

    private fun names() = Names(file, clock = { now }, sleep = { now += it })

    private fun town(name: String) =
        """{"addresstype":"town","name":"$name","address":{"town":"$name","state":"Illinois","country":"United States"}}"""

    init {
        Nominatim.fetch = { url ->
            calls += now to url
            reply()
        }
    }

    @After
    fun restore() {
        Nominatim.fetch = Nominatim::http
    }

    @Test
    fun cachedByRoundedSpotAndLanguage() {
        val n = names()
        assertEquals("Springfield" to "Illinois, United States", n.lookup(39.8017, -89.6437, "en", network = true))
        assertEquals("Springfield" to "Illinois, United States", n.lookup(39.798, -89.641, "en", network = true))
        assertEquals(1, calls.size)
        assertTrue(calls[0].second.contains("lat=39.80&lon=-89.64"))
        n.lookup(39.8017, -89.6437, "de", network = true)
        assertEquals(2, calls.size)
        // Kept on disk, and without network only the cache answers.
        assertEquals("Springfield", names().lookup(39.80, -89.64, "en", network = false)?.first)
        assertNull(names().lookup(41.0, -89.0, "en", network = false))
        assertEquals(2, calls.size)
    }

    @Test
    fun keepsTwentyMostRecentlyUsed() {
        val n = names()
        for (i in 0 until 20) {
            reply = { 200 to town("T$i") }
            n.lookup(40.0 + i / 10.0, -89.0, "en", network = true)
        }
        n.lookup(40.0, -89.0, "en", network = true) // T0 used again, so T1 is now the oldest
        reply = { 200 to town("T20") }
        n.lookup(42.0, -89.0, "en", network = true)
        assertEquals(21, calls.size)
        val again = names()
        assertEquals("T0", again.lookup(40.0, -89.0, "en", network = false)?.first)
        assertNull(again.lookup(40.1, -89.0, "en", network = false))
        assertEquals("T2", again.lookup(40.2, -89.0, "en", network = false)?.first)
        assertEquals("T20", again.lookup(42.0, -89.0, "en", network = false)?.first)
    }

    @Test
    fun notFoundExpiresAfter30Days() {
        val n = names()
        reply = { 200 to """{"error":"Unable to geocode"}""" }
        assertNull(n.lookup(30.0, -40.0, "en", network = true))
        now += 29L * 24 * 3600_000
        assertNull(n.lookup(30.0, -40.0, "en", network = true))
        assertEquals(1, calls.size)
        now += 2L * 24 * 3600_000
        reply = { 200 to town("Atlantis") }
        assertEquals("Atlantis", n.lookup(30.0, -40.0, "en", network = true)?.first)
        assertEquals(2, calls.size)
    }

    @Test
    fun backsOff15MinutesAfterNetworkErrors429And5xx() {
        val failures = listOf<() -> Pair<Int, String>>({ throw IOException("offline") }, { 429 to "" }, { 503 to "" })
        for (fail in failures) {
            calls.clear()
            val n = names()
            reply = fail
            assertNull(n.lookup(39.8, -89.64, "en", network = true))
            reply = { 200 to town("Springfield") }
            now += 14 * 60_000
            assertNull(n.lookup(39.8, -89.64, "en", network = true))
            assertEquals(1, calls.size)
            now += 2 * 60_000
            assertEquals("Springfield", n.lookup(39.8, -89.64, "en", network = true)?.first)
            assertEquals(2, calls.size)
            file.delete()
        }
    }

    @Test
    fun backsOffADayAfter403() {
        val n = names()
        reply = { 403 to "" }
        assertNull(n.lookup(39.8, -89.64, "en", network = true))
        reply = { 200 to town("Springfield") }
        now += 23L * 3600_000
        assertNull(n.lookup(39.8, -89.64, "en", network = true))
        assertEquals(1, calls.size)
        now += 2L * 3600_000
        assertEquals("Springfield", n.lookup(39.8, -89.64, "en", network = true)?.first)
    }

    @Test
    fun requestsAtLeast1100MsApart() {
        val n = names()
        for (i in 0 until 4) {
            n.lookup(40.0 + i, -89.0, "en", network = true)
            now += 300
        }
        assertEquals(4, calls.size)
        calls.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 1100) }
    }
}
