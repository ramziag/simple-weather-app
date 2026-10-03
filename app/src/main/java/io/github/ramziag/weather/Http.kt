package io.github.ramziag.weather

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Minimal HTTP GET helpers (plain JVM, no Android classes). */
object Http {
    const val USER_AGENT = "SimpleWeather (Android; https://github.com/ramziag/simple-weather-app)"

    fun open(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /** API calls skip the HTTP disk cache (map tiles use it); freshness is handled by [Repo]. */
    fun text(url: String): String {
        val c = open(url).apply { useCaches = false }
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

    fun bytes(url: String): ByteArray {
        val c = open(url).apply { useCaches = false }
        try {
            if (c.responseCode !in 200..299) throw IOException("Server error ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
