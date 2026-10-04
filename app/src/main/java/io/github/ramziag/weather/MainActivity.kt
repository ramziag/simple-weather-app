package io.github.ramziag.weather

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * The whole app is this one screen: a header, five tab pages (Now, Hourly, 10-Day, Radar, Cities) and a
 * search page. Plain framework views only, so it starts fast and the APK stays tiny. Weather+'s "My location"
 * comes in through [Here], only inside `if (Here.ENABLED)`.
 */
class MainActivity : Activity(), Repo.Listener, HereHost {

    private lateinit var store: Store
    private lateinit var repo: Repo
    private lateinit var fmt: Fmt
    private var is24Hour = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var units: TextView
    private lateinit var refresh: View
    private lateinit var backHome: TextView
    private lateinit var status: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var pages: Array<View>
    private lateinit var tabViews: Array<View>
    private lateinit var pageSearch: View
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private lateinit var searchHere: TextView
    private lateinit var useLocation: TextView
    private lateinit var useLocationHint: TextView

    private lateinit var nowContent: View
    private lateinit var nowEmpty: View
    private lateinit var nowIcon: ImageView
    private lateinit var nowTemp: TextView
    private lateinit var nowDesc: TextView
    private lateinit var nowHilo: TextView
    private lateinit var nowHours: LinearLayout
    private lateinit var nowDetails: LinearLayout
    private lateinit var hourlyList: LinearLayout
    private lateinit var dailyList: LinearLayout
    private lateinit var citiesList: LinearLayout
    private lateinit var radarView: RadarView
    private lateinit var radarPlay: ImageButton
    private lateinit var radarSeek: SeekBar
    private lateinit var radarTime: TextView
    private lateinit var radarStatus: TextView

    private var colorText = 0
    private var colorSub = 0

    private var tab = TAB_NOW
    private var viewing: Place? = null
    private var forecast: Forecast? = null
    private var error: String? = null
    private var citiesError: String? = null
    private var radarError: String? = null
    private var radarAutoPlay = true
    private var refreshing = false
    private var renderedHour: LocalDateTime? = null
    private var hadHere = false
    private var hereSearchText: String? = null
    private val generation = ++created

    /** Pages whose views are out of date; rebuilt lazily when shown. */
    private val dirty = BooleanArray(5) { true }

    private var searchMode = SEARCH_NONE
    private var searchSeq = 0
    private val results = ArrayList<Place>()
    private val resultsAdapter = ResultsAdapter()
    private val searchRunnable = Runnable { search(searchInput.text.toString()) }

    private var backCallback: Any? = null

    /** Where the forecast tabs go back to: Weather+'s My location once it has been found, else the hometown. */
    private val defaultPlace: Place? get() = (if (Here.ENABLED) Here.place(this) else null) ?: store.home

    /** What the Now / Hourly / 10-Day tabs show: a city picked from the list, else the default place. */
    private val place: Place? get() = viewing ?: defaultPlace

