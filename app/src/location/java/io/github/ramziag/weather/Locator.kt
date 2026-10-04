package io.github.ramziag.weather

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One location attempt at a time, on the main thread: a recent last-known fix if there is one, otherwise
 * updates from a single provider until a good fix arrives or 45 s pass. Every fix goes to [onFix] as it
 * arrives (provisional ones too); [onEnd] then gets the phase to show next: [IDLE] (got one), [OFF] or [NO_FIX].
 * Robolectric ignores the location switch and request durations, so both are enforced here.
 */
@SuppressLint("MissingPermission") // Here checks the permission first; a SecurityException still means no fix
class Locator(
    context: Context,
    private val onFix: (Fix) -> Unit,
    private val onSlow: () -> Unit,
    private val onEnd: (Int) -> Unit,
) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val executor = context.mainExecutor
    private val main = Handler(Looper.getMainLooper())

    private var precise = false
    private var best: Fix? = null

    /** True while a listener is registered. */
    var running = false
        private set

    private val listener = LocationListener { arrived(it.toFix()) }
    private val slow = Runnable { onSlow() }
    private val watchdog = Runnable { finish(if (best != null) IDLE else NO_FIX) }

    fun enabled(): Boolean = lm.isLocationEnabled

    fun start(precise: Boolean) {
        stop()
        this.precise = precise
        best = null
        try {
            if (!lm.isLocationEnabled) return onEnd(OFF)
            val now = SystemClock.elapsedRealtimeNanos()
            val last = Geo.pickBest(lastKnown(), now, precise)
            if (last != null) {
                onFix(last)
                if (Geo.isFresh(last, now, precise)) return onEnd(IDLE)
            }
            val p = provider() ?: return onEnd(NO_FIX)
            if (Build.VERSION.SDK_INT >= 31) request31(p) else lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            running = true
            main.postDelayed(slow, SLOW_MS)
            main.postDelayed(watchdog, TIMEOUT_MS)
        } catch (e: SecurityException) {
            finish(NO_FIX)
        } catch (e: IllegalArgumentException) {
            finish(NO_FIX)
        }
    }

    fun stop() {
        main.removeCallbacks(slow)
        main.removeCallbacks(watchdog)
        if (running) {
            running = false
            lm.removeUpdates(listener)
        }
    }

    private fun finish(phase: Int) {
        stop()
        onEnd(phase)
    }

    /** Keeps the most accurate fix seen; stops at the first one under 100 m (or any, when approximate). */
    private fun arrived(f: Fix) {
        if (!running) return
        val b = best
        if (b == null || f.accM <= b.accM) {
            best = f
            onFix(f)
        }
        if (Geo.isGood(f, precise)) finish(IDLE)
    }

    private fun lastKnown(): List<Fix> {
        val names = if (hasFused()) arrayOf(FUSED, GPS, NETWORK, PASSIVE) else arrayOf(GPS, NETWORK, PASSIVE)
        return names.mapNotNull { lm.getLastKnownLocation(it)?.toFix() }
    }

    /**
     * Precise prefers fused (it adds network positions when that is on). Approximate never uses fused: coarse
     * requests are forced to low power, where fused may never start GPS. Android 11 hides fused altogether.
     */
    private fun provider(): String? {
        val order = when {
            Build.VERSION.SDK_INT < 31 -> arrayOf(GPS, NETWORK)
            precise -> arrayOf(FUSED, GPS, NETWORK)
            else -> arrayOf(NETWORK, GPS)
        }
        return order.firstOrNull { (it != FUSED || hasFused()) && lm.isProviderEnabled(it) }
    }

    private fun hasFused() = Build.VERSION.SDK_INT >= 31 && lm.hasProvider(FUSED)

    @TargetApi(31)
    private fun request31(provider: String) {
        val request = LocationRequest.Builder(1000)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setDurationMillis(TIMEOUT_MS)
            .build()
        lm.requestLocationUpdates(provider, request, executor, listener)
    }

    private fun Location.toFix() = Fix(latitude, longitude, if (hasAccuracy()) accuracy else NO_ACCURACY_M, elapsedRealtimeNanos)

    companion object {
        // Phases, shared with Here.
        const val IDLE = 0
        const val LOCATING = 1
        const val OFF = 2
        const val NO_FIX = 3

        const val SLOW_MS = 8_000L
        const val TIMEOUT_MS = 45_000L

        private const val FUSED = "fused" // LocationManager.FUSED_PROVIDER, API 31
        private const val GPS = LocationManager.GPS_PROVIDER
        private const val NETWORK = LocationManager.NETWORK_PROVIDER
        private const val PASSIVE = LocationManager.PASSIVE_PROVIDER

        /** Treat a fix without accuracy like Android's approximate location. */
        private const val NO_ACCURACY_M = 2000f
    }
}

/** A position fix as plain numbers, so the rules in [Geo] run on the JVM. [nanos] is elapsed-realtime time. */
class Fix(val lat: Double, val lon: Double, val accM: Float, val nanos: Long)

/** Pure rules for fixes and rounded spots. */
object Geo {
    private const val MIN_NS = 60_000_000_000L
    private const val EARTH_M = 6_371_008.8

    /** To 0.01° (about 1 km), never "-0.0". */
    fun round2(v: Double): Double = Math.round(v * 100) / 100.0

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_M * asin(sqrt(h.coerceAtMost(1.0)))
    }

    /** e.g. "39.80° N, 89.64° W": the area shown while (or instead of) looking up a place name. */
    fun coords(lat: Double, lon: Double): String {
        val la = round2(lat)
        val lo = round2(lon)
        return String.format(Locale.US, "%.2f° %s, %.2f° %s", abs(la), if (la < 0) "S" else "N", abs(lo), if (lo < 0) "W" else "E")
    }

    fun ageMs(f: Fix, nowNanos: Long): Long = max(0L, nowNanos - f.nanos) / 1_000_000

    /** Good enough to stop looking: at most 2 min old and 100 m (precise), or at most 10 min old (approximate). */
    fun isFresh(f: Fix, nowNanos: Long, precise: Boolean): Boolean =
        if (precise) ageNs(f, nowNanos) <= 2 * MIN_NS && f.accM <= 100f else ageNs(f, nowNanos) <= 10 * MIN_NS

    /** A live fix that ends the attempt. */
    fun isGood(f: Fix, precise: Boolean): Boolean = !precise || f.accM <= 100f

    /**
     * The last-known fix worth using, from several providers: the most accurate fresh one, else the newest one
     * at most 30 min old (shown while locating), else null.
     */
    fun pickBest(fixes: List<Fix>, nowNanos: Long, precise: Boolean): Fix? {
        val recent = fixes.filter { ageNs(it, nowNanos) <= 30 * MIN_NS }
        return recent.filter { isFresh(it, nowNanos, precise) }.minByOrNull { it.accM } ?: recent.maxByOrNull { it.nanos }
    }

    /** Whether a fix leaves the spot centred on [lat], [lon]: GPS jitter and cell edges don't count. */
    fun shouldMove(lat: Double, lon: Double, f: Fix): Boolean =
        distanceM(lat, lon, f.lat, f.lon) > max(1000.0, f.accM.toDouble())

    private fun ageNs(f: Fix, nowNanos: Long) = max(0L, nowNanos - f.nanos)
}
