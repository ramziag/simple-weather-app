package io.github.ramziag.weather

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Reverse geocoding with OpenStreetMap's Nominatim (https://nominatim.org): the town a rounded spot is in.
 * Free and keyless, but at most one request per second and only with caching (see [Names]).
 * Pure JVM code (no Android classes) so it can be unit tested directly.
 */
object Nominatim {
    private const val REVERSE = "https://nominatim.openstreetmap.org/reverse"

    private val TOWNS = setOf("city", "town", "village", "hamlet")

    /** Test hook: GET [url] and return the HTTP status and body. */
    internal var fetch: (String) -> Pair<Int, String> = ::http

    /** zoom=13 reaches villages and suburbs; layer=address keeps out lakes, parks and bays. */
    fun url(lat: Double, lon: Double, lang: String) = String.format(
        Locale.US,
        "$REVERSE?format=jsonv2&lat=%.2f&lon=%.2f&zoom=13&layer=address&addressdetails=1&accept-language=%s",
        Geo.round2(lat), Geo.round2(lon), lang,
    )

    /**
     * (name, area) from a jsonv2 reply, or null when there is nothing there (e.g. mid-ocean). The result's own
     * name only counts when it is a town: in cities it is usually a suburb, and an "address.city" can be an
     * administrative area around a village. The area follows OpenMeteo.parseSearch: "Illinois, United States".
     */
    fun parse(body: String, lang: String): Pair<String, String>? {
        val o = JSONObject(body)
        if (o.has("error")) return null
        val a = o.optJSONObject("address") ?: JSONObject()
        var name = if (str(o, "addresstype") in TOWNS) str(o, "name") else ""
        if (name.isEmpty()) name = first(a, "city", "town", "village", "hamlet", "municipality", "suburb", "city_district", "county")
        if (lang == "en") name = name.removePrefix("City of ")
        // Never "region": French replies carry "Metropolitan France" there.
        val admin1 = first(a, "state", "province", "state_district")
        val country = str(a, "country")
        if (name.isEmpty()) name = admin1.ifEmpty { country }
        if (name.isEmpty()) return null
        return name to listOf(admin1, country).filter { it.isNotEmpty() && it != name }.distinct().joinToString(", ")
    }

    private fun first(o: JSONObject, vararg keys: String) = keys.firstNotNullOfOrNull { str(o, it).ifEmpty { null } } ?: ""

    private fun str(o: JSONObject, key: String) = if (o.isNull(key)) "" else o.optString(key).trim()

    internal fun http(url: String): Pair<Int, String> {
        val c = Http.open(url).apply { useCaches = false }
        try {
            val code = c.responseCode
            return code to if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Place names by rounded spot and language, so each ~1 km cell is looked up once: the 20 most recently used
 * live in [file] (names forever, "nothing here" for 30 days). Requests go out at least 1.1 s apart; after a
 * network error, 429 or 5xx none for 15 min, after a 403 (blocked) none for a day. Blocking, so call it from a
 * background thread; one lookup runs at a time.
 */
class Names(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private class Entry(val name: String?, val area: String, val at: Long)

    private val map = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX
    }
    private var loaded = false
    private var lastAt = Long.MIN_VALUE / 2
    private var until = Long.MIN_VALUE

    /** The cached name, else (when [network]) Nominatim's; null when unknown, not found or unavailable. */
    @Synchronized
    fun lookup(lat: Double, lon: Double, lang: String, network: Boolean): Pair<String, String>? {
        load()
        val key = String.format(Locale.US, "%s|%.2f_%.2f", lang, Geo.round2(lat), Geo.round2(lon))
        map[key]?.let { e ->
            if (e.name != null) return (e.name to e.area).also { save() } // keeps the LRU order
            if (clock() - e.at < NOT_FOUND_MS) return null
        }
        if (!network || clock() < until) return null
        val wait = lastAt + SPACING_MS - clock()
        if (wait > 0) sleep(wait)
        lastAt = clock()
        val result = try {
            val (code, body) = Nominatim.fetch(Nominatim.url(lat, lon, lang))
            if (code !in 200..299) {
                until = lastAt + if (code == 403) BLOCKED_MS else BACKOFF_MS
                return null
            }
            Nominatim.parse(body, lang)
        } catch (e: Exception) {
            until = lastAt + BACKOFF_MS // offline, timeout or an unreadable reply
            return null
        }
        map[key] = Entry(result?.first, result?.second.orEmpty(), lastAt)
        save()
        return result
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                map[o.getString("k")] = Entry(if (o.has("n")) o.getString("n") else null, o.optString("a"), o.getLong("t"))
            }
        }
    }

    /** Least recently used first, so [load] rebuilds the same order. */
    private fun save() {
        val a = JSONArray()
        for ((k, e) in map) a.put(JSONObject().put("k", k).put("n", e.name).put("a", e.area).put("t", e.at))
        runCatching { ForecastFiles.writeAtomic(file, a.toString()) }
    }

    companion object {
        const val MAX = 20
        const val SPACING_MS = 1_100L
        const val BACKOFF_MS = 15 * 60_000L
        const val BLOCKED_MS = 24 * 60 * 60_000L
        const val NOT_FOUND_MS = 30 * 24 * 60 * 60_000L
    }
}
