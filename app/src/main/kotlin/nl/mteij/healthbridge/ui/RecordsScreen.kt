package nl.mteij.healthbridge.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.ManualRecord
import nl.mteij.healthbridge.data.Workout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Standard running distances, in metres. Same list the server computes best efforts for. */
private val Distances = listOf(1000.0, 1609.344, 3000.0, 5000.0, 10000.0, 21097.5, 42195.0)

private class Best(val seconds: Double, val workout: Workout?, val manual: ManualRecord?, val date: String?)

private fun localDate(iso: String): String = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.getOrDefault(iso.take(10))

@Composable
fun RecordsScreen(vm: AppViewModel, onBack: () -> Unit, onWorkout: (String) -> Unit) {
    val state by vm.allWorkouts.collectAsState()
    val manual by vm.manualRecords.collectAsState()
    LaunchedEffect(Unit) { vm.loadAllWorkouts(); vm.loadManualRecords() }
    val workouts = state.value.orEmpty()
    var editing by remember { mutableStateOf<Double?>(null) }

    // fastest time per distance: the better of what the watch recorded and what was typed in
    val bests = Distances.associateWith { distance ->
        val recorded = workouts.filter { it.type.startsWith("running") }.flatMap { w ->
            w.bestEfforts.filter { kotlin.math.abs(it.distanceM - distance) < 1 }.map { Best(it.seconds, w, null, localDate(w.start)) }
        }.minByOrNull { it.seconds }
        val typed = manual[distance.toInt()]?.let { Best(it.seconds.toDouble(), null, it, it.date) }
        listOfNotNull(recorded, typed).minByOrNull { it.seconds }
    }
    val longest = workouts.maxByOrNull { it.distanceM ?: 0.0 }?.takeIf { (it.distanceM ?: 0.0) > 0 }
    val longestTime = workouts.maxByOrNull { it.durationS }
    val mostAscent = workouts.maxByOrNull { it.elevation?.gainM?.toDouble() ?: it.elevationGainM ?: 0.0 }
        ?.takeIf { (it.elevation?.gainM?.toDouble() ?: it.elevationGainM ?: 0.0) > 0 }

    editing?.let { distance -> ManualRecordDialog(vm, distance, manual[distance.toInt()]) { editing = null } }

    LazyColumn(Modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.personal_records), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            ElevatedSection(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.running_records), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.records_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Distances.forEach { distance ->
                        val best = bests[distance]
                        Row(
                            Modifier.fillMaxWidth().let { if (best?.workout != null) it.clickableRow { onWorkout(best.workout.id) } else it },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(distanceLabel(distance), style = MaterialTheme.typography.titleSmall)
                                best?.let {
                                    Text(
                                        (if (it.manual != null) stringResource(R.string.entered_by_hand) + " · " else "") + (it.date ?: ""),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (best != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(duration(best.seconds.toLong()), style = MaterialTheme.typography.titleMedium, color = MetricColor.steps)
                                    Text(pace((best.seconds / (distance / 1000)).toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editing = distance }) { Icon(Icons.Rounded.Edit, stringResource(R.string.sleep_edit)) }
                        }
                    }
                }
            }
        }
        item {
            ElevatedSection(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.other_records), style = MaterialTheme.typography.titleMedium)
                    longest?.let { RecordRow(stringResource(R.string.record_distance), kilometers(it.distanceM!!), it, onWorkout) }
                    longestTime?.let { RecordRow(stringResource(R.string.record_duration), duration(it.durationS), it, onWorkout) }
                    mostAscent?.let { RecordRow(stringResource(R.string.record_ascent), "${(it.elevation?.gainM?.toDouble() ?: it.elevationGainM ?: 0.0).toInt()} m", it, onWorkout) }
                }
            }
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String, workout: Workout, onWorkout: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickableRow { onWorkout(workout.id) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text("${workout.title ?: workoutLabel(workout.type)} · ${localDate(workout.start)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = MetricColor.distance)
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

@Composable
private fun distanceLabel(meters: Double): String = when {
    meters >= 42000 -> stringResource(R.string.marathon)
    meters >= 21000 -> stringResource(R.string.half_marathon)
    meters in 1600.0..1620.0 -> stringResource(R.string.one_mile)
    else -> "${(meters / 1000).toInt()} km"
}

/** Type in a time that was not recorded by a watch; it counts as a record when it beats the recorded best. */
@Composable
private fun ManualRecordDialog(vm: AppViewModel, distance: Double, existing: ManualRecord?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var hours by remember { mutableStateOf(existing?.seconds?.div(3600)?.takeIf { it > 0 }?.toString() ?: "") }
    var minutes by remember { mutableStateOf(existing?.seconds?.let { (it % 3600) / 60 }?.toString() ?: "") }
    var seconds by remember { mutableStateOf(existing?.seconds?.let { it % 60 }?.toString() ?: "") }
    var date by remember { mutableStateOf(existing?.date?.let(LocalDate::parse) ?: LocalDate.now()) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    val total = (hours.toIntOrNull() ?: 0) * 3600 + (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(distanceLabel(distance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple(hours, "h", { v: String -> hours = v.filter(Char::isDigit).take(2) }), Triple(minutes, "min", { v: String -> minutes = v.filter(Char::isDigit).take(2) }), Triple(seconds, "s", { v: String -> seconds = v.filter(Char::isDigit).take(2) }))
                        .forEach { (value, label, set) ->
                            OutlinedTextField(value, set, Modifier.weight(1f), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                }
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }, Modifier.fillMaxWidth()) { Text(date.toString()) }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.note)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = total > 0, onClick = {
                vm.saveManualRecord(ManualRecord(distance, total, date.toString(), note.trim().ifEmpty { null }))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { vm.deleteManualRecord(distance.toInt()); onDismiss() }) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
