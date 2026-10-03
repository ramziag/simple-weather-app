package io.github.ramziag.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Hits the real Open-Meteo API. Skipped unless LIVE_API=1 (CI sets it) so local runs work offline. */
class LiveApiTest {

    private val berlin = Place("Berlin", "Germany", 52.52, 13.41)
    private val tokyo = Place("Tokyo", "Japan", 35.69, 139.69)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("LIVE_API") == "1")

    @Test
    fun forecast() {
        val (body, f) = OpenMeteo.fetchForecast(berlin)
        println("forecast response (head): " + body.take(700))
        assertFalse(f.current.temp.isNaN())
        assertTrue(f.current.code >= 0)
        assertEquals(10, f.days.size)
        assertTrue(f.upcomingHours(48).size == 48)
        assertTrue(f.days.all { !it.max.isNaN() && !it.min.isNaN() && it.sunrise != null })
        assertTrue(f.utcOffsetSeconds == 3600 || f.utcOffsetSeconds == 7200)
    }

    @Test
    fun summariesForSeveralCities() {
        val list = OpenMeteo.fetchSummaries(listOf(berlin, tokyo))
        assertEquals(2, list.size)
        assertTrue(list.all { !it.temp.isNaN() && !it.max.isNaN() && !it.min.isNaN() })
        assertEquals(1, OpenMeteo.fetchSummaries(listOf(tokyo)).size)
    }

    @Test
    fun radarAndMapTiles() {
        val maps = Radar.fetch()
        println("radar: host=${maps.host} frames=${maps.frames.size} latest=${maps.frames.lastOrNull()?.path}")
        assertTrue(maps.frames.size >= 6)
        val z = Radar.MAX_RADAR_ZOOM
        val x = (Radar.mercatorX(berlin.lon) * (1 shl z)).toInt()
        val y = (Radar.mercatorY(berlin.lat) * (1 shl z)).toInt()
        assertPng(Http.bytes(Radar.radarTileUrl(maps, maps.frames.last(), z, x, y)))
        assertPng(Http.bytes(Radar.radarTileUrl(maps, maps.frames.first(), z - 2, x shr 2, y shr 2)))
        assertPng(Http.bytes(Radar.baseTileUrl(z, x, y)))
        assertPng(Http.bytes(Radar.baseTileUrl(10, x shl 3, y shl 3)))
    }

    private fun assertPng(bytes: ByteArray) {
        val png = bytes.size > 100 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        assertTrue("expected a PNG, got ${bytes.size} bytes", png)
    }

    @Test
    fun search() {
        val hits = OpenMeteo.search("Springfield, IL", "en")
        println("search: " + hits.take(3))
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().area.startsWith("Illinois"))
        assertTrue(OpenMeteo.search("Berlin", "en").first().area.contains("Germany"))
    }
}
