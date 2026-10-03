package io.github.ramziag.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// All values are metric (°C, km/h, mm, hPa) and times are local to the forecast location.
// Missing values are NaN.

class Current(
    val time: LocalDateTime,
    val temp: Double,
    val feels: Double,
    val humidity: Double,
    val precip: Double,
    val code: Int,
    val isDay: Boolean,
    val cloud: Double,
    val pressure: Double,
    val wind: Double,
    val windDir: Double,
    val gusts: Double,
)

class Hour(
    val time: LocalDateTime,
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val pop: Double,
    val wind: Double,
)

class Day(
    val date: LocalDate,
    val code: Int,
    val max: Double,
    val min: Double,
    val pop: Double,
    val precip: Double,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val uv: Double,
    val windMax: Double,
)

class Forecast(
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
    val utcOffsetSeconds: Int,
    val fetchedAt: Long,
) {
    fun isFresh(now: Long = System.currentTimeMillis()) = now - fetchedAt < OpenMeteo.STALE_MS

    /** Wall-clock time at the forecast location. */
    fun localNow(now: Long = System.currentTimeMillis()): LocalDateTime =
        LocalDateTime.ofEpochSecond(Math.floorDiv(now, 1000L), 0, ZoneOffset.ofTotalSeconds(utcOffsetSeconds))

    /** Hours from the current one onward, so a cached forecast never shows the past. */
    fun upcomingHours(count: Int, now: Long = System.currentTimeMillis()): List<Hour> {
        val from = localNow(now).truncatedTo(ChronoUnit.HOURS)
        return hours.asSequence().filter { !it.time.isBefore(from) }.take(count).toList()
    }

    fun upcomingDays(now: Long = System.currentTimeMillis()): List<Day> {
        val today = localNow(now).toLocalDate()
        return days.filter { !it.date.isBefore(today) }
    }

    fun today(now: Long = System.currentTimeMillis()): Day? = upcomingDays(now).firstOrNull()

    fun summary(): Summary {
        val today = today()
        return Summary(current.temp, current.code, current.isDay, today?.max ?: Double.NaN, today?.min ?: Double.NaN, fetchedAt)
    }
}

/** The little bit of data the Cities list needs per place. */
class Summary(
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val max: Double,
    val min: Double,
    val fetchedAt: Long,
) {
    fun isFresh(now: Long = System.currentTimeMillis()) = now - fetchedAt < OpenMeteo.STALE_MS
}
