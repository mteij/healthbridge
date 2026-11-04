package nl.mteij.healthbridge.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.SleepNight
import nl.mteij.healthbridge.health.SleepSuggestion
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Add or adjust a night by hand. New nights start from the screen-off suggestion when usage access is granted. */
@Composable
fun SleepEditor(vm: AppViewModel, existing: SleepNight?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var wakeDay by remember { mutableStateOf(existing?.date?.let(LocalDate::parse) ?: LocalDate.now()) }
    var bedtime by remember { mutableStateOf(existing?.let { Instant.parse(it.start).atZone(zone).toLocalTime().withSecond(0).withNano(0) } ?: LocalTime.of(23, 0)) }
    var wake by remember { mutableStateOf(existing?.end?.let { Instant.parse(it).atZone(zone).toLocalTime().withSecond(0).withNano(0) } ?: LocalTime.of(7, 0)) }
    var suggested by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val hasAccess = remember { SleepSuggestion.hasAccess(context) }

    LaunchedEffect(wakeDay) {
        if (existing != null) return@LaunchedEffect
        SleepSuggestion.suggest(context, wakeDay)?.let { (from, to) ->
            bedtime = from.atZone(zone).toLocalTime().withSecond(0).withNano(0)
            wake = to.atZone(zone).toLocalTime().withSecond(0).withNano(0)
            suggested = true
        } ?: run { suggested = false }
    }

    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    fun pick(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)); suggested = false }, initial.hour, initial.minute, true).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.sleep_add else R.string.sleep_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { wakeDay = wakeDay.minusDays(1) }, enabled = existing == null) { Icon(Icons.Rounded.ChevronLeft, null) }
                    Text(
                        wakeDay.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { wakeDay = wakeDay.plusDays(1) }, enabled = existing == null && wakeDay < LocalDate.now()) { Icon(Icons.Rounded.ChevronRight, null) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { pick(bedtime) { bedtime = it } }, Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.bedtime), style = MaterialTheme.typography.labelSmall)
                            Text(bedtime.format(timeFormat))
                        }
                    }
                    OutlinedButton(onClick = { pick(wake) { wake = it } }, Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.wake_up), style = MaterialTheme.typography.labelSmall)
                            Text(wake.format(timeFormat))
                        }
                    }
                }
                when {
                    suggested -> Text(stringResource(R.string.sleep_suggested), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    !hasAccess && existing == null -> TextButton(onClick = { runCatching { context.startActivity(SleepSuggestion.settingsIntent()) } }) {
                        Text(stringResource(R.string.sleep_usage_access))
                    }
                }
                if (failed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // bedtime in the afternoon or evening belongs to the day before the wake-up day
                val startDay = if (bedtime >= LocalTime.NOON) wakeDay.minusDays(1) else wakeDay
                val start = startDay.atTime(bedtime).atZone(zone).toInstant()
                val end = wakeDay.atTime(wake).atZone(zone).toInstant()
                if (end <= start) { failed = true; return@TextButton }
                vm.saveManualSleep(start, end) { error -> if (error == null) onDismiss() else failed = true }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (existing?.source == MANUAL_SLEEP_SOURCE && existing.id != null) {
                    TextButton(onClick = { vm.deleteManualSleep(existing.id); onDismiss() }) { Text(stringResource(R.string.delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
