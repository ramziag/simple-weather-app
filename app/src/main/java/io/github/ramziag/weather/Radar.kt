package io.github.ramziag.weather

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Precipitation radar from RainViewer (https://www.rainviewer.com/api.html; free for personal use, no key)
 * drawn over standard OpenStreetMap tiles (no key; the app identifies itself, caches tiles and shows the
 * attribution, as the OSM tile usage policy asks). Pure JVM so it can be unit tested.
 */
object Radar {
    const val MAPS_URL = "https://api.rainviewer.com/public/weather-maps.json"

    /** RainViewer's free tier serves radar tiles up to this zoom; closer views scale them up. */
    const val MAX_RADAR_ZOOM = 7
    const val STALE_MS = 5 * 60 * 1000L

    class Frame(val time: Long, val path: String)

    /** Available radar frames, oldest first (past ~2 hours, every 10 minutes). */
    class Maps(val host: String, val frames: List<Frame>, val fetchedAt: Long) {
        fun isFresh(now: Long = System.currentTimeMillis()) = now - fetchedAt < STALE_MS
    }

    fun fetch(): Maps = parse(Http.text(MAPS_URL), System.currentTimeMillis())

    fun parse(body: String, fetchedAt: Long): Maps {
        val o = JSONObject(body)
        val past = o.optJSONObject("radar")?.optJSONArray("past")
        val frames = List(past?.length() ?: 0) { i ->
            val f = past!!.getJSONObject(i)
            Frame(f.getLong("time"), f.getString("path"))
        }
        return Maps(o.getString("host"), frames.sortedBy { it.time }, fetchedAt)
    }

    /** 256 px radar tile; colour scheme 2 ("Universal Blue", the free one), smoothed, with snow. */
    fun radarTileUrl(maps: Maps, frame: Frame, z: Int, x: Int, y: Int) =
        "${maps.host}${frame.path}/256/$z/$x/$y/2/1_1.png"

    /** 256 px OpenStreetMap base map tile. */
    fun baseTileUrl(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"

    // Web Mercator, as fractions of the world square: x in 0..1 west to east, y in 0..1 north to south.

    fun mercatorX(lon: Double) = (lon + 180.0) / 360.0

    fun mercatorY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }
}
