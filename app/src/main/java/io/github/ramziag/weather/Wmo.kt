package io.github.ramziag.weather

/** WMO weather interpretation codes, as returned by Open-Meteo. */
object Wmo {
    fun label(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Freezing fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85 -> "Snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "—"
    }

    fun icon(code: Int, day: Boolean): Int = when (code) {
        0, 1 -> if (day) R.drawable.w_clear_day else R.drawable.w_clear_night
        2 -> if (day) R.drawable.w_partly_day else R.drawable.w_partly_night
        45, 48 -> R.drawable.w_fog
        51, 53, 55 -> R.drawable.w_drizzle
        56, 57, 66, 67 -> R.drawable.w_sleet
        61, 63, 65, 80, 81, 82 -> R.drawable.w_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.w_snow
        95, 96, 99 -> R.drawable.w_storm
        else -> R.drawable.w_cloudy
    }
}
