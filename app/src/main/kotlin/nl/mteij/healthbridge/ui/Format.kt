package nl.mteij.healthbridge.ui

import android.text.format.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun relativeTime(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
private val timeOnly = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

fun dateTime(ms: Long): String = dateTime.format(Instant.ofEpochMilli(ms))
fun timeOnly(ms: Long): String = timeOnly.format(Instant.ofEpochMilli(ms))
fun isoToDateTime(iso: String): String = runCatching { dateTime.format(Instant.parse(iso)) }.getOrDefault(iso)

fun duration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

fun kilometers(meters: Double): String = String.format(Locale.getDefault(), "%.2f km", meters / 1000)

fun pace(secondsPerKm: Long): String = String.format(Locale.ROOT, "%d:%02d /km", secondsPerKm / 60, secondsPerKm % 60)

fun workoutLabel(type: String): String =
    type.replace('_', ' ').replaceFirstChar { it.uppercase() }

fun workoutEmoji(type: String): String = when {
    type.startsWith("running") -> "🏃"
    type.startsWith("biking") -> "🚴"
    type.startsWith("walking") || type == "hiking" -> "🚶"
    type.startsWith("swimming") -> "🏊"
    type == "strength_training" || type == "weightlifting" -> "🏋️"
    type == "yoga" || type == "pilates" || type == "stretching" -> "🧘"
    type == "soccer" || type == "football_american" -> "⚽"
    type == "tennis" || type == "table_tennis" || type == "squash" || type == "badminton" -> "🎾"
    type == "rowing" || type == "rowing_machine" -> "🚣"
    else -> "💪"
}
