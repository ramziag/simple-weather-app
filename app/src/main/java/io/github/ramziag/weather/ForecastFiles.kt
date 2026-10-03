package io.github.ramziag.weather

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The on-disk forecast cache, shared by the app ([Repo]) and the home-screen widget. Each file is
 * "<fetchedAt millis>\n<raw Open-Meteo JSON>". Thread-safe: writes go to a unique temp file and are renamed
 * into place, so a reader never sees half a file even if the app and the widget write at the same time.
 */
object ForecastFiles {

    fun file(context: Context, place: Place) = File(dir(context), "${place.key}.json")

    fun read(file: File): Forecast? = runCatching {
        val text = file.readText()
        val nl = text.indexOf('\n')
        OpenMeteo.parseForecast(text.substring(nl + 1), text.substring(0, nl).toLong())
    }.getOrNull()

    /** When the cached forecast was fetched, reading only the first line; null if there is none. */
    fun fetchedAt(file: File): Long? = runCatching { file.bufferedReader().use { it.readLine() }.toLong() }.getOrNull()

    fun write(file: File, fetchedAt: Long, body: String) = writeAtomic(file, "$fetchedAt\n$body")

    /** Blocking: the cached forecast if it's newer than [maxAgeMs], otherwise a fresh one (cached on success). */
    fun load(context: Context, place: Place, maxAgeMs: Long): Forecast {
        val file = file(context, place)
        read(file)?.takeIf { System.currentTimeMillis() - it.fetchedAt < maxAgeMs }?.let { return it }
        val (body, forecast) = OpenMeteo.fetchForecast(place)
        write(file, forecast.fetchedAt, body)
        return forecast
    }

    fun writeAtomic(file: File, text: String) {
        val dir = file.parentFile ?: throw IOException("No directory for ${file.name}")
        val tmp = File.createTempFile(file.name, ".tmp", dir)
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) throw IOException("Could not write ${file.name}")
        } finally {
            tmp.delete()
        }
    }

    private fun dir(context: Context) = File(context.cacheDir, "forecasts").apply { mkdirs() }
}
