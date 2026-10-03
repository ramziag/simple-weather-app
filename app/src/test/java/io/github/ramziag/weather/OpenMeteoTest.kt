package io.github.ramziag.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class OpenMeteoTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val forecast = OpenMeteo.parseForecast(fixture("forecast.json"), fetchedAt = 1234L)

    /** Epoch millis for a wall-clock time at the fixture's location (UTC-5). */
    private fun at(local: String) =
        LocalDateTime.parse(local).toEpochSecond(ZoneOffset.ofHours(-5)) * 1000

    @Test
    fun parsesCurrentConditions() {
        val c = forecast.current
        assertEquals(LocalDateTime.of(2026, 10, 3, 14, 15), c.time)
        assertEquals(19.6, c.temp, 0.0)
        assertEquals(18.9, c.feels, 0.0)
        assertEquals(48.0, c.humidity, 0.0)
        assertEquals(2, c.code)
        assertTrue(c.isDay)
        assertEquals(315.0, c.windDir, 0.0)
        assertEquals(-18000, forecast.utcOffsetSeconds)
        assertEquals(1234L, forecast.fetchedAt)
    }

    @Test
    fun parsesHourlyAndDailySeries() {
        assertEquals(240, forecast.hours.size)
        assertEquals(10, forecast.days.size)
        val day = forecast.days[0]
        assertEquals(LocalDate.of(2026, 10, 3), day.date)
        assertEquals(20.0, day.max, 0.0)
        assertEquals(8.0, day.min, 0.0)
        assertEquals(LocalDateTime.of(2026, 10, 3, 7, 0), day.sunrise)
        assertEquals(LocalDateTime.of(2026, 10, 3, 18, 30), day.sunset)
    }

    @Test
    fun nullsBecomeNaN() {
        assertTrue(forecast.hours[239].pop.isNaN())
        assertTrue(forecast.days[9].pop.isNaN())
        assertFalse(forecast.hours[0].pop.isNaN())
    }

    @Test
    fun upcomingHoursStartAtTheCurrentLocalHour() {
        val hours = forecast.upcomingHours(48, now = at("2026-10-03T14:40"))
        assertEquals(48, hours.size)
        assertEquals(LocalDateTime.of(2026, 10, 3, 14, 0), hours.first().time)
        assertEquals(LocalDateTime.of(2026, 10, 5, 13, 0), hours.last().time)
    }

    @Test
    fun staleCacheDropsPastDays() {
        val days = forecast.upcomingDays(now = at("2026-10-04T09:00"))
        assertEquals(9, days.size)
        assertEquals(LocalDate.of(2026, 10, 4), days.first().date)
        assertEquals(LocalDate.of(2026, 10, 4), forecast.today(now = at("2026-10-04T09:00"))!!.date)
    }

    @Test
    fun parsesMultiLocationSummariesInOrder() {
        val list = OpenMeteo.parseSummaries(fixture("summaries.json"), fetchedAt = 5L)
        assertEquals(2, list.size)
        assertEquals(15.2, list[0].temp, 0.0)
        assertEquals(17.0, list[0].max, 0.0)
        assertEquals(61, list[1].code)
        assertFalse(list[1].isDay)
        assertTrue(list[1].min.isNaN())
    }

    @Test
    fun parsesSingleLocationSummary() {
        val single = fixture("summaries.json").let { org.json.JSONArray(it).getJSONObject(0).toString() }
        val list = OpenMeteo.parseSummaries(single, fetchedAt = 5L)
        assertEquals(1, list.size)
        assertEquals(3, list[0].code)
    }

    @Test
    fun searchResultsAndQualifiers() {
        val hits = OpenMeteo.parseSearch(fixture("search.json"))
        assertEquals(4, hits.size)
        val il = hits[1]
        assertEquals("Springfield", il.place.name)
        assertEquals("Illinois, United States", il.place.area)
        assertTrue(il.matches("IL"))
        assertTrue(il.matches("illinois"))
        assertTrue(il.matches("us"))
        assertFalse(il.matches("MO"))
        assertTrue(hits[0].matches("MO"))
        assertTrue(hits[2].matches("Mass"))
        // JSON null admin1 must not leak in as the string "null".
        assertEquals("New Zealand", hits[3].place.area)
        assertTrue(hits[3].matches("nz"))
    }

    @Test
    fun placeRoundTripsThroughJson() {
        val p = Place("Springfield", "Illinois, United States", 39.80172, -89.64371)
        val back = Place.parse(p.toJson().toString())!!
        assertEquals(p, back)
        assertEquals(p.area, back.area)
        assertEquals(listOf(p), Place.parseList(Place.listJson(listOf(p))))
        assertNull(Place.parse("not json"))
    }

    @Test
    fun forecastUrlAsksForTenDaysInLocalTime() {
        val url = OpenMeteo.forecastUrl(Place("X", "", 39.8, -89.6))
        assertTrue(url, url.contains("latitude=39.8000&longitude=-89.6000"))
        assertTrue(url.contains("forecast_days=10"))
        assertTrue(url.contains("timezone=auto"))
    }
}
