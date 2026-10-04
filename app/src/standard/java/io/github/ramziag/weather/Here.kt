package io.github.ramziag.weather

import android.content.Context

/**
 * Weather, the original app, never uses location: "My location" is always absent and every hook does nothing.
 * Shared code checks [ENABLED] first, so these calls compile away. Weather+ has the real one in src/location,
 * with the same signatures.
 */
object Here {
    const val ENABLED = false

    fun place(c: Context): Place? = null
    fun attach(h: HereHost?) {}
    fun resume() {}
    fun pause() {}
    fun tick(viewingHere: Boolean) {}
    fun left() {}
    fun cancelSearch() {}
    fun refresh() {}
    fun widgetOpened() {}
    fun onPermissionsResult(code: Int) {}
    fun note(): String? = null
    fun approximate(): Boolean = false
    fun icon(): Int = 0
    fun text(c: Context, which: Int): String? = null
    fun action(slot: Int): HereAction? = null
    fun menu(p: Place) {}
    fun me(): DoubleArray? = null
    fun locateOnRadar(v: RadarView) {}
    fun resetForTest() {}
}
