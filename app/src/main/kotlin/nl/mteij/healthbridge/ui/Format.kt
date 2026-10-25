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


fun weekdayShort(isoDate: String): String = runCatching {
    java.time.LocalDate.parse(isoDate).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
}.getOrDefault("")

fun monthTitle(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
        .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

fun dayAndTime(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.getDefault())
        .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso)

fun clock(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

fun epochSeconds(iso: String): Double = Instant.parse(iso).epochSecond.toDouble()

fun clockFromEpoch(seconds: Double): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(seconds.toLong()).atZone(ZoneId.systemDefault()))

fun elapsedFromEpoch(start: Double): (Double) -> String = { seconds -> duration((seconds - start).toLong().coerceAtLeast(0)) }

fun hoursMinutes(minutes: Int): String = "${minutes / 60} h ${minutes % 60} min"

fun number(value: Double?, digits: Int = 0): String =
    if (value == null) "–" else String.format(Locale.getDefault(), "%,.${digits}f", value)

/** Friendly name for the Android package that wrote a record. */
fun sourceName(packageName: String?): String = when {
    packageName == null -> "–"
    packageName.contains("shealth") || packageName.contains("samsung.android.app.health") -> "Samsung Health"
    packageName.contains("strava") -> "Strava"
    packageName.contains("google.android.apps.fitness") -> "Google Fit"
    packageName.contains("garmin") -> "Garmin Connect"
    packageName.contains("fitbit") -> "Fitbit"
    packageName.startsWith("com.android.healthconnect") -> "Phone"
    else -> packageName.substringAfterLast('.')
}

fun speedKmh(mps: Double) = mps * 3.6
