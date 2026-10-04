package io.github.ramziag.weather

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A saved location. [area] is the human readable region, e.g. "Illinois, United States". [here] marks Weather+'s
 * "My location", whose key never equals a saved place's.
 */
class Place(val name: String, val area: String, val lat: Double, val lon: Double, val here: Boolean = false) {

    /** Stable identity: two search hits for the same spot are the same place. */
    val key: String = String.format(Locale.US, if (here) "here_%.3f_%.3f" else "%.3f_%.3f", lat, lon)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("area", area)
        .put("lat", lat)
        .put("lon", lon)
        .apply { if (here) put("here", true) }

    /** The same spot as an ordinary place, to keep "My location" as the hometown or a city. */
    fun plain() = Place(name, area, lat, lon)

    override fun equals(other: Any?) = other is Place && other.key == key

    override fun hashCode() = key.hashCode()

    override fun toString() = if (area.isEmpty()) name else "$name, $area"

    companion object {
        fun fromJson(o: JSONObject) =
            Place(o.getString("name"), o.optString("area"), o.getDouble("lat"), o.getDouble("lon"), o.optBoolean("here"))

        fun parse(json: String): Place? = runCatching { fromJson(JSONObject(json)) }.getOrNull()

        fun parseList(json: String): List<Place> = runCatching {
            val a = JSONArray(json)
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun listJson(places: List<Place>): String =
            JSONArray().apply { places.forEach { put(it.toJson()) } }.toString()
    }
}
