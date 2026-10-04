package io.github.ramziag.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Slippy map with an animated precipitation radar overlay. Drag to pan, pinch or double-tap to zoom.
 * Positions are Web Mercator fractions (0..1) so panning and zooming are plain arithmetic.
 */
class RadarView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var onFrameChanged: ((Int) -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    private val base = TileCache(context, 24 shl 20)
    private val radar = TileCache(context, 64 shl 20)

    /** On-screen size of a 256 px map tile at an exact zoom level (256 dp keeps OSM's labels readable). */
    private val tilePx = 256 * resources.displayMetrics.density

    private val bg: Int
    private val dark: Boolean
    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val meDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meArea = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val dst = RectF()
    private val src = Rect()

    private var zoom = DEFAULT_ZOOM
    private var cx = 0.5
    private var cy = 0.5
    private var placeX = Double.NaN
    private var placeY = Double.NaN

    // Weather+'s own position (see [setMe]): Mercator fractions, accuracy in metres and metres per world width.
    private var meX = Double.NaN
    private var meY = Double.NaN
    private var meAccM = 0f
    private var meWorldM = 0.0

    var maps: Radar.Maps? = null
        set(value) {
            if (value === field) return
            field = value
            val n = value?.frames?.size ?: 0
            frame = if (playing && frame in 0 until n) frame else n - 1
            onFrameChanged?.invoke(frame)
            invalidate()
        }

    var frame = -1
        private set

    var playing = false
        private set

    private var waitedMs = 0

    init {
        bg = themeColor(R.attr.wBg)
        dark = Color.luminance(bg) < 0.4f
        markerFill.color = themeColor(R.attr.wAccent)
        markerRing.color = themeColor(R.attr.wText)
        meDot.color = markerRing.color
        meHalo.color = bg
        meEdge.color = markerFill.color
        meArea.color = markerFill.color
        meArea.alpha = 64
        basePaint.colorFilter = ColorMatrixColorFilter(mapStyle(dark, bg))
        base.onLoaded = { invalidate() }
        radar.onLoaded = { invalidate() }
    }

    fun setPlace(lat: Double, lon: Double) {
        val x = Radar.mercatorX(lon)
        val y = Radar.mercatorY(lat)
        if (x == placeX && y == placeY) return
        placeX = x
        placeY = y
        recenter()
    }

    fun recenter() {
        if (placeX.isNaN()) return
        cx = placeX
        cy = placeY
        zoom = DEFAULT_ZOOM
        invalidate()
    }

    /** Shows the phone's position as a dot with its accuracy circle. Never moves the map by itself. */
    fun setMe(lat: Double, lon: Double, accM: Float) {
        val x = Radar.mercatorX(lon)
        val y = Radar.mercatorY(lat)
        if (x == meX && y == meY && accM == meAccM) return
        meX = x
        meY = y
        meAccM = accM
        meWorldM = EARTH_M * cos(Math.toRadians(lat))
        invalidate()
    }

    fun clearMe() {
        if (meX.isNaN()) return
        meX = Double.NaN
        meY = Double.NaN
        invalidate()
    }

    /** Centres on a spot, zooming in to at least [minZoom]. */
    fun centerOn(lat: Double, lon: Double, minZoom: Double) {
        cx = Radar.mercatorX(lon)
        cy = Radar.mercatorY(lat)
        zoom = max(zoom, minZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
    }

    fun zoomBy(delta: Double) = zoomAround(delta, width / 2f, height / 2f)

    fun showFrame(index: Int) {
        val n = maps?.frames?.size ?: 0
        if (n == 0) return
        frame = index.coerceIn(0, n - 1)
        onFrameChanged?.invoke(frame)
        invalidate()
    }

    fun play() {
        val n = maps?.frames?.size ?: 0
        if (playing || n < 2) return
        playing = true
        waitedMs = 0
        onPlayingChanged?.invoke(true)
        // From the latest frame, linger on "now" before looping back to the oldest.
        postDelayed(stepper, if (frame >= n - 1) HOLD_LAST_MS else FRAME_MS)
        invalidate()
    }

    fun pause() {
        if (!playing) return
        playing = false
        removeCallbacks(stepper)
        onPlayingChanged?.invoke(false)
    }

    /** Frees tile memory; called when the radar page is hidden. */
    fun trim() {
        radar.clear()
        base.clear()
    }

    /** Advances only once the next frame's visible tiles are in, so the animation never flickers. */
    private val stepper = object : Runnable {
        override fun run() {
            val n = maps?.frames?.size ?: 0
            if (!playing || n == 0) return
            val next = (frame + 1) % n
            if (frameSettled(next) || waitedMs >= MAX_WAIT_MS) {
                waitedMs = 0
                showFrame(next)
                postDelayed(this, if (next == n - 1) HOLD_LAST_MS else FRAME_MS)
            } else {
                waitedMs += 100
                postDelayed(this, 100)
            }
        }
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    // ---- Drawing -----------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        if (placeX.isNaN() || width == 0) return
        val tileZoom = floor(zoom + 0.5).toInt()
        val m = maps
        val current = m?.frames?.getOrNull(frame)
        val radarZoom = min(tileZoom, Radar.MAX_RADAR_ZOOM)

        // The loader is last-in-first-out, so ask for the least urgent tiles first: other frames (only needed
        // while playing), then the base map, then the frame on screen.
        if (m != null && playing) {
            for (f in m.frames) {
                if (f !== current) forEachTile(radarZoom) { x, y -> radar.request(Radar.radarTileUrl(m, f, radarZoom, x, y)) }
            }
        }
        forEachTile(tileZoom) { x, y ->
            val tile = base.request(Radar.baseTileUrl(tileZoom, x, y))
            if (tile != null) canvas.drawBitmap(tile, null, dst, basePaint) else drawParent(canvas, tileZoom, x, y)
        }
        if (m != null && current != null) {
            forEachTile(radarZoom) { x, y ->
                radar.request(Radar.radarTileUrl(m, current, radarZoom, x, y))?.let { canvas.drawBitmap(it, null, dst, radarPaint) }
            }
        }
        radar.commit()
        base.commit()

        val world = worldSize()
        val density = resources.displayMetrics.density
        val meShown = !meX.isNaN()
        val mx = if (meShown) screenX(meX, world) else 0f
        val my = if (meShown) screenY(meY, world) else 0f
        if (meShown) {
            // Accuracy circle, under the city marker; only when it is bigger than the dot and its halo.
            val r = min(meAccM / meWorldM * world, hypot(width.toDouble(), height.toDouble())).toFloat()
            if (r > 8 * density) {
                canvas.drawCircle(mx, my, r, meArea)
                canvas.drawCircle(mx, my, r, meEdge)
            }
        }
        val px = screenX(placeX, world)
        val py = screenY(placeY, world)
        val r = 7 * density
        canvas.drawCircle(px, py, r, markerFill)
        canvas.drawCircle(px, py, r, markerRing)
        if (meShown) {
            canvas.drawCircle(mx, my, 6 * density, meHalo)
            canvas.drawCircle(mx, my, 4 * density, meDot)
        }
    }

    /** Screen position of a Mercator x, the shortest way round the date line. */
    private fun screenX(x: Double, world: Double) = ((x - cx).let { it - Math.rint(it) } * world + width / 2.0).toFloat()

    private fun screenY(y: Double, world: Double) = ((y - cy) * world + height / 2.0).toFloat()

    /** While a tile loads, stretch an already-loaded ancestor over its spot. */
    private fun drawParent(canvas: Canvas, z: Int, x: Int, y: Int) {
        for (up in 1..4) {
            val pz = z - up
            if (pz < 0) return
            val parent: Bitmap = base.get(Radar.baseTileUrl(pz, x shr up, y shr up)) ?: continue
            val part = parent.width shr up
            val sx = (x - ((x shr up) shl up)) * part
            val sy = (y - ((y shr up) shl up)) * part
            src.set(sx, sy, sx + part, sy + part)
            canvas.drawBitmap(parent, src, dst, basePaint)
            return
        }
    }

    /** Calls [block] for every tile of zoom [z] on screen, with [dst] set to where it goes. */
    private inline fun forEachTile(z: Int, block: (x: Int, y: Int) -> Unit) {
        val n = 1 shl z
        val size = tilePx * 2.0.pow(zoom - z)
        val world = size * n
        val left = cx * world - width / 2.0
        val top = cy * world - height / 2.0
        val tx0 = floor(left / size).toInt()
        val tx1 = floor((left + width) / size).toInt()
        val ty0 = max(0, floor(top / size).toInt())
        val ty1 = min(n - 1, floor((top + height) / size).toInt())
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                dst.set(
                    (tx * size - left).roundToInt().toFloat(),
                    (ty * size - top).roundToInt().toFloat(),
                    ((tx + 1) * size - left).roundToInt().toFloat(),
                    ((ty + 1) * size - top).roundToInt().toFloat(),
                )
                block(Math.floorMod(tx, n), ty)
            }
        }
    }

    private fun frameSettled(index: Int): Boolean {
        val m = maps ?: return true
        val f = m.frames.getOrNull(index) ?: return true
        val z = min(floor(zoom + 0.5).toInt(), Radar.MAX_RADAR_ZOOM)
        var settled = true
        forEachTile(z) { x, y -> if (!radar.settled(Radar.radarTileUrl(m, f, z, x, y))) settled = false }
        return settled
    }

    private fun worldSize() = tilePx * 2.0.pow(zoom)

    private fun wrap(x: Double) = x - floor(x)

    // ---- Gestures ----------------------------------------------------------------------------------------

    private fun pan(dx: Float, dy: Float) {
        val world = worldSize()
        cx = wrap(cx + dx / world)
        cy = (cy + dy / world).coerceIn(0.0, 1.0)
        invalidate()
    }

    private fun zoomAround(delta: Double, fx: Float, fy: Float) {
        val next = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val before = worldSize()
        val ox = fx - width / 2.0
        val oy = fy - height / 2.0
        val px = cx + ox / before
        val py = cy + oy / before
        zoom = next
        val after = worldSize()
        cx = wrap(px - ox / after)
        cy = (py - oy / after).coerceIn(0.0, 1.0)
        invalidate()
    }

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomAround(ln(d.scaleFactor.toDouble()) / ln(2.0), d.focusX, d.focusY)
                return true
            }
        },
    )

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                pan(distanceX, distanceY)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomAround(1.0, e.x, e.y)
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress && event.pointerCount == 1) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ---- Helpers -----------------------------------------------------------------------------------------

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    /**
     * Calms OSM's colourful style down to the app's look: muted and tinted toward the theme background, or
     * for dark themes inverted with hues turned back round (so water stays blue) and dimmed.
     */
    private fun mapStyle(dark: Boolean, bg: Int): ColorMatrix {
        val m = ColorMatrix()
        if (dark) {
            m.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
            m.postConcat(HUE_180)
        }
        m.postConcat(ColorMatrix().apply { setSaturation(if (dark) 0.35f else 0.45f) })
        val tint = blend(Color.WHITE, bg, if (dark) 0.35f else 0.5f)
        val k = if (dark) 0.8f else 1f
        m.postConcat(ColorMatrix().apply { setScale(k * Color.red(tint) / 255f, k * Color.green(tint) / 255f, k * Color.blue(tint) / 255f, 1f) })
        return m
    }

    private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) * (1 - f) + Color.red(b) * f).roundToInt(),
        (Color.green(a) * (1 - f) + Color.green(b) * f).roundToInt(),
        (Color.blue(a) * (1 - f) + Color.blue(b) * f).roundToInt(),
    )

    private companion object {
        const val DEFAULT_ZOOM = 7.0
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 10.0
        const val FRAME_MS = 500L
        const val HOLD_LAST_MS = 1500L
        const val MAX_WAIT_MS = 3000
        const val EARTH_M = 40_075_017.0 // the equator's length

        /** Hue rotation by 180° (the W3C feColorMatrix hueRotate formula). */
        val HUE_180 = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
