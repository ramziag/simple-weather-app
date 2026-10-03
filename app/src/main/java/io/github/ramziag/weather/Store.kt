package io.github.ramziag.weather

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** User choices that survive restarts: hometown, saved cities, units and theme. */
class Store private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    var home: Place? = prefs.getString(KEY_HOME, null)?.let(Place::parse)
        set(value) {
            field = value
            prefs.edit().putString(KEY_HOME, value?.toJson()?.toString()).apply()
        }

    var cities: List<Place> = prefs.getString(KEY_CITIES, null)?.let(Place::parseList).orEmpty()
        set(value) {
            field = value
            prefs.edit().putString(KEY_CITIES, Place.listJson(value)).apply()
        }

    var imperial: Boolean = prefs.getBoolean(KEY_IMPERIAL, Locale.getDefault().country in IMPERIAL_REGIONS)
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY_IMPERIAL, value).apply()
        }

    var theme: Int = prefs.getInt(KEY_THEME, 0)
        set(value) {
            field = value
            prefs.edit().putInt(KEY_THEME, value).apply()
        }

    /** Hometown first, then saved cities. */
    val allPlaces: List<Place> get() = listOfNotNull(home) + cities

    companion object {
        private const val KEY_HOME = "home"
        private const val KEY_CITIES = "cities"
        private const val KEY_IMPERIAL = "imperial"
        private const val KEY_THEME = "theme"

        private val IMPERIAL_REGIONS = setOf("US", "LR", "MM", "BS", "KY", "PW", "FM", "MH")

        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }
}

object Themes {
    val NAMES = arrayOf("Auto (Sky / Dusk)", "Sky", "Mint", "Peach", "Lavender", "Rose", "Lemon", "Dusk", "Moss")

    private val STYLES = intArrayOf(
        0,
        R.style.Theme_Weather_Sky,
        R.style.Theme_Weather_Mint,
        R.style.Theme_Weather_Peach,
        R.style.Theme_Weather_Lavender,
        R.style.Theme_Weather_Rose,
        R.style.Theme_Weather_Lemon,
        R.style.Theme_Weather_Dusk,
        R.style.Theme_Weather_Moss,
    )

    fun style(index: Int, config: Configuration): Int {
        if (index in 1 until STYLES.size) return STYLES[index]
        val night = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) R.style.Theme_Weather_Dusk else R.style.Theme_Weather_Sky
    }
}
