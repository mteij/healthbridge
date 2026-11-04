package nl.mteij.healthbridge.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Suggests when the user slept from the times the screen was off: the longest stretch of at least
 * three hours that starts in the evening and ends the next morning. It is only a starting point,
 * the user adjusts it. Reading screen events needs "usage access", which the user grants in system settings.
 */
object SleepSuggestion {
    private val MIN_NIGHT = Duration.ofHours(3)

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The night that ended on [wakeDay] (the evening before until noon that day), or null when the screen was never off long enough. */
    fun suggest(context: Context, wakeDay: LocalDate): Pair<Instant, Instant>? {
        if (!hasAccess(context)) return null
        val zone = ZoneId.systemDefault()
        val from = wakeDay.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val to = minOf(wakeDay.atTime(12, 0).atZone(zone).toInstant(), Instant.now())
        if (to <= from) return null

        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(from.toEpochMilli(), to.toEpochMilli())
        val event = UsageEvents.Event()
        var screenOff: Instant? = null
        var best: Pair<Instant, Instant>? = null
        fun consider(start: Instant, end: Instant) {
            if (Duration.between(start, end) >= MIN_NIGHT && (best == null || Duration.between(start, end) > Duration.between(best!!.first, best!!.second))) best = start to end
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val at = Instant.ofEpochMilli(event.timeStamp)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> if (screenOff == null) screenOff = at
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOff?.let { consider(it, at); screenOff = null }
            }
        }
        // still off at the end of the window (asleep right now, or the history ends before the screen came on)
        screenOff?.let { consider(it, to) }
        return best
    }
}
