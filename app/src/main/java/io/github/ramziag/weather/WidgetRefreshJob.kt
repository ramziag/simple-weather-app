package io.github.ramziag.weather

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Fetches fresh forecasts for the widgets' cities and redraws them. A job (rather than the widget broadcast
 * itself) because the app only has network access in the background while a job or broadcast is running,
 * and a job gets minutes instead of seconds.
 */
class WidgetRefreshJob : JobService() {

    @Volatile
    private var stopped = false

    // Held until jobFinished: Android 16+ stops and penalises jobs whose parameters were garbage collected.
    private var params: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        this.params = params
        stopped = false
        val app = applicationContext
        Thread {
            try {
                val ids = Widgets.ids(app)
                for (place in Widgets.places(app, ids)) {
                    if (stopped) break
                    // Failures (offline, timeouts) are fine: the cached forecast stays and the next tick retries.
                    runCatching { ForecastFiles.load(app, place, Widgets.MAX_AGE_MS) }
                }
                if (!stopped) Widgets.render(app, ids)
            } finally {
                if (!stopped) jobFinished(params, false)
                this.params = null
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return false // no retry; the 30-minute widget tick schedules the next attempt
    }
}
