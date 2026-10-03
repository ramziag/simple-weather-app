package io.github.ramziag.weather

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class FmtTest {
    private val us = Fmt(imperial = true, is24Hour = false, locale = Locale.US)
    private val metric = Fmt(imperial = false, is24Hour = true, locale = Locale.UK)

    @Test
    fun temperatures() {
        assertEquals("68°", us.temp(20.0))
        assertEquals("32°", us.temp(0.0))
        assertEquals("20°", metric.temp(20.0))
        assertEquals("-3°", metric.temp(-2.6))
        assertEquals("0°", metric.temp(-0.4))
        assertEquals("–", metric.temp(Double.NaN))
        assertEquals("–", metric.temp(null))
    }

    @Test
    fun windPrecipPressure() {
        assertEquals("10 mph", us.wind(16.09))
        assertEquals("16 km/h", metric.wind(16.09))
        assertEquals("0.50 in", us.precip(12.7))
        assertEquals("12.7 mm", metric.precip(12.7))
        assertEquals("30.01 inHg", us.pressure(1016.4))
        assertEquals("1016 hPa", metric.pressure(1016.4))
    }

    @Test
    fun compassPoints() {
        assertEquals("N", us.compass(0.0))
        assertEquals("N", us.compass(355.0))
        assertEquals("NW", us.compass(315.0))
        assertEquals("SSW", us.compass(200.0))
        assertEquals("", us.compass(Double.NaN))
    }

    @Test
    fun times() {
        val t = LocalDateTime.of(2026, 10, 3, 15, 5)
        assertEquals("3 PM", us.hour(t))
        assertEquals("3:05 PM", us.time(t))
        assertEquals("15:00", metric.hour(t.withMinute(0)))
        val today = LocalDate.of(2026, 10, 3)
        assertEquals("Today", us.dayHeading(today, today))
        assertEquals("Tomorrow", us.dayHeading(today.plusDays(1), today))
        assertEquals("Monday, Oct 5", us.dayHeading(today.plusDays(2), today))
        assertEquals("Mon 5", us.dayShort(today.plusDays(2), today))
    }

    @Test
    fun ages() {
        assertEquals("just now", us.ago(0, 30_000))
        assertEquals("5 min ago", us.ago(0, 5 * 60_000))
        assertEquals("3 h ago", us.ago(0, 3 * 3_600_000))
    }

    @Test
    fun uvLevels() {
        assertEquals("5 · moderate", us.uv(4.65))
        assertEquals("–", us.uv(null))
    }
}
