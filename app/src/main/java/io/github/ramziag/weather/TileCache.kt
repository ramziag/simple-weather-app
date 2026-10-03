package io.github.ramziag.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Map tiles in memory, loaded in the background. The newest request loads first and requests for tiles that
 * scrolled away are dropped. Downloads go through Android's HTTP disk cache, so revisiting an area is local.
 * Only touched from the main thread (except [wanted], which the loader threads read).
 */
class TileCache(context: Context, maxBytes: Int) {

    private val cacheDir = File(context.cacheDir, "http")

    var onLoaded: (() -> Unit)? = null

    /** Tiles the last draw needed; queued downloads for anything else are skipped. */
    @Volatile
    private var wanted: Set<String> = emptySet()
    private var drawn = HashSet<String>()
    private val queued = ArrayList<String>()

    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val loading = HashSet<String>()
    private val failed = HashMap<String, Long>()

    fun get(url: String): Bitmap? = memory.get(url)

    /**
     * Returns the tile if it's in memory, otherwise queues it (once) and returns null. Call [commit] after
     * each draw: it marks everything requested in that draw as wanted and starts the downloads.
     */
    fun request(url: String): Bitmap? {
        drawn += url
        memory.get(url)?.let { return it }
        if (url in loading) return null
        val failedAt = failed[url]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) return null
        loading += url
        queued += url
        return null
    }

    fun commit() {
        wanted = drawn
        drawn = HashSet()
        for (url in queued) io.execute { load(url) }
        queued.clear()
    }

    private fun load(url: String) {
        installDiskCache(cacheDir)
        val stillWanted = url in wanted
        val bitmap = if (stillWanted) download(url) else null
        main.post {
            loading -= url
            if (bitmap != null) {
                memory.put(url, bitmap)
                failed -= url
            } else if (stillWanted) {
                failed[url] = System.currentTimeMillis()
            }
            if (stillWanted) onLoaded?.invoke()
        }
    }

    /** Loaded, or recently failed: either way nothing more is coming for this tile right now. */
    fun settled(url: String) = memory.get(url) != null || failed.containsKey(url)

    fun clear() = memory.evictAll()

    private fun download(url: String): Bitmap? = try {
        val c = Http.open(url).apply { useCaches = true }
        try {
            if (c.responseCode == 200) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val RETRY_MS = 30_000L

        /** Shared loader threads; last in, first out, so whatever is on screen now wins. */
        private val io = ThreadPoolExecutor(
            4, 4, 30, TimeUnit.SECONDS,
            object : LinkedBlockingDeque<Runnable>() {
                override fun offer(e: Runnable) = offerFirst(e)
            },
        ).apply { allowCoreThreadTimeOut(true) }

        /** Done lazily on a loader thread so app start-up never touches the disk cache. */
        @Synchronized
        private fun installDiskCache(dir: File) {
            if (HttpResponseCache.getInstalled() == null) {
                runCatching { HttpResponseCache.install(dir, 50L shl 20) }
            }
        }

        /** Persist the disk cache index; call when the app goes to the background. */
        fun flush() {
            runCatching { HttpResponseCache.getInstalled()?.flush() }
        }
    }
}
