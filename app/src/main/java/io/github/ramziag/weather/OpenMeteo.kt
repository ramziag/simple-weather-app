package io.github.ramziag.weather

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Open-Meteo client (https://open-meteo.com). Free, no API key, no account.
 * Pure JVM code (no Android classes) so it can be unit tested directly.
 */
object OpenMeteo {
    const val STALE_MS = 15 * 60 * 1000L

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

    private const val CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation," +
        "weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
    private const val HOURLY = "temperature_2m,precipitation_probability,weather_code,is_day,wind_speed_10m"
    private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
        "precipitation_sum,sunrise,sunset,uv_index_max,wind_speed_10m_max"

    // ---- URLs -------------------------------------------------------------------------------------------

    fun forecastUrl(p: Place) = "$FORECAST?latitude=${coord(p.lat)}&longitude=${coord(p.lon)}" +
        "&current=$CURRENT&hourly=$HOURLY&daily=$DAILY&timezone=auto&forecast_days=10"

    /** One request for every saved city: current conditions plus today's high and low. */
    fun summaryUrl(places: List<Place>) = FORECAST +
        "?latitude=" + places.joinToString(",") { coord(it.lat) } +
        "&longitude=" + places.joinToString(",") { coord(it.lon) } +
        "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
        "&timezone=auto&forecast_days=1"

    fun searchUrl(name: String, count: Int, language: String) = GEOCODE +
        "?name=" + URLEncoder.encode(name, "UTF-8") + "&count=$count&language=$language&format=json"

    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    // ---- Network ----------------------------------------------------------------------------------------

    fun fetchForecast(p: Place): Pair<String, Forecast> {
        val body = get(forecastUrl(p))
        return body to parseForecast(body, System.currentTimeMillis())
    }

    fun fetchSummaries(places: List<Place>): List<Summary> =
        parseSummaries(get(summaryUrl(places)), System.currentTimeMillis())

    /**
     * Place search. "Springfield, IL" or "Paris, FR" style queries are split: the part before the comma
     * is searched, the rest narrows results by state/region/country (falls back to all hits if nothing matches).
     */
    fun search(query: String, language: String = Locale.getDefault().language.ifEmpty { "en" }): List<Place> {
        val comma = query.indexOf(',')
        val name = (if (comma >= 0) query.substring(0, comma) else query).trim()
        val qualifier = if (comma >= 0) query.substring(comma + 1).trim() else ""
        if (name.length < 2) return emptyList()
        val hits = parseSearch(get(searchUrl(name, if (qualifier.isEmpty()) 10 else 30, language)))
        if (qualifier.isEmpty()) return hits.map { it.place }
        val narrowed = hits.filter { it.matches(qualifier) }
        return (narrowed.ifEmpty { hits }).take(10).map { it.place }
    }

    private fun get(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "SimpleWeather (Android)")
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }
                val reason = err?.let { runCatching { JSONObject(it).optString("reason") }.getOrNull() }
                throw IOException(if (reason.isNullOrEmpty()) "Server error $code" else reason)
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---- Parsing ----------------------------------------------------------------------------------------

    fun parseForecast(body: String, fetchedAt: Long): Forecast {
        val o = JSONObject(body)
        val c = o.getJSONObject("current")
        val current = Current(
            time = LocalDateTime.parse(c.getString("time")),
            temp = c.num("temperature_2m"),
            feels = c.num("apparent_temperature"),
            humidity = c.num("relative_humidity_2m"),
            precip = c.num("precipitation"),
            code = c.optInt("weather_code", -1),
            isDay = c.optInt("is_day", 1) == 1,
            cloud = c.num("cloud_cover"),
            pressure = c.num("pressure_msl"),
            wind = c.num("wind_speed_10m"),
            windDir = c.num("wind_direction_10m"),
            gusts = c.num("wind_gusts_10m"),
        )

        val h = o.getJSONObject("hourly")
        val hTime = h.getJSONArray("time")
        val hTemp = h.optJSONArray("temperature_2m")
        val hPop = h.optJSONArray("precipitation_probability")
        val hCode = h.optJSONArray("weather_code")
        val hDay = h.optJSONArray("is_day")
        val hWind = h.optJSONArray("wind_speed_10m")
        val hours = List(hTime.length()) { i ->
            Hour(
                time = LocalDateTime.parse(hTime.getString(i)),
                temp = hTemp.num(i),
                code = hCode?.optInt(i, -1) ?: -1,
                isDay = (hDay?.optInt(i, 1) ?: 1) == 1,
                pop = hPop.num(i),
                wind = hWind.num(i),
            )
        }

        val d = o.getJSONObject("daily")
        val dTime = d.getJSONArray("time")
        val dCode = d.optJSONArray("weather_code")
        val dMax = d.optJSONArray("temperature_2m_max")
        val dMin = d.optJSONArray("temperature_2m_min")
        val dPop = d.optJSONArray("precipitation_probability_max")
        val dSum = d.optJSONArray("precipitation_sum")
        val dRise = d.optJSONArray("sunrise")
        val dSet = d.optJSONArray("sunset")
        val dUv = d.optJSONArray("uv_index_max")
        val dWind = d.optJSONArray("wind_speed_10m_max")
        val days = List(dTime.length()) { i ->
            Day(
                date = LocalDate.parse(dTime.getString(i)),
                code = dCode?.optInt(i, -1) ?: -1,
                max = dMax.num(i),
                min = dMin.num(i),
                pop = dPop.num(i),
                precip = dSum.num(i),
                sunrise = dRise.time(i),
                sunset = dSet.time(i),
                uv = dUv.num(i),
                windMax = dWind.num(i),
            )
        }

        return Forecast(current, hours, days, o.optInt("utc_offset_seconds", 0), fetchedAt)
    }

    /** Multi-location responses are a JSON array (in request order); a single location is a bare object. */
    fun parseSummaries(body: String, fetchedAt: Long): List<Summary> {
        val text = body.trimStart()
        val all = if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        return List(all.length()) { i ->
            val o = all.getJSONObject(i)
            val c = o.getJSONObject("current")
            val d = o.optJSONObject("daily")
            Summary(
                temp = c.num("temperature_2m"),
                code = c.optInt("weather_code", -1),
                isDay = c.optInt("is_day", 1) == 1,
                max = d?.optJSONArray("temperature_2m_max").num(0),
                min = d?.optJSONArray("temperature_2m_min").num(0),
                fetchedAt = fetchedAt,
            )
        }
    }

    class Hit(val place: Place, private val countryCode: String, private val admin1: String, private val admin2: String) {
        fun matches(qualifier: String): Boolean {
            val q = qualifier.lowercase(Locale.ROOT)
            val cc = countryCode.lowercase(Locale.ROOT)
            if (q == cc || (q == "uk" && cc == "gb") || (q == "usa" && cc == "us")) return true
            if (cc == "us" && US_STATES[q]?.equals(admin1, ignoreCase = true) == true) return true
            return listOf(admin1, admin2, place.area).any { it.lowercase(Locale.ROOT).startsWith(q) }
        }
    }

    fun parseSearch(body: String): List<Hit> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return List(results.length()) { i ->
            val o = results.getJSONObject(i)
            val name = o.str("name")
            val admin1 = o.str("admin1")
            val country = o.str("country")
            val area = listOf(admin1, country).filter { it.isNotEmpty() && it != name }.joinToString(", ")
            Hit(Place(name, area, o.getDouble("latitude"), o.getDouble("longitude")), o.str("country_code"), admin1, o.str("admin2"))
        }
    }

    // org.json returns the string "null" for JSON nulls, so check explicitly.
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONObject.num(key: String): Double = optDouble(key, Double.NaN)

    private fun JSONArray?.num(i: Int): Double = this?.optDouble(i, Double.NaN) ?: Double.NaN

    private fun JSONArray?.time(i: Int): LocalDateTime? {
        if (this == null || isNull(i)) return null
        return runCatching { LocalDateTime.parse(getString(i)) }.getOrNull()
    }

    private val US_STATES: Map<String, String> = (
        "al:Alabama,ak:Alaska,az:Arizona,ar:Arkansas,ca:California,co:Colorado,ct:Connecticut,de:Delaware," +
            "dc:District of Columbia,fl:Florida,ga:Georgia,hi:Hawaii,id:Idaho,il:Illinois,in:Indiana,ia:Iowa," +
            "ks:Kansas,ky:Kentucky,la:Louisiana,me:Maine,md:Maryland,ma:Massachusetts,mi:Michigan,mn:Minnesota," +
            "ms:Mississippi,mo:Missouri,mt:Montana,ne:Nebraska,nv:Nevada,nh:New Hampshire,nj:New Jersey," +
            "nm:New Mexico,ny:New York,nc:North Carolina,nd:North Dakota,oh:Ohio,ok:Oklahoma,or:Oregon," +
            "pa:Pennsylvania,ri:Rhode Island,sc:South Carolina,sd:South Dakota,tn:Tennessee,tx:Texas,ut:Utah," +
            "vt:Vermont,va:Virginia,wa:Washington,wv:West Virginia,wi:Wisconsin,wy:Wyoming,pr:Puerto Rico"
        ).split(',').associate { it.substringBefore(':') to it.substringAfter(':') }
}
