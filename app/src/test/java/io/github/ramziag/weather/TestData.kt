package io.github.ramziag.weather

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Shared Robolectric fixtures: saved places, settings and fresh cached forecasts. */
object TestData {
    val home = Place("Springfield", "Illinois, United States", 39.80172, -89.64371)
    val paris = Place("Paris", "Île-de-France, France", 48.85341, 2.3488)
    val tokyo = Place("Tokyo", "Tokyo, Japan", 35.6895, 139.69171)

    /** Store, Repo and Here live for the whole process; give every test a clean one. */
    fun resetSingletons() {
        for (c in listOf(Store::class.java, Repo::class.java)) {
            c.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
        Here.resetForTest()
    }

    /** Hometown + two cities (and no My location), °F, the given theme, and forecasts cached [ageMs] ago. */
    fun seed(app: Context, theme: Int, withPlaces: Boolean = true, ageMs: Long = 0) {
        val prefs = app.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().clear()
        if (withPlaces) {
            prefs.putString("home", home.toJson().toString())
            prefs.putString("cities", Place.listJson(listOf(paris, tokyo)))
        }
        prefs.putBoolean("imperial", true).putInt("theme", theme).commit()
        for (name in listOf("here.json", "names.json")) File(app.noBackupFilesDir, name).delete()

        val now = System.currentTimeMillis()
        val body = forecastJson(now)
        val dir = File(app.cacheDir, "forecasts").apply { mkdirs() }
        for (p in listOf(home, paris, tokyo)) File(dir, "${p.key}.json").writeText("${now - ageMs}\n$body")
        File(app.cacheDir, "summaries.json").writeText(
            """{"${paris.key}":{"temp":15.2,"code":3,"day":true,"max":17,"min":9.5,"at":$now},""" +
                """"${tokyo.key}":{"temp":21,"code":61,"day":false,"max":24.1,"min":18.2,"at":$now}}""",
        )
    }

    /** The fixture forecast (Springfield, UTC-5) with its dates moved so that its first day is today there. */
    fun forecastJson(now: Long): String {
        val today = LocalDateTime.ofEpochSecond(now / 1000, 0, ZoneOffset.ofHours(-5)).toLocalDate()
        val shift = ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 3), today)
        val raw = TestData::class.java.classLoader!!.getResource("forecast.json")!!.readText()
        return Regex("2026-10-(\\d\\d)").replace(raw) {
            LocalDate.of(2026, 10, it.groupValues[1].toInt()).plusDays(shift).toString()
        }
    }
}
