package io.github.ramziag.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Unit conversion and text formatting. Forecast data is always metric; conversion happens here. */
class Fmt(val imperial: Boolean, is24Hour: Boolean, private val locale: Locale = Locale.getDefault()) {

    private val hourFormat = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h a", locale)
    private val timeFormat = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)
    private val shortDay = DateTimeFormatter.ofPattern("EEE d", locale)
    private val longDay = DateTimeFormatter.ofPattern("EEEE, MMM d", locale)

    val unit: String get() = if (imperial) "°F" else "°C"

    fun temp(c: Double?): String {
        if (c == null || c.isNaN()) return "–"
        return "${(if (imperial) c * 9 / 5 + 32 else c).roundToInt()}°"
    }

    fun wind(kmh: Double?): String {
        if (kmh == null || kmh.isNaN()) return "–"
        return if (imperial) "${(kmh / 1.609344).roundToInt()} mph" else "${kmh.roundToInt()} km/h"
    }

    fun precip(mm: Double?): String {
        if (mm == null || mm.isNaN()) return "–"
        return if (imperial) String.format(locale, "%.2f in", mm / 25.4) else String.format(locale, "%.1f mm", mm)
    }

    fun pressure(hpa: Double?): String {
        if (hpa == null || hpa.isNaN()) return "–"
        return if (imperial) String.format(locale, "%.2f inHg", hpa * 0.02953) else "${hpa.roundToInt()} hPa"
    }

    fun percent(v: Double?): String = if (v == null || v.isNaN()) "–" else "${v.roundToInt()}%"

    fun uv(v: Double?): String {
        if (v == null || v.isNaN()) return "–"
        val level = when {
            v < 3 -> "low"
            v < 6 -> "moderate"
            v < 8 -> "high"
            v < 11 -> "very high"
            else -> "extreme"
        }
        return "${v.roundToInt()} · $level"
    }

    fun compass(deg: Double): String {
        if (deg.isNaN()) return ""
        return DIRECTIONS[(((deg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]
    }

    fun hour(t: LocalDateTime): String = hourFormat.format(t)

    fun time(t: LocalDateTime?): String = t?.let(timeFormat::format) ?: "–"

    /** "Today", "Tomorrow" or "Monday, Oct 6". */
    fun dayHeading(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> longDay.format(d)
    }

    /** "Today" or "Mon 6". */
    fun dayShort(d: LocalDate, today: LocalDate): String = if (d == today) "Today" else shortDay.format(d)

    fun ago(then: Long, now: Long = System.currentTimeMillis()): String {
        val min = (now - then) / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    private companion object {
        val DIRECTIONS = arrayOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
        )
    }
}
