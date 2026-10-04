package io.github.ramziag.weather

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Weather+'s "My location": a [Place] with `here = true` at the phone's position rounded to 0.01° (about 1 km),
 * named after a saved place within 3 km, a cached or looked-up town, or else its coordinates. It locates only
 * while MainActivity is resumed, the user turned it on and the permission is held, and it never asks for the
 * permission without a tap. The raw fix stays in memory; noBackupFilesDir/here.json keeps the rounded place
 * and the flags. Main thread only, except [place], which the widget also reads from its own threads.
 */
object Here {
    const val ENABLED = true

    private const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
    private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
    private const val MIN = 60_000L
    private const val NAMING_MS = 10_000L
    private const val STATE = "here.json"
    private const val NAMES = "names.json"

    // Saved in here.json.
    @Volatile private var loaded = false
    @Volatile private var on = false
    @Volatile private var here: Place? = null
    private var blocked = false // denied for good: only App info can grant it now
    private var preciseBlocked = false // the same for "Use precise location"
    private var deniedOnce = false // a request already ended without the permission: the next silent "no" is for good
    private var preciseDeniedOnce = false // the same for "Use precise location"
    private var names = true // look up place names on the network
    private var fixAt = 0L // wall-clock time of the newest fix used
    private var failAt = 0L
    private var approx = false

    // Memory only.
    @Volatile private var app: Context? = null
    private var host: HereHost? = null
    private var locator: Locator? = null
    private var fix: Fix? = null
    private var phase = Locator.IDLE
    private var slow = false
    private var resumed = false
    private var pending = false // locate once resumed: permission results and widget taps arrive while paused
    private var fromWidget = false
    private var askedForSearch = false
    private var upgrading = false
    private var askedRationale = false // what the rationale check said just before the dialog
    private var revokeOnLeave = false // "Stop using location": give the permission back once the app is left
    private var revoked = false // given back; it stays granted until the system kills the process
    private var watchingMode = false
    private var searching = false
    private var searchStatus: String? = null
    private var searchSeq = 0
    private var swept = false
    private var main = Handler(Looper.getMainLooper())
    private var io: ExecutorService = Executors.newSingleThreadExecutor() // files
    private var net: ExecutorService = Executors.newSingleThreadExecutor() // name lookups, one at a time
    private var cache: Names? = null // used on [net] only

    private val starter = Runnable { if (resumed && phase == Locator.LOCATING) start() }

