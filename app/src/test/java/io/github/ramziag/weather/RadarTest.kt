package io.github.ramziag.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarTest {
    private val maps = Radar.parse(javaClass.classLoader!!.getResource("weather-maps.json")!!.readText(), fetchedAt = 7L)

    @Test
    fun parsesFramesOldestFirst() {
        assertEquals("https://tilecache.rainviewer.com", maps.host)
        assertEquals(13, maps.frames.size)
        assertTrue(maps.frames.zipWithNext().all { (a, b) -> a.time < b.time })
        assertEquals(1791019800L, maps.frames.first().time)
        assertEquals(7L, maps.fetchedAt)
    }

    @Test
    fun missingRadarMeansNoFrames() {
        assertEquals(0, Radar.parse("""{"host":"https://h"}""", 0).frames.size)
    }

    @Test
    fun tileUrls() {
        val f = maps.frames.last()
        assertEquals("https://tilecache.rainviewer.com${f.path}/256/7/68/41/2/1_1.png", Radar.radarTileUrl(maps, f, 7, 68, 41))
        assertEquals("https://tile.openstreetmap.org/7/68/41.png", Radar.baseTileUrl(7, 68, 41))
    }

    @Test
    fun webMercator() {
        assertEquals(0.5, Radar.mercatorX(0.0), 1e-12)
        assertEquals(0.5, Radar.mercatorY(0.0), 1e-12)
        assertEquals(0.0, Radar.mercatorX(-180.0), 1e-12)
        assertEquals(0.0, Radar.mercatorY(89.9), 1e-9)
        // Berlin lands on tile 68/41 at zoom 7.
        assertEquals(68, (Radar.mercatorX(13.41) * 128).toInt())
        assertEquals(41, (Radar.mercatorY(52.52) * 128).toInt())
    }
}
