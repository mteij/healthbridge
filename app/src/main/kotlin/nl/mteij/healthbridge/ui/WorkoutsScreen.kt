package nl.mteij.healthbridge.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.SleepNight
import nl.mteij.healthbridge.data.Workout

@Composable
fun WorkoutsScreen(vm: AppViewModel, onWorkout: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by vm.workouts.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.loadWorkouts() }

    val all = state.value.orEmpty()
    val types = all.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val shown = all.filter { filter == null || it.type == filter }
    val weekAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(7))
    val week = all.filter { runCatching { java.time.Instant.parse(it.start) }.getOrNull()?.isAfter(weekAgo) == true }

    LazyColumn(modifier.statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.tab_workouts), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (all.isNotEmpty()) {
            item {
                ElevatedSection(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp)) {
                        StatTile(stringResource(R.string.last_7_days), "${week.size}", Modifier.weight(1f))
                        StatTile(stringResource(R.string.distance), kilometers(week.sumOf { it.distanceM ?: 0.0 }), Modifier.weight(1f), MetricColor.distance)
                        StatTile(stringResource(R.string.time), hoursMinutes((week.sumOf { it.durationS } / 60).toInt()), Modifier.weight(1.2f))
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.all)) })
                    types.forEach { type ->
                        FilterChip(selected = filter == type, onClick = { filter = type }, label = { Text("${workoutEmoji(type)} ${workoutLabel(type)}") })
                    }
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.value != null && shown.isEmpty()) item { Text(stringResource(R.string.no_workouts), color = MaterialTheme.colorScheme.onSurfaceVariant) }

        var lastMonth = ""
        shown.forEach { w ->
            val month = monthTitle(w.start)
            if (month != lastMonth) {
                lastMonth = month
                item(key = "m-$month") {
                    Text(month.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item(key = w.id) { WorkoutRow(w) { onWorkout(w.id) } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun WorkoutRow(w: Workout, onClick: () -> Unit) {
    ElevatedSection(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(typeColor(w.type).copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Text(workoutEmoji(w.type), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(w.title ?: workoutLabel(w.type), style = MaterialTheme.typography.titleMedium)
                Text(dayAndTime(w.start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val stats = buildList {
                    w.distanceM?.let { add(kilometers(it)) }
                    add(duration(w.durationS))
                    w.heartRate?.let { add("♥ ${it.avg}") }
                }
                Text(stats.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

fun typeColor(type: String) = when {
    type.startsWith("running") || type.startsWith("walking") || type == "hiking" -> MetricColor.steps
    type.startsWith("biking") -> MetricColor.distance
    type.startsWith("swimming") || type.startsWith("rowing") -> MetricColor.weight
    type == "yoga" || type == "pilates" || type == "stretching" -> MetricColor.sleep
    else -> MetricColor.calories
}

/** A thin strip showing how a night was split over sleep stages. */
@Composable
fun SleepStageStrip(night: SleepNight, modifier: Modifier = Modifier) {
    val order = listOf("deep" to 0xFF5B4BD6, "light" to 0xFF8E7CFF, "rem" to 0xFFB9A9FF, "awake" to 0xFFFFC857)
    val parts = order.mapNotNull { (key, color) -> night.stagesMin[key]?.takeIf { it > 0 }?.let { it to androidx.compose.ui.graphics.Color(color) } }
    if (parts.isEmpty()) return
    Row(modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { (minutes, color) -> Box(Modifier.weight(minutes.toFloat()).fillMaxWidth().height(10.dp).background(color, CircleShape)) }
    }
}
