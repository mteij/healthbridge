package nl.mteij.healthbridge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

private enum class Sort(val label: Int) { NEWEST(R.string.sort_newest), LONGEST(R.string.sort_longest), FARTHEST(R.string.sort_farthest) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkoutsScreen(vm: AppViewModel, onWorkout: (String) -> Unit) {
    val state by vm.workouts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.loadWorkouts() }

    val all = state.value.orEmpty()
    val types = all.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }
    val text = query.trim().lowercase()
    val shown = all
        .filter { filter == null || it.type == filter }
        .filter { text.isEmpty() || (it.title ?: "").lowercase().contains(text) || workoutLabel(it.type).lowercase().contains(text) || dayAndTime(it.start).lowercase().contains(text) }
        .let { list ->
            when (sort) {
                Sort.NEWEST -> list
                Sort.LONGEST -> list.sortedByDescending { it.durationS }
                Sort.FARTHEST -> list.sortedByDescending { it.distanceM ?: 0.0 }
            }
        }
    val weekAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(7))
    val week = all.filter { runCatching { java.time.Instant.parse(it.start) }.getOrNull()?.isAfter(weekAgo) == true }

    LazyColumn(Modifier, listState, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item { Text(stringResource(R.string.tab_workouts), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }

        if (all.isNotEmpty()) {
            item {
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Row(Modifier.padding(20.dp)) {
                        StatTile(stringResource(R.string.last_7_days), "${week.size}", Modifier.weight(1f))
                        StatTile(stringResource(R.string.distance), kilometers(week.sumOf { it.distanceM ?: 0.0 }), Modifier.weight(1f), MetricColor.distance)
                        StatTile(stringResource(R.string.time), hoursMinutes((week.sumOf { it.durationS } / 60).toInt()), Modifier.weight(1.2f))
                    }
                }
            }
            item { Records(all, Modifier.padding(horizontal = 16.dp)) }
        }

        stickyHeader {
            StickyHeader(rememberStuck(listState, if (all.isNotEmpty()) 4 else 2)) {
                SearchField(query, { query = it }, stringResource(R.string.search_workouts))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        FilterChip(
                            selected = sort != Sort.NEWEST, onClick = { sortMenu = true },
                            leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)) },
                            label = { Text(stringResource(sort.label)) },
                        )
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            Sort.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(stringResource(option.label)) }, onClick = { sort = option; sortMenu = false })
                            }
                        }
                    }
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.all)) })
                    types.forEach { (type, count) ->
                        FilterChip(
                            selected = filter == type, onClick = { filter = if (filter == type) null else type },
                            leadingIcon = { Icon(workoutIcon(type), null, Modifier.size(18.dp)) },
                            label = { Text("${workoutLabel(type)} · $count") },
                        )
                    }
                }
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        state.error?.let { item { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) } }
        if (state.value != null && shown.isEmpty()) {
            item { Text(stringResource(R.string.no_workouts), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        var lastMonth = ""
        shown.forEach { w ->
            if (sort == Sort.NEWEST) {
                val month = monthTitle(w.start)
                if (month != lastMonth) {
                    lastMonth = month
                    item(key = "m-$month") {
                        Text(month.replaceFirstChar { it.uppercase() }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = w.id) { WorkoutRow(w, Modifier.padding(horizontal = 16.dp)) { onWorkout(w.id) } }
        }
    }
}

/** Longest and fastest efforts across the loaded workouts. */
@Composable
private fun Records(all: List<Workout>, modifier: Modifier) {
    val longest = all.maxByOrNull { it.distanceM ?: 0.0 }?.takeIf { (it.distanceM ?: 0.0) > 0 }
    val longestTime = all.maxByOrNull { it.durationS }
    val fastestRun = all.filter { it.type.startsWith("running") && (it.distanceM ?: 0.0) >= 3000 && it.paceSPerKm != null }.minByOrNull { it.paceSPerKm!! }
    if (longest == null && fastestRun == null) return
    ElevatedSection(modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp)) {
            longest?.let { StatTile(stringResource(R.string.record_distance), kilometers(it.distanceM!!), Modifier.weight(1f)) }
            longestTime?.let { StatTile(stringResource(R.string.record_duration), duration(it.durationS), Modifier.weight(1f)) }
            fastestRun?.let { StatTile(stringResource(R.string.record_pace), pace(it.paceSPerKm!!), Modifier.weight(1f), MetricColor.steps) }
        }
    }
}

@Composable
fun WorkoutRow(w: Workout, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ElevatedSection(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(workoutIcon(w.type), typeColor(w.type))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(w.title ?: workoutLabel(w.type), style = MaterialTheme.typography.titleMedium)
                Text(dayAndTime(w.start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val stats = buildList {
                    w.distanceM?.let { add(kilometers(it)) }
                    add(duration(w.durationS))
                    w.heartRate?.let { add("${it.avg} bpm") }
                }
                Text(stats.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { (minutes, color) ->
            Box(Modifier.weight(minutes.toFloat()).height(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        }
    }
}