    // ---- Lifecycle -------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        store = Store.get(this)
        setTheme(Themes.style(store.theme, resources.configuration))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 35) {
            // Android 15+ is always edge-to-edge; older versions opt in here.
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }
        setContentView(R.layout.activity_main)

        repo = Repo.get(this)
        is24Hour = DateFormat.is24HourFormat(this)
        fmt = Fmt(store.imperial, is24Hour)
        colorText = themeColor(R.attr.wText)
        colorSub = themeColor(R.attr.wSub)

        root = findViewById(R.id.root)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        units = findViewById(R.id.units)
        refresh = findViewById(R.id.refresh)
        backHome = findViewById(R.id.back_home)
        status = findViewById(R.id.status)
        tabs = findViewById(R.id.tabs)
        pages = arrayOf(
            findViewById(R.id.page_now),
            findViewById(R.id.page_hourly),
            findViewById(R.id.page_daily),
            findViewById(R.id.page_radar),
            findViewById(R.id.page_cities),
        )
        tabViews = arrayOf(
            findViewById(R.id.tab_now),
            findViewById(R.id.tab_hourly),
            findViewById(R.id.tab_daily),
            findViewById(R.id.tab_radar),
            findViewById(R.id.tab_cities),
        )
        pageSearch = findViewById(R.id.page_search)
        searchInput = findViewById(R.id.search_input)
        searchStatus = findViewById(R.id.search_status)
        searchHere = findViewById(R.id.search_here)
        useLocation = findViewById(R.id.use_location)
        useLocationHint = findViewById(R.id.use_location_hint)
        nowContent = findViewById(R.id.now_content)
        nowEmpty = findViewById(R.id.now_empty)
        nowIcon = findViewById(R.id.now_icon)
        nowTemp = findViewById(R.id.now_temp)
        nowDesc = findViewById(R.id.now_desc)
        nowHilo = findViewById(R.id.now_hilo)
        nowHours = findViewById(R.id.now_hours)
        nowDetails = findViewById(R.id.now_details)
        hourlyList = findViewById(R.id.hourly_list)
        dailyList = findViewById(R.id.daily_list)
        citiesList = findViewById(R.id.cities_list)
        radarView = findViewById(R.id.radar_map)
        radarPlay = findViewById(R.id.radar_play)
        radarSeek = findViewById(R.id.radar_seek)
        radarTime = findViewById(R.id.radar_time)
        radarStatus = findViewById(R.id.radar_status)

        setupInsets()
        setupTabs()
        setupSearch()
        setupRadar()
        units.setOnClickListener { toggleUnits() }
        findViewById<View>(R.id.theme).setOnClickListener { pickTheme() }
        refresh.setOnClickListener {
            if (Here.ENABLED && place?.here == true) Here.refresh()
            reload(force = true)
        }
        backHome.setOnClickListener {
            setPlace(null)
            render()
            syncBack()
        }
        findViewById<View>(R.id.choose_home).setOnClickListener { openSearch(SEARCH_HOME) }

        savedInstanceState?.let {
            tab = it.getInt(STATE_TAB, TAB_NOW)
            viewing = it.getString(STATE_VIEWING)?.let(Place::parse)
            // My location is always the default place, never "viewing": show whatever it is now.
            if (Here.ENABLED && viewing?.here == true) viewing = null
        }
        repo.listener = this
        if (Here.ENABLED) {
            Here.attach(this)
            hadHere = Here.place(this) != null
        }
        forecast = place?.let(repo::cached)
        showTab(tab)
        if (savedInstanceState == null) openFromWidget(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromWidget(intent)
    }

    /** A widget tap carries the widget's city (empty = hometown; Weather+: [HERE_MARKER]): show it on the Now tab. */
    private fun openFromWidget(intent: Intent?) {
        val ref = intent?.getStringExtra(Widgets.EXTRA_PLACE) ?: return
        intent.removeExtra(Widgets.EXTRA_PLACE)
        if (searchMode != SEARCH_NONE) closeSearch(TAB_NOW)
        when {
            !Here.ENABLED -> setPlace(if (ref.isEmpty()) null else Place.parse(ref))
            ref == HERE_MARKER -> {
                setPlace(null)
                Here.widgetOpened()
            }
            // The hometown itself, also while My location is the default.
            else -> setPlace(if (ref.isEmpty()) store.home else Place.parse(ref))
        }
        showTab(TAB_NOW)
    }

    override fun onResume() {
        super.onResume()
        val now24 = DateFormat.is24HourFormat(this)
        if (now24 != is24Hour) {
            is24Hour = now24
            fmt = Fmt(store.imperial, is24Hour)
            markDirty()
            render()
        }
        handler.post(ticker)
        if (tab == TAB_RADAR) {
            radarAutoPlay = true
            render()
        }
        if (Here.ENABLED) {
            Here.resume()
            hereChanged(false) // the permission or the location setting may have changed while away
        }
    }

    override fun onPause() {
        if (Here.ENABLED) Here.pause()
        handler.removeCallbacks(ticker)
        radarView.pause()
        TileCache.flush()
        super.onPause()
    }

    override fun onStop() {
        radarView.trim()
        super.onStop()
    }

    override fun onDestroy() {
        if (repo.listener === this) repo.listener = null
        // A newer MainActivity may already be attached (an old one's onDestroy can come after its onCreate).
        if (Here.ENABLED && generation == created) Here.attach(null)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Empty results mean the request was cancelled (e.g. another was showing): nothing changes.
        if (Here.ENABLED && grantResults.isNotEmpty()) Here.onPermissionsResult(requestCode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        viewing?.let { outState.putString(STATE_VIEWING, it.toJson().toString()) }
    }

    /** Once a minute: refresh stale data, keep "updated x min ago" honest and drop hours that have passed. */
    private val ticker = object : Runnable {
        override fun run() {
            reload(force = false)
            if (Here.ENABLED) Here.tick(searchMode == SEARCH_NONE && place?.here == true)
            val hour = forecast?.localNow()?.truncatedTo(ChronoUnit.HOURS)
            if (hour != renderedHour) {
                renderedHour = hour
                markDirty()
                render()
            } else {
                renderHeader()
            }
            if (tab == TAB_RADAR) updateRadarLabel()
            handler.postDelayed(this, 60_000)
        }
    }

    // ---- Data ------------------------------------------------------------------------------------------

    private fun reload(force: Boolean) {
        val p = place
        val cities = tab == TAB_CITIES && listed().isNotEmpty()
        if (force && (p != null || cities)) {
            refreshing = true
            renderHeader()
        }
        p?.let { repo.loadForecast(it, force) }
        if (cities) repo.loadSummaries(listed(), force)
        if (tab == TAB_RADAR && p != null) repo.loadRadar(force)
    }

    override fun onForecast(place: Place, forecast: Forecast?, error: String?) {
        dirty[TAB_CITIES] = true
        if (place == this.place) {
            refreshing = false
            if (forecast != null) this.forecast = forecast
            this.error = error
            markDirty()
        }
        render()
    }

    override fun onSummaries(error: String?) {
        if (tab == TAB_CITIES) refreshing = false
        citiesError = error
        dirty[TAB_CITIES] = true
        render()
    }

    override fun onRadar(error: String?) {
        if (tab == TAB_RADAR) refreshing = false
        radarError = error
        render()
    }

    /** Switches the forecast tabs to [p] (null = the default place). Caller renders. */
    private fun setPlace(p: Place?) {
        viewing = p?.takeIf { it != defaultPlace }
        forecast = place?.let(repo::cached)
        error = null
        markDirty()
        place?.let { repo.loadForecast(it, false) }
    }

    private fun setHometown(p: Place, keepOld: Boolean) {
        val old = store.home
        val cities = store.cities.toMutableList()
        val at = cities.indexOf(p)
        if (at >= 0) cities.removeAt(at)
        if (keepOld && old != null && old != p) cities.add(if (at >= 0) at else 0, old)
        store.cities = cities
        store.home = p
        setPlace(null)
        repo.loadSummaries(listed(), false)
        Widgets.update(this)
    }

    private fun markDirty() = dirty.fill(true)

    /** Every place the Cities tab lists: My location (Weather+), the hometown, then the saved cities. */
    private fun listed(): List<Place> =
        if (Here.ENABLED) listOfNotNull(Here.place(this)) + store.allPlaces else store.allPlaces

    // ---- My location (Weather+) -------------------------------------------------------------------------

    override fun hereChanged(moved: Boolean) {
        if (moved) {
            val old = forecast
            setPlace(viewing)
            // Moved to a new spot: keep the last spot's forecast up, as "Updating…", until the new one arrives.
            if (hadHere && viewing == null && forecast == null && old != null && place?.here == true) {
                forecast = old
                refreshing = true
            }
            if (tab == TAB_CITIES) repo.loadSummaries(listed(), false)
        }
        hadHere = Here.place(this) != null
        dirty[TAB_CITIES] = true
        if (searchMode != SEARCH_NONE) {
            showAction(searchHere, Here.action(SLOT_SEARCH))
            val text = Here.text(this, TEXT_SEARCH)
            if (text != null && text != hereSearchText) searchStatus.text = text
            hereSearchText = text
        }
        render()
        syncBack()
    }

    override fun pickPlace(p: Place) {
        if (searchMode != SEARCH_NONE) pick(p)
    }

    override fun makeHometown(p: Place) {
        setHometown(p, keepOld = true)
        dirty[TAB_CITIES] = true
        render()
        syncBack()
    }

    override fun saveCity(p: Place) {
        if (p != store.home && p !in store.cities) store.cities = store.cities + p
        repo.loadSummaries(listed(), false)
        dirty[TAB_CITIES] = true
        render()
    }

    /** Shows Weather+'s next step towards a working My location on [v] (e.g. "Allow location"), or hides it. */
    private fun showAction(v: TextView, a: HereAction?) {
        v.visibility = if (a != null) View.VISIBLE else View.GONE
        if (a == null) return
        v.text = a.label
        v.isEnabled = a.enabled
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(a.icon, 0, 0, 0)
        v.setOnClickListener { a.run() }
    }

    // ---- Rendering -------------------------------------------------------------------------------------

    private fun showTab(t: Int) {
        if (tab == TAB_RADAR && t != TAB_RADAR) {
            radarView.pause()
            radarView.trim()
        }
        if (t == TAB_RADAR && tab != TAB_RADAR) radarAutoPlay = true
        tab = t
        tabViews.forEachIndexed { i, v ->
            val on = i == t
            val color = if (on) colorText else colorSub
            val icon = v.findViewById<ImageView>(R.id.tab_icon)
            icon.setBackgroundResource(if (on) R.drawable.bg_tab_on else 0)
            icon.imageTintList = ColorStateList.valueOf(color)
            val label = v.findViewById<TextView>(R.id.tab_label)
            label.setTextColor(color)
            label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            v.isSelected = on
        }
        if (t == TAB_CITIES) repo.loadSummaries(listed(), false)
        if (t == TAB_RADAR) repo.loadRadar(false)
        render()
        syncBack()
    }

    private fun render() {
        renderHeader()
        if (searchMode != SEARCH_NONE) return
        val p = place
        val f = forecast
        val message = when {
            tab == TAB_CITIES -> null
            p == null -> if (tab == TAB_NOW) null else getString(R.string.no_hometown)
            tab == TAB_RADAR -> null
            f == null -> error ?: getString(R.string.loading)
            else -> null
        }
        status.text = message
        status.visibility = if (message != null) View.VISIBLE else View.GONE
        pages.forEachIndexed { i, page -> page.visibility = if (i == tab && message == null) View.VISIBLE else View.GONE }
        if (message != null) return
        if (tab == TAB_RADAR) {
            renderRadar()
            return
        }
        if (tab == TAB_NOW) {
            nowEmpty.visibility = if (p == null) View.VISIBLE else View.GONE
            nowContent.visibility = if (p == null) View.GONE else View.VISIBLE
            if (Here.ENABLED && p == null) {
                val a = Here.action(SLOT_WELCOME)
                showAction(useLocation, a)
                useLocationHint.text = Here.text(this, TEXT_HINT)
                useLocationHint.visibility = useLocation.visibility
            }
        }
        if (!dirty[tab]) return
        dirty[tab] = false
        if (tab == TAB_CITIES) {
            renderCities()
        } else if (f != null) {
            renderedHour = f.localNow().truncatedTo(ChronoUnit.HOURS)
            when (tab) {
                TAB_NOW -> renderNow(f)
                TAB_HOURLY -> renderHourly(f)
                TAB_DAILY -> renderDaily(f)
            }
        }
    }

    private fun renderHeader() {
        val p = place
        val searching = searchMode != SEARCH_NONE
        val showsPlace = !searching && tab != TAB_CITIES
        title.text = when {
            searchMode == SEARCH_HOME -> getString(R.string.choose_hometown)
            searchMode == SEARCH_ADD -> getString(R.string.add_city)
            tab == TAB_CITIES -> getString(R.string.cities)
            else -> p?.name ?: getString(R.string.app_name)
        }
        val homeMark = showsPlace && p != null && (if (Here.ENABLED) p == store.home else viewing == null)
        val mark = when {
            Here.ENABLED && showsPlace && p?.here == true -> Here.icon()
            homeMark -> R.drawable.ic_home_small
            else -> 0
        }
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(mark, 0, 0, 0)
        subtitle.text = when {
            searching -> ""
            refreshing -> "Updating…"
            tab == TAB_CITIES -> citiesError ?: getString(R.string.cities_hint)
            tab == TAB_RADAR -> radarError ?: getString(R.string.radar_subtitle)
            else -> placeStatus()
        }
        subtitle.visibility = if (subtitle.text.isEmpty()) View.GONE else View.VISIBLE
        units.text = fmt.unit
        val canRefresh = !searching && (if (tab == TAB_CITIES) listed().isNotEmpty() else p != null)
        refresh.visibility = if (canRefresh) View.VISIBLE else View.GONE
        backHome.visibility = if (showsPlace && viewing != null) View.VISIBLE else View.GONE
        if (Here.ENABLED) {
            val toHere = Here.text(this, TEXT_BACK)
            backHome.text = toHere ?: getString(R.string.back_home)
            backHome.setCompoundDrawablesRelativeWithIntrinsicBounds(if (toHere != null) Here.icon() else R.drawable.ic_home_small, 0, 0, 0)
        }
    }

    /** e.g. "Illinois · updated 5 min ago" or "Offline · updated 2 h ago". */
    private fun placeStatus(): String {
        val p = place ?: return ""
        if (Here.ENABLED && p.here) return hereStatus(p)
        val f = forecast ?: return p.area
        val first = error ?: p.area.substringBefore(',')
        return listOf(first, "updated " + fmt.ago(f.fetchedAt)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** e.g. "Locating…", "Illinois · approximate · updated 5 min ago" or "Location is off · updated 2 h ago". */
    private fun hereStatus(p: Place): String {
        val note = Here.note()
        val f = forecast ?: return note ?: p.area
        // An unnamed spot's area is its coordinates ("39.80° N, 89.64° W"), which only make sense whole.
        val area = if (p.area.firstOrNull()?.isDigit() == true) p.area else p.area.substringBefore(',')
        return listOfNotNull(note ?: error ?: area, Here.text(this, TEXT_APPROX), "updated " + fmt.ago(f.fetchedAt))
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun renderNow(f: Forecast) {
        val c = f.current
        val today = f.today()
        nowIcon.setImageResource(Wmo.icon(c.code, c.isDay))
        nowIcon.contentDescription = Wmo.label(c.code)
        nowTemp.text = fmt.temp(c.temp)
        nowDesc.text = Wmo.label(c.code)
        nowHilo.text = "Feels like ${fmt.temp(c.feels)}   ·   H ${fmt.temp(today?.max)}  L ${fmt.temp(today?.min)}"

        nowHours.removeAllViews()
        f.upcomingHours(24).forEachIndexed { i, h ->
            val v = inflate(R.layout.item_hour_mini, nowHours)
            v.text(R.id.time, if (i == 0) "Now" else fmt.hour(h.time))
            if (i == 0) v.icon(R.id.icon, c.code, c.isDay) else v.icon(R.id.icon, h.code, h.isDay)
            v.text(R.id.temp, fmt.temp(if (i == 0) c.temp else h.temp))
            v.text(R.id.pop, if (h.pop >= 10) fmt.percent(h.pop) else "")
        }

        nowDetails.removeAllViews()
        val cells = listOf(
            "Wind" to "${fmt.wind(c.wind)} ${fmt.compass(c.windDir)}".trim(),
            "Gusts" to fmt.wind(c.gusts),
            "Humidity" to fmt.percent(c.humidity),
            "Cloud cover" to fmt.percent(c.cloud),
            "Precip. chance" to fmt.percent(today?.pop),
            "Precip. total" to fmt.precip(today?.precip),
            "UV index (max)" to fmt.uv(today?.uv),
            "Pressure" to fmt.pressure(c.pressure),
            "Sunrise" to fmt.time(today?.sunrise),
            "Sunset" to fmt.time(today?.sunset),
        )
        for (pair in cells.chunked(2)) {
            val row = LinearLayout(this)
            for ((label, value) in pair) {
                val cell = inflate(R.layout.item_detail, row)
                cell.text(R.id.label, label)
                cell.text(R.id.value, value)
            }
            nowDetails.addView(row)
        }
    }

    private fun renderHourly(f: Forecast) {
        hourlyList.removeAllViews()
        val today = f.localNow().toLocalDate()
        var date: LocalDate? = null
        var card: ViewGroup = hourlyList
        for (h in f.upcomingHours(48)) {
            val d = h.time.toLocalDate()
            if (d != date) {
                date = d
                (inflate(R.layout.section, hourlyList) as TextView).text = fmt.dayHeading(d, today)
                card = inflate(R.layout.card, hourlyList) as ViewGroup
            }
            val row = inflate(R.layout.row_hour, card)
            row.text(R.id.time, fmt.hour(h.time))
            row.icon(R.id.icon, h.code, h.isDay)
            row.text(R.id.desc, Wmo.label(h.code))
            row.pop(R.id.pop, h.pop)
            row.text(R.id.wind, fmt.wind(h.wind))
            row.text(R.id.temp, fmt.temp(h.temp))
        }
    }

    private fun renderDaily(f: Forecast) {
        dailyList.removeAllViews()
        val days = f.upcomingDays()
        val today = f.localNow().toLocalDate()
        val lo = days.map { it.min }.filterNot { it.isNaN() }.minOrNull() ?: 0.0
        val hi = days.map { it.max }.filterNot { it.isNaN() }.maxOrNull() ?: 1.0
        (inflate(R.layout.section, dailyList) as TextView).text = "${days.size}-day forecast · tap a day for details"
        val card = inflate(R.layout.card, dailyList) as ViewGroup
        for (d in days) {
            val row = inflate(R.layout.row_day, card)
            row.text(R.id.day, fmt.dayShort(d.date, today))
            row.icon(R.id.icon, d.code, true)
            row.pop(R.id.pop, d.pop)
            row.text(R.id.min, fmt.temp(d.min))
            row.text(R.id.max, fmt.temp(d.max))
            row.findViewById<RangeBar>(R.id.bar).set(lo, hi, d.min, d.max)
            val detail = row.findViewById<TextView>(R.id.detail)
            detail.text = "${Wmo.label(d.code)} · precip. ${fmt.precip(d.precip)}\n" +
                "Wind up to ${fmt.wind(d.windMax)} · UV ${fmt.uv(d.uv)}\n" +
                "Sunrise ${fmt.time(d.sunrise)} · sunset ${fmt.time(d.sunset)}"
            row.setOnClickListener {
                detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

    private fun renderRadar() {
        val p = place ?: return
        radarView.setPlace(p.lat, p.lon)
        if (Here.ENABLED) {
            val me = Here.me()
            if (me != null) radarView.setMe(me[0], me[1], me[2].toFloat()) else radarView.clearMe()
        }
        val maps = repo.radarMaps
        radarView.maps = maps
        val n = maps?.frames?.size ?: 0
        radarSeek.max = maxOf(0, n - 1)
        radarSeek.isEnabled = n > 1
        radarPlay.isEnabled = n > 1
        updateRadarLabel()
        val message = when {
            maps == null -> radarError ?: getString(R.string.radar_loading)
            n == 0 -> "No radar frames available right now"
            else -> null
        }
        radarStatus.text = message
        radarStatus.visibility = if (message != null) View.VISIBLE else View.GONE
        if (radarAutoPlay) radarView.play()
    }

    /** "20 min ago · 2:40 PM" for the frame on screen. */
    private fun updateRadarLabel() {
        val frame = repo.radarMaps?.frames?.getOrNull(radarView.frame)
        radarSeek.progress = maxOf(0, radarView.frame)
        if (frame == null) {
            radarTime.text = ""
            return
        }
        val millis = frame.time * 1000
        val minutes = ((System.currentTimeMillis() - millis) / 60_000).coerceAtLeast(0)
        val ago = when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            else -> "${minutes / 60} h ${minutes % 60} min ago"
        }
        radarTime.text = "$ago · ${fmt.clock(millis)}"
    }

    private fun renderCities() {
        citiesList.removeAllViews()
        if (Here.ENABLED) Here.place(this)?.let { cityRow(it, RowKind.HERE) }
        val home = store.home
        if (home == null) {
            pill(citiesList, R.string.choose_hometown, R.drawable.ic_home) { openSearch(SEARCH_HOME) }
        } else {
            cityRow(home, RowKind.HOME)
        }
        store.cities.forEach { cityRow(it, RowKind.CITY) }
        if (Here.ENABLED) Here.action(SLOT_CITIES)?.let { showAction(inflate(R.layout.pill, citiesList) as TextView, it) }
        pill(citiesList, R.string.add_city, R.drawable.ic_add) { openSearch(SEARCH_ADD) }
        inflate(R.layout.attribution, citiesList)
    }

    private fun cityRow(p: Place, kind: RowKind) {
        val row = inflate(R.layout.row_city, citiesList)
        val name = row.findViewById<TextView>(R.id.name)
        name.text = p.name
        val mark = when (kind) {
            RowKind.HOME -> R.drawable.ic_home_small
            RowKind.HERE -> Here.icon()
            RowKind.CITY -> 0
        }
        name.setCompoundDrawablesRelativeWithIntrinsicBounds(mark, 0, 0, 0)
        row.text(R.id.area, if (kind == RowKind.HERE) Here.note() ?: p.area else p.area)
        val s = repo.summary(p)
        if (s != null) {
            row.icon(R.id.icon, s.code, s.isDay)
            row.text(R.id.temp, fmt.temp(s.temp))
            row.text(R.id.hilo, "H ${fmt.temp(s.max)}  L ${fmt.temp(s.min)}")
        } else {
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(null)
            row.text(R.id.temp, "–")
            row.text(R.id.hilo, "")
        }
        row.setOnClickListener {
            setPlace(p)
            showTab(TAB_NOW)
        }
        row.setOnLongClickListener {
            if (kind == RowKind.HERE) Here.menu(p) else cityMenu(p, kind == RowKind.HOME)
            true
        }
    }

    private fun cityMenu(p: Place, isHome: Boolean) {
        if (isHome) {
            AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(arrayOf(getString(R.string.change_hometown))) { _, _ -> openSearch(SEARCH_HOME) }
                .show()
            return
        }
        val list = store.cities
        val i = list.indexOf(p)
        val labels = intArrayOf(R.string.make_hometown, R.string.move_up, R.string.move_down, R.string.remove)
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(labels.map { getString(it) }.toTypedArray()) { _, which ->
                when (which) {
                    0 -> setHometown(p, keepOld = true)
                    1 -> if (i > 0) store.cities = list.swapped(i, i - 1)
                    2 -> if (i in 0 until list.size - 1) store.cities = list.swapped(i, i + 1)
                    3 -> {
                        store.cities = list - p
                        repo.forget(p)
                        if (viewing == p) setPlace(null)
                        Widgets.update(this) // widgets set to this city go back to the hometown
                    }
                }
                dirty[TAB_CITIES] = true
                render()
                syncBack()
            }
            .show()
    }

    // ---- Search ----------------------------------------------------------------------------------------

    private fun setupSearch() {
        val list = findViewById<ListView>(R.id.search_results)
        list.adapter = resultsAdapter
        list.setOnItemClickListener { _, _, position, _ -> pick(results[position]) }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchRunnable)
                if (searchMode != SEARCH_NONE) handler.postDelayed(searchRunnable, 350)
            }
        })
        searchInput.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            handler.removeCallbacks(searchRunnable)
            searchRunnable.run()
            true
        }
    }

    private fun openSearch(mode: Int) {
        searchMode = mode
        radarView.pause()
        searchInput.setText("")
        handler.removeCallbacks(searchRunnable)
        searchSeq++
        results.clear()
        resultsAdapter.notifyDataSetChanged()
        searchStatus.setText(R.string.search_help)
        if (Here.ENABLED) {
            showAction(searchHere, Here.action(SLOT_SEARCH))
            hereSearchText = Here.text(this, TEXT_SEARCH)
        }
        pages.forEach { it.visibility = View.GONE }
        status.visibility = View.GONE
        tabs.visibility = View.GONE
        pageSearch.visibility = View.VISIBLE
        root.requestApplyInsets()
        searchInput.requestFocus()
        searchInput.windowInsetsController?.show(WindowInsets.Type.ime())
        renderHeader()
        syncBack()
    }

    private fun closeSearch(toTab: Int) {
        searchMode = SEARCH_NONE
        searchSeq++
        handler.removeCallbacks(searchRunnable)
        searchInput.windowInsetsController?.hide(WindowInsets.Type.ime())
        searchInput.clearFocus()
        pageSearch.visibility = View.GONE
        tabs.visibility = View.VISIBLE
        root.requestApplyInsets()
        showTab(toTab)
    }

    private fun search(text: String) {
        val query = text.trim()
        val seq = ++searchSeq
        if (query.length < 2) {
            results.clear()
            resultsAdapter.notifyDataSetChanged()
            searchStatus.setText(R.string.search_help)
            return
        }
        searchStatus.setText(R.string.searching)
        repo.search(query) { places, err ->
            if (seq != searchSeq) return@search
            results.clear()
            places?.let(results::addAll)
            resultsAdapter.notifyDataSetChanged()
            searchStatus.text = err ?: if (results.isEmpty()) getString(R.string.no_matches) else ""
        }
    }

    private fun pick(p: Place) {
        when (searchMode) {
            SEARCH_HOME -> {
                setHometown(p, keepOld = false)
                closeSearch(TAB_NOW)
            }
            SEARCH_ADD -> {
                if (p != store.home && p !in store.cities) store.cities = store.cities + p
                dirty[TAB_CITIES] = true
                closeSearch(TAB_CITIES)
            }
        }
    }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.row_search, parent, false)
            val p = results[position]
            v.text(R.id.name, p.name)
            v.text(R.id.area, p.area)
            return v
        }
    }

    // ---- Header actions --------------------------------------------------------------------------------

    private fun toggleUnits() {
        store.imperial = !store.imperial
        fmt = Fmt(store.imperial, is24Hour)
        markDirty()
        render()
        Widgets.update(this)
    }

    private fun pickTheme() {
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(Themes.NAMES, store.theme) { dialog, which ->
                dialog.dismiss()
                if (which != store.theme) {
                    store.theme = which
                    Widgets.update(this)
                    recreate()
                }
            }
            .show()
    }

    // ---- Back navigation -------------------------------------------------------------------------------

    private fun canGoBack() = searchMode != SEARCH_NONE || viewing != null || tab != TAB_NOW

    private fun goBack() {
        when {
            searchMode != SEARCH_NONE -> closeSearch(tab)
            viewing != null -> {
                setPlace(null)
                showTab(TAB_CITIES)
            }
            tab != TAB_NOW -> showTab(TAB_NOW)
        }
    }

    /** Only intercept back while there is somewhere to go back to, so predictive back-to-home still works. */
    private fun syncBack() {
        if (Build.VERSION.SDK_INT >= 33) syncBack33()
    }

    @TargetApi(33)
    private fun syncBack33() {
        val want = canGoBack()
        val current = backCallback as OnBackInvokedCallback?
        if (want && current == null) {
            val callback = OnBackInvokedCallback { goBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!want && current != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(current)
            backCallback = null
        }
    }

    @Deprecated("Android 12 and older only; newer versions use OnBackInvokedCallback")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (canGoBack()) goBack() else super.onBackPressed()
    }

    // ---- Helpers ---------------------------------------------------------------------------------------

    private fun setupInsets() {
        val tabsTop = tabs.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val bottom = if (tabs.visibility == View.VISIBLE) 0 else maxOf(bars.bottom, ime.bottom)
            v.setPadding(bars.left, bars.top, bars.right, bottom)
            tabs.setPadding(0, tabsTop, 0, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    private fun setupTabs() {
        val icons = intArrayOf(R.drawable.ic_now, R.drawable.ic_hourly, R.drawable.ic_daily, R.drawable.ic_radar, R.drawable.ic_cities)
        val labels = intArrayOf(R.string.tab_now, R.string.tab_hourly, R.string.tab_daily, R.string.tab_radar, R.string.tab_cities)
        tabViews.forEachIndexed { i, v ->
            v.findViewById<ImageView>(R.id.tab_icon).setImageResource(icons[i])
            v.findViewById<TextView>(R.id.tab_label).setText(labels[i])
            v.setOnClickListener {
                when {
                    tab != i -> showTab(i)
                    i == TAB_RADAR -> radarView.recenter()
                    else -> (pages[i] as ScrollView).smoothScrollTo(0, 0)
                }
            }
        }
    }

    private fun setupRadar() {
        radarView.onFrameChanged = { updateRadarLabel() }
        radarView.onPlayingChanged = { playing ->
            radarPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            radarPlay.contentDescription = getString(if (playing) R.string.pause else R.string.play)
        }
        radarPlay.setOnClickListener {
            if (radarView.playing) {
                radarAutoPlay = false
                radarView.pause()
            } else {
                radarView.play()
            }
        }
        radarSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                radarAutoPlay = false
                radarView.pause()
                radarView.showFrame(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        findViewById<View>(R.id.radar_zoom_in).setOnClickListener { radarView.zoomBy(1.0) }
        findViewById<View>(R.id.radar_zoom_out).setOnClickListener { radarView.zoomBy(-1.0) }
        findViewById<View>(R.id.radar_locate).setOnClickListener {
            if (Here.ENABLED) Here.locateOnRadar(radarView) else radarView.recenter()
        }
    }

    private fun pill(parent: ViewGroup, text: Int, icon: Int, onClick: () -> Unit) {
        val v = inflate(R.layout.pill, parent) as TextView
        v.setText(text)
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        v.setOnClickListener { onClick() }
    }

    private fun inflate(layout: Int, parent: ViewGroup): View =
        layoutInflater.inflate(layout, parent, false).also(parent::addView)

    private fun View.text(id: Int, s: CharSequence) {
        findViewById<TextView>(id).text = s
    }

    private fun View.icon(id: Int, code: Int, day: Boolean) {
        val v = findViewById<ImageView>(id)
        v.setImageResource(Wmo.icon(code, day))
        v.contentDescription = Wmo.label(code)
    }

    /** Precipitation chance with a drop icon; hidden below 5% to keep rows quiet. */
    private fun View.pop(id: Int, pop: Double) {
        val v = findViewById<TextView>(id)
        val show = !pop.isNaN() && pop >= 5
        v.text = if (show) fmt.percent(pop) else ""
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(if (show) R.drawable.ic_drop else 0, 0, 0, 0)
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
        toMutableList().also { it[a] = this[b]; it[b] = this[a] }

    private enum class RowKind { HOME, HERE, CITY }

    private companion object {
        /** MainActivity instances so far, to tell the newest one apart. */
        var created = 0

        const val TAB_NOW = 0
        const val TAB_HOURLY = 1
        const val TAB_DAILY = 2
        const val TAB_RADAR = 3
        const val TAB_CITIES = 4

        const val SEARCH_NONE = 0
        const val SEARCH_HOME = 1
        const val SEARCH_ADD = 2

        const val STATE_TAB = "tab"
        const val STATE_VIEWING = "viewing"
    }
}