    /** The location switch, watched while resumed: Quick Settings doesn't pause the app, so resume() can't see it. */
    private val modeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!resumed || phase != Locator.OFF || !loc().enabled()) return
            phase = Locator.IDLE
            if (on && has(COARSE)) locate() else changed(false)
        }
    }

    /** My location when it is on and has been found, whatever the permission says now; null otherwise. */
    fun place(c: Context): Place? {
        load(c)
        return if (on) here else null
    }

    fun attach(h: HereHost?) {
        host = h
        val c = (h as? Context)?.let(::load) ?: return
        if (swept) return
        swept = true
        io.execute { sweep(c) }
    }

    fun resume() {
        if (app == null) return
        resumed = true
        val coarse = has(COARSE)
        if (coarse) {
            val a = !has(FINE)
            val pb = preciseBlocked && a
            val pd = preciseDeniedOnce && a
            if (blocked || deniedOnce || approx != a || preciseBlocked != pb || preciseDeniedOnce != pd) {
                blocked = false
                deniedOnce = false
                approx = a
                preciseBlocked = pb
                preciseDeniedOnce = pd
                save()
            }
            if (phase == Locator.OFF && loc().enabled()) phase = Locator.IDLE
        }
        if (!watchingMode) {
            watchingMode = true
            app?.registerReceiver(modeReceiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED)
        }
        val want = pending || (on && ((fromWidget && age() > 2 * MIN) || due(10 * MIN)))
        pending = false
        fromWidget = false
        if (want && coarse && locator?.running != true) {
            // Starts after the first frame; until then the screen already says "Locating…".
            phase = Locator.LOCATING
            slow = false
            postStart()
        }
    }

    fun pause() {
        resumed = false
        unwatchMode()
        cancelStart()
        locator?.stop()
        if (phase == Locator.LOCATING) phase = Locator.IDLE
        if (searching) {
            searching = false
            searchStatus = null
            searchSeq++
        }
    }

    /**
     * Once a minute from MainActivity's ticker: keeps My location current while it is on screen. At most every
     * 15 min, failed attempts included: due()'s quick retry is for coming back to the app, not for polling the GPS.
     */
    fun tick(viewingHere: Boolean) {
        val idle = System.currentTimeMillis() - maxOf(fixAt, failAt)
        if (viewingHere && on && resumed && phase != Locator.LOCATING && idle > 15 * MIN) locate()
    }

    /** MainActivity stopped (left, not recreated): the moment to give the permission back after "Stop using location". */
    fun left() {
        if (revokeOnLeave && !on && Build.VERSION.SDK_INT >= 33) {
            runCatching { app?.revokeSelfPermissionsOnKill(listOf(FINE, COARSE)) }
            revoked = true
        }
        revokeOnLeave = false
    }

    /** The search page closed: its "Use my location" stops, so a late fix can't be picked into another search. */
    fun cancelSearch() {
        searchStatus = null
        if (!searching) return
        searching = false
        searchSeq++
        if (!on) {
            pending = false
            cancelStart()
            locator?.stop()
            if (phase == Locator.LOCATING) phase = Locator.IDLE
        }
    }

    /** Refresh while showing My location: ask again if the permission is gone, else renew an old fix. */
    fun refresh() {
        if (!on || app == null) return
        when {
            !has(COARSE) -> if (!blocked) request(forSearch = false)
            age() > MIN -> locate()
        }
    }

    /** A My location widget was tapped; MainActivity resumes next. */
    fun widgetOpened() {
        if (!resumed) fromWidget = true else if (on && age() > 2 * MIN) locate()
    }

    /**
     * The permission dialog closed. MainActivity skips empty result arrays: those come only from a request
     * superseded by one still showing. Back gives full arrays with nothing granted, just like a denial.
     */
    fun onPermissionsResult(code: Int) {
        if (code != REQUEST_LOCATION) return
        val a = host as? Activity ?: return
        val forSearch = askedForSearch
        val upgrade = upgrading
        askedForSearch = false
        upgrading = false
        // Denied for good when the rationale is false after an earlier denial: "Don't allow" a second time, and the
        // dialog won't show any more. That denial is the rationale seen true before this dialog, or else (the
        // process may have died in between, or the permission was fixed where the app never saw it) the last
        // result on record. False before and after the first time says nothing: dismissed with Back, or the first
        // answer. A second silent "no" in a row is taken as for good: App info is a way out, a dead button isn't.
        val after = a.shouldShowRequestPermissionRationale(FINE)
        val asked = askedRationale
        askedRationale = false
        fun refused(deniedBefore: Boolean) = !after && (asked || deniedBefore)
        // What counts is what the app holds now, not the dialog's own answer.
        if (has(COARSE)) {
            blocked = false
            deniedOnce = false
            approx = !has(FINE)
            if (approx && upgrade) {
                preciseBlocked = preciseBlocked || refused(preciseDeniedOnce)
                preciseDeniedOnce = true
            }
            preciseBlocked = preciseBlocked && approx
            preciseDeniedOnce = preciseDeniedOnce && approx
            if (!forSearch) on = true
            save()
            if (upgrade && preciseBlocked) openAppInfo(a)
            if (forSearch) searchHere() else locate()
        } else {
            blocked = refused(deniedOnce)
            deniedOnce = true
            save()
        }
        changed(false)
    }

    /** The header's and the Cities row's status, most urgent first; null when there is nothing to say. */
    fun note(): String? {
        val c = app ?: return null
        if (!on) return null
        val id = when {
            phase == Locator.LOCATING -> if (slow) R.string.here_slow else R.string.here_locating
            phase == Locator.OFF -> R.string.here_off
            !has(COARSE) -> R.string.here_denied
            phase == Locator.NO_FIX && here != null -> R.string.here_no_update
            else -> return null
        }
        return c.getString(id)
    }

    fun approximate(): Boolean = on && here != null && approx

    fun icon(): Int = R.drawable.ic_here_small

    fun text(c: Context, which: Int): String? {
        load(c)
        return when (which) {
            TEXT_BACK -> if (place(c) != null) c.getString(R.string.here_back) else null
            TEXT_HINT -> c.getString(
                when {
                    phase == Locator.NO_FIX -> R.string.here_no_fix
                    phase == Locator.LOCATING && slow -> R.string.here_slow
                    else -> R.string.here_hint
                },
            )
            TEXT_WIDGET -> c.getString(R.string.widget_here).let { label ->
                place(c)?.name?.takeIf { it != c.getString(R.string.here_name) }?.let { "$label · $it" } ?: label
            }
            TEXT_SEARCH -> searchStatus
            TEXT_APPROX -> if (approximate()) c.getString(R.string.here_approx) else null
            else -> null
        }
    }

    /** The button for [slot]: the next step towards a working My location; null when it already works. */
    fun action(slot: Int): HereAction? {
        val c = app ?: return null
        fun act(label: Int, enabled: Boolean = true, run: () -> Unit) =
            HereAction(c.getString(label), R.drawable.ic_locate, enabled, run)
        if (slot == SLOT_SEARCH) return if (blocked) null else act(R.string.here_use, !searching) { searchHere() }
        val coarse = has(COARSE)
        return when {
            blocked -> act(R.string.here_settings) { (host as? Activity)?.let(::openAppInfo) }
            on && !coarse -> act(R.string.here_allow) { request(forSearch = false) }
            !on -> act(R.string.here_use) {
                if (has(COARSE)) { // as of the tap: the pill may have been drawn before a grant
                    on = true
                    save()
                    locate()
                } else {
                    request(forSearch = false)
                }
            }
            phase == Locator.OFF -> act(R.string.here_turn_on) { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            here != null -> null
            phase == Locator.LOCATING -> act(R.string.here_locating, enabled = false) {}
            else -> act(R.string.here_try_again) { locate() }
        }
    }

    /** Touch & hold on the Cities row. */
    fun menu(p: Place) {
        val a = host as? Activity ?: return
        val items = ArrayList<Pair<Int, () -> Unit>>()
        items += R.string.make_hometown to { toKeep(here ?: p) { host?.makeHometown(it) } }
        items += R.string.here_save_city to { toKeep(here ?: p) { host?.saveCity(it) } }
        if (approximate()) {
            items += R.string.here_precise to {
                if (preciseBlocked) {
                    openAppInfo(a)
                } else {
                    upgrading = true
                    request(forSearch = false)
                }
            }
        }
        items += (if (names) R.string.here_names_off else R.string.here_names_on) to {
            names = !names
            save()
            here?.let(::nameIfUnnamed)
        }
        items += R.string.here_stop to ::stopUsing
        AlertDialog.Builder(a)
            .setTitle(p.name)
            .setItems(items.map { a.getString(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** The raw fix for the radar dot (lat, lon, accuracy in m) while it is at most 30 min old. */
    fun me(): DoubleArray? {
        val f = fix ?: return null
        if (Geo.ageMs(f, SystemClock.elapsedRealtimeNanos()) > 30 * MIN) return null
        return doubleArrayOf(f.lat, f.lon, f.accM.toDouble())
    }

    /** The radar's locate button: to the fix when there is one (renewed if over a minute old), else the place. */
    fun locateOnRadar(v: RadarView) {
        if (on && (fix == null || age() > MIN)) locate()
        val m = me() ?: return v.recenter()
        v.centerOn(m[0], m[1], 9.0)
    }

    fun resetForTest() {
        unwatchMode()
        locator?.stop()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        net.shutdownNow()
        main = Handler(Looper.getMainLooper())
        io = Executors.newSingleThreadExecutor()
        net = Executors.newSingleThreadExecutor()
        locator = null
        cache = null
        app = null
        host = null
        loaded = false
        swept = false
        resumed = false
        revokeOnLeave = false
        revoked = false
        clear()
        Nominatim.fetch = Nominatim::http
    }

    // ---- State ------------------------------------------------------------------------------------------

    /** Reads here.json once (a few hundred bytes); returns the application context. */
    private fun load(c: Context): Context {
        app?.let { if (loaded) return it }
        synchronized(this) {
            if (!loaded) {
                val a = c.applicationContext
                runCatching { JSONObject(File(a.noBackupFilesDir, STATE).readText()) }.getOrNull()?.let { o ->
                    blocked = o.optBoolean("blocked")
                    preciseBlocked = o.optBoolean("preciseBlocked")
                    deniedOnce = o.optBoolean("deniedOnce")
                    preciseDeniedOnce = o.optBoolean("preciseDeniedOnce")
                    names = o.optBoolean("names", true)
                    fixAt = o.optLong("fixAt")
                    failAt = o.optLong("failAt")
                    approx = o.optBoolean("approx")
                    here = o.optJSONObject("place")?.let { runCatching { Place.fromJson(it) }.getOrNull() }
                    on = o.optBoolean("on")
                }
                app = a
                loaded = true
            }
            return app!!
        }
    }

    private fun save() {
        val c = app ?: return
        val text = JSONObject()
            .put("on", on).put("blocked", blocked).put("preciseBlocked", preciseBlocked).put("names", names)
            .put("deniedOnce", deniedOnce).put("preciseDeniedOnce", preciseDeniedOnce)
            .put("fixAt", fixAt).put("failAt", failAt).put("approx", approx)
            .apply { here?.let { put("place", it.toJson()) } }
            .toString()
        io.execute { runCatching { ForecastFiles.writeAtomic(File(c.noBackupFilesDir, STATE), text) } }
    }

    private fun clear() {
        on = false
        here = null
        blocked = false
        preciseBlocked = false
        deniedOnce = false
        preciseDeniedOnce = false
        names = true
        fixAt = 0
        failAt = 0
        approx = false
        fix = null
        phase = Locator.IDLE
        slow = false
        pending = false
        fromWidget = false
        askedForSearch = false
        upgrading = false
        askedRationale = false
        searching = false
        searchStatus = null
        searchSeq++
    }

    /** Deletes forecasts of spots My location has left (all of them once it is off). */
    private fun sweep(c: Context) {
        val keep = if (on) here?.key?.let { "$it.json" } else null
        File(c.cacheDir, "forecasts").listFiles()?.forEach {
            if (it.name.startsWith("here_") && it.name.endsWith(".json") && it.name != keep) it.delete()
        }
    }

    /**
     * "Stop using location": forgets everything and, on Android 13+, gives the permission back once the app is
     * left with location still off ([left]). It can't be taken back once asked for, and until the system kills
     * the app the permission still reads as granted, so a change of mind before leaving keeps it.
     */
    private fun stopUsing() {
        val c = app ?: return
        locator?.stop()
        cancelStart()
        val old = here
        // What the dialog said last still holds: without it, a refused request could no longer lead to App info.
        // So does "Don't look up place names": a privacy choice isn't undone by stopping.
        val keep = booleanArrayOf(blocked, preciseBlocked, deniedOnce, preciseDeniedOnce, names)
        clear()
        io.execute {
            File(c.noBackupFilesDir, STATE).delete()
            sweep(c)
        }
        blocked = keep[0]
        preciseBlocked = keep[1]
        deniedOnce = keep[2]
        preciseDeniedOnce = keep[3]
        names = keep[4]
        if (blocked || preciseBlocked || deniedOnce || preciseDeniedOnce || !names) save()
        net.execute {
            (cache ?: Names(File(c.noBackupFilesDir, NAMES))).forget()
            cache = null
        }
        old?.let { Repo.get(c).forget(it) }
        Widgets.update(c)
        revokeOnLeave = Build.VERSION.SDK_INT >= 33
        changed(true)
    }

    // ---- Permission -------------------------------------------------------------------------------------

    private fun has(permission: String) = !revoked && app?.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** FINE and COARSE together: some Android 12 builds ignore FINE alone. */
    private fun request(forSearch: Boolean) {
        val a = host as? Activity ?: return
        // Given back but still granted: the dialog wouldn't show, and the grant ends when the app is killed.
        if (revoked) return Toast.makeText(a, R.string.here_given_back, Toast.LENGTH_LONG).show()
        askedForSearch = forSearch
        askedRationale = a.shouldShowRequestPermissionRationale(FINE)
        a.requestPermissions(arrayOf(FINE, COARSE), REQUEST_LOCATION)
    }

    private fun openAppInfo(a: Activity) =
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))

    private fun open(intent: Intent) {
        val a = host as? Activity ?: return
        try {
            a.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Some builds lack the settings screen; the button then does nothing.
        }
    }

    // ---- Locating ---------------------------------------------------------------------------------------

    /** Milliseconds since the newest fix used (huge when there is none). */
    private fun age() = System.currentTimeMillis() - fixAt

    /** For automatic attempts: a failed one is retried after 2 min, a fix older than [maxAgeMs] is renewed. */
    private fun due(maxAgeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return if (failAt > fixAt) now - failAt > 2 * MIN else now - fixAt > maxAgeMs
    }

    /** Queues [starter] behind the first frame: a view holds its posts until its window is attached and drawn. */
    private fun postStart() = (host as? Activity)?.window?.decorView?.post(starter) ?: main.post(starter)

    private fun cancelStart() {
        main.removeCallbacks(starter)
        (host as? Activity)?.window?.decorView?.removeCallbacks(starter)
    }

    private fun unwatchMode() {
        if (!watchingMode) return
        watchingMode = false
        runCatching { app?.unregisterReceiver(modeReceiver) }
    }

    private fun loc(): Locator = locator ?: Locator(app!!, ::fixed, ::slowed, ::ended).also { locator = it }

    private fun locate() {
        if (!has(COARSE)) return
        if (!resumed) {
            pending = true
            return
        }
        if (locator?.running == true) return
        cancelStart()
        start()
    }

    private fun start() {
        phase = Locator.LOCATING
        slow = false
        changed(false)
        loc().start(has(FINE))
    }

    private fun fixed(f: Fix) {
        fix = f
        if (on) move(f) else changed(false)
    }

    private fun slowed() {
        slow = true
        changed(false)
    }

    private fun ended(p: Int) {
        phase = p
        slow = false
        if (p == Locator.NO_FIX) {
            failAt = System.currentTimeMillis()
            save()
        }
        if (searching) {
            val f = fix
            if (p == Locator.IDLE && f != null) nameForSearch(f) else failSearch(if (p == Locator.OFF) R.string.here_off else R.string.here_no_fix)
        }
        changed(false)
    }

    /** Takes [f] as My location; a new spot only when the fix is clearly away from the current one. */
    private fun move(f: Fix) {
        val c = app ?: return
        fixAt = maxOf(fixAt, System.currentTimeMillis() - Geo.ageMs(f, SystemClock.elapsedRealtimeNanos()))
        val old = here
        val lat = Geo.round2(f.lat)
        val lon = Geo.round2(f.lon)
        val p = Place(c.getString(R.string.here_name), Geo.coords(lat, lon), lat, lon, here = true)
        if (old != null && (old == p || !Geo.shouldMove(old.lat, old.lon, f))) {
            save()
            nameIfUnnamed(old)
            changed(false)
            return
        }
        here = p
        save()
        old?.let { Repo.get(c).forget(it) }
        Widgets.update(c)
        changed(true)
        name(p)
    }

    // ---- Naming -----------------------------------------------------------------------------------------

    /** Retries a place that only got its coordinates, e.g. while offline (the cache backs off by itself). */
    private fun nameIfUnnamed(p: Place) {
        if (unnamed(p)) name(p)
    }

    /** Only its coordinates: My location's fallback ("My location", coordinates) or a place saved from it, by [named]. */
    private fun unnamed(p: Place) = Geo.coords(p.lat, p.lon).let { p.area == it || p.name == it }

    private fun name(p: Place) {
        val c = app ?: return
        lookup(c, p.lat, p.lon) { name, area ->
            val now = here
            if (on && now == p && (now.name != name || now.area != area)) {
                here = Place(name, area, p.lat, p.lon, here = true)
                save()
                Widgets.update(c)
                changed(false)
            }
        }
    }

    /** Calls [done] on the main thread with the best name for a rounded spot; never fails. */
    private fun lookup(c: Context, lat: Double, lon: Double, done: (String, String) -> Unit) {
        val near = nearSaved(c, lat, lon)
        if (near != null) return done(near.name, near.area)
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val network = names
        val fallback = c.getString(R.string.here_name) to Geo.coords(lat, lon)
        val handler = main
        net.execute {
            val cached = cache ?: Names(File(c.noBackupFilesDir, NAMES)).also { cache = it }
            val (name, area) = cached.lookup(lat, lon, lang, network) ?: fallback
            handler.post { done(name, area) }
        }
    }

    /** The closest saved place within 3 km that has a name of its own: one known only by its coordinates names nothing. */
    private fun nearSaved(c: Context, lat: Double, lon: Double): Place? =
        Store.get(c).allPlaces
            .filter { !unnamed(it) }
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
            .filter { it.second <= 3000 }
            .minByOrNull { it.second }?.first

    /**
     * An ordinary place for the spot, to save: named by [lookup] within [NAMING_MS], else by its coordinates
     * (never "My location", which would be a second one, and would name My location after itself from then on).
     */
    private fun named(c: Context, lat: Double, lon: Double, done: (Place) -> Unit) {
        var waiting = true
        fun finish(name: String, area: String) {
            if (!waiting) return
            waiting = false
            val coords = Geo.coords(lat, lon)
            done(if (area == coords) Place(coords, "", lat, lon) else Place(name, area, lat, lon))
        }
        val guard = Runnable { finish("", Geo.coords(lat, lon)) }
        main.postDelayed(guard, NAMING_MS)
        lookup(c, lat, lon) { name, area ->
            main.removeCallbacks(guard)
            finish(name, area)
        }
    }

    /**
     * My location as the hometown or a city: the saved place it is named after, which keeps one row per town, or
     * itself as an ordinary place once it has a name.
     */
    private fun toKeep(p: Place, done: (Place) -> Unit) {
        val c = app ?: return
        nearSaved(c, p.lat, p.lon)?.let { return done(it) }
        if (unnamed(p)) named(c, p.lat, p.lon, done) else done(p.plain())
    }

    // ---- Search page ------------------------------------------------------------------------------------

    /** "Use my location" on the search page: one fix, named and picked like a search result. Here stays as is. */
    private fun searchHere() {
        val c = app ?: return
        if (!has(COARSE)) return request(forSearch = true)
        searching = true
        searchSeq++
        searchStatus = c.getString(R.string.here_locating)
        changed(false)
        locate()
    }

    private fun nameForSearch(f: Fix) {
        val c = app ?: return
        val seq = ++searchSeq
        val lat = Geo.round2(f.lat)
        val lon = Geo.round2(f.lon)
        // Near a saved place, that place itself: its name at another spot would be listed twice.
        nearSaved(c, lat, lon)?.let { return pickForSearch(seq, it) }
        searchStatus = c.getString(R.string.here_naming)
        named(c, lat, lon) { pickForSearch(seq, it) }
    }

    private fun pickForSearch(seq: Int, p: Place) {
        if (seq != searchSeq || !searching) return
        searching = false
        searchStatus = null
        host?.pickPlace(p)
    }

    private fun failSearch(text: Int) {
        searching = false
        searchSeq++
        searchStatus = app?.getString(text)
    }

    private fun changed(moved: Boolean) {
        host?.hereChanged(moved)
    }
}
