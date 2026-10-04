package io.github.ramziag.weather

/**
 * The shared side of "My location". Each flavor has its own `object Here`: in src/standard every hook is a
 * no-op (that app has no location permission or code), in src/location it locates. Shared code reaches it
 * only through `Here`, these types and [Place.here], inside `if (Here.ENABLED)`. MainActivity implements this
 * interface so Here can update the screen.
 */
interface HereHost {
    /** Here's place, name or status changed; [moved] means it is now a different place (a new forecast key). */
    fun hereChanged(moved: Boolean)

    /** The search page's "Use my location" found [p]: use it like a picked search result. */
    fun pickPlace(p: Place)

    fun makeHometown(p: Place)

    fun saveCity(p: Place)
}

/** A button whose label and action depend on Here's state, e.g. "Use my location" or "Allow in Settings". */
class HereAction(val label: CharSequence, val icon: Int, val enabled: Boolean, val run: () -> Unit)

/** Stands for "My location" where a place's JSON would go: a widget's choice and its open-app extra. */
const val HERE_MARKER = "here"

/** requestPermissions() code for the location permissions. */
const val REQUEST_LOCATION = 7

// Where `Here.action(slot)` buttons go.
const val SLOT_WELCOME = 0
const val SLOT_CITIES = 1
const val SLOT_SEARCH = 2

// Weather+ strings shown by shared code, via `Here.text(c, which)`: back chip, welcome hint, widget option, the
// search page's status while its "Use my location" runs, and the header's "approximate".
const val TEXT_BACK = 0
const val TEXT_HINT = 1
const val TEXT_WIDGET = 2
const val TEXT_SEARCH = 3
const val TEXT_APPROX = 4
