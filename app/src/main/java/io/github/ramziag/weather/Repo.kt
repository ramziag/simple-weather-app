package io.github.ramziag.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * Forecast cache and loader. Every forecast is kept in memory and on disk, so the app opens instantly
 * (and works offline) with the last data, and only hits the network when data is older than 15 minutes
 * or the user taps refresh. All maps and callbacks live on the main thread; only I/O runs on [io].
 */
class Repo private constructor(context: Context) {

    interface Listener {
        fun onForecast(place: Place, forecast: Forecast?, error: String?)
        fun onSummaries(error: String?)
    }

    var listener: Listener? = null

    private val dir = File(context.cacheDir, "forecasts").apply { mkdirs() }
    private val summaryFile = File(context.cacheDir, "summaries.json")
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private val forecasts = HashMap<String, Forecast>()
    private val summaries = HashMap<String, Summary>()
    private val busy = HashSet<String>()
    private var summariesBusy = false
    private var summariesFromDisk = false

    fun cached(place: Place): Forecast? = forecasts[place.key]

    fun summary(place: Place): Summary? = summaries[place.key]

    fun loadForecast(place: Place, force: Boolean) {
        val key = place.key
        val mem = forecasts[key]
        if (mem != null && !force && mem.isFresh()) return
        if (!busy.add(key)) return
        val file = File(dir, "$key.json")
        io.execute {
            if (mem == null) {
                val disk = readForecast(file)
                if (disk != null) {
                    main.post { if (forecasts[key] == null) publish(place, disk) }
                    if (!force && disk.isFresh()) {
                        main.post { busy.remove(key) }
                        return@execute
                    }
                }
            }
            try {
                val (body, forecast) = OpenMeteo.fetchForecast(place)
                writeAtomic(file, "${forecast.fetchedAt}\n$body")
                main.post {
                    busy.remove(key)
                    publish(place, forecast)
                }
            } catch (e: Exception) {
                main.post {
                    busy.remove(key)
                    listener?.onForecast(place, forecasts[key], describe(e))
                }
            }
        }
    }

    private fun publish(place: Place, forecast: Forecast) {
        forecasts[place.key] = forecast
        summaries[place.key] = forecast.summary()
        listener?.onForecast(place, forecast, null)
    }

    fun loadSummaries(places: List<Place>, force: Boolean) {
        if (places.isEmpty() || summariesBusy) return
        val now = System.currentTimeMillis()
        val stale = places.filter { force || summaries[it.key]?.isFresh(now) != true }
        if (stale.isEmpty()) return
        summariesBusy = true
        val readDisk = !summariesFromDisk
        io.execute {
            val disk = if (readDisk) readSummaries() else emptyMap()
            if (readDisk) {
                main.post {
                    summariesFromDisk = true
                    for ((k, s) in disk) if ((summaries[k]?.fetchedAt ?: 0) < s.fetchedAt) summaries[k] = s
                    if (disk.isNotEmpty()) listener?.onSummaries(null)
                }
            }
            val todo = stale.filter { force || disk[it.key]?.isFresh(now) != true }
            if (todo.isEmpty()) {
                main.post { summariesBusy = false }
                return@execute
            }
            try {
                val fresh = OpenMeteo.fetchSummaries(todo)
                main.post {
                    summariesBusy = false
                    todo.zip(fresh).forEach { (p, s) -> summaries[p.key] = s }
                    saveSummaries()
                    listener?.onSummaries(null)
                }
            } catch (e: Exception) {
                main.post {
                    summariesBusy = false
                    listener?.onSummaries(describe(e))
                }
            }
        }
    }

    fun search(query: String, callback: (List<Place>?, String?) -> Unit) {
        io.execute {
            try {
                val places = OpenMeteo.search(query)
                main.post { callback(places, null) }
            } catch (e: Exception) {
                main.post { callback(null, describe(e)) }
            }
        }
    }

    /** Drops cached data for a city the user removed. */
    fun forget(place: Place) {
        forecasts.remove(place.key)
        summaries.remove(place.key)
        val file = File(dir, "${place.key}.json")
        io.execute { file.delete() }
        saveSummaries()
    }

    private fun readForecast(file: File): Forecast? = runCatching {
        val text = file.readText()
        val nl = text.indexOf('\n')
        OpenMeteo.parseForecast(text.substring(nl + 1), text.substring(0, nl).toLong())
    }.getOrNull()

    private fun readSummaries(): Map<String, Summary> = runCatching {
        val o = JSONObject(summaryFile.readText())
        o.keys().asSequence().associateWith { k ->
            val s = o.getJSONObject(k)
            Summary(
                s.optDouble("temp", Double.NaN), s.optInt("code", -1), s.optBoolean("day", true),
                s.optDouble("max", Double.NaN), s.optDouble("min", Double.NaN), s.optLong("at"),
            )
        }
    }.getOrDefault(emptyMap())

    private fun saveSummaries() {
        val o = JSONObject()
        for ((k, s) in summaries) {
            o.put(
                k,
                JSONObject().put("code", s.code).put("day", s.isDay).put("at", s.fetchedAt)
                    .putFinite("temp", s.temp).putFinite("max", s.max).putFinite("min", s.min),
            )
        }
        val text = o.toString()
        io.execute { runCatching { writeAtomic(summaryFile, text) } }
    }

    private fun JSONObject.putFinite(key: String, v: Double): JSONObject = if (v.isNaN()) this else put(key, v)

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("Could not write ${file.name}")
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "Offline"
        is SocketTimeoutException -> "Timed out"
        is IOException -> e.message ?: "Network error"
        else -> "Unexpected response"
    }

    companion object {
        @Volatile
        private var instance: Repo? = null

        fun get(context: Context): Repo = instance ?: synchronized(this) {
            instance ?: Repo(context.applicationContext).also { instance = it }
        }
    }
}
