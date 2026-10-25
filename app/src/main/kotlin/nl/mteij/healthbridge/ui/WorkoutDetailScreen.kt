package nl.mteij.healthbridge.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.HrPoint
import nl.mteij.healthbridge.data.Workout
import java.util.Locale

private val ZoneColors = listOf(Color(0xFF9AA7B2), Color(0xFF3B9BFF), Color(0xFF2EBD85), Color(0xFFFF8A3D), Color(0xFFFF4D6A))

/** Seconds spent in each of five zones (share of max heart rate: <60, 60-70, 70-80, 80-90, 90+). */
private fun zoneSeconds(points: List<HrPoint>, maxHr: Int): DoubleArray {
    val seconds = DoubleArray(5)
    points.zipWithNext().forEach { (a, b) ->
        val dt = (epochSeconds(b.t) - epochSeconds(a.t)).coerceIn(0.0, 120.0)
        val share = a.bpm.toDouble() / maxHr
        val zone = when {
            share < 0.6 -> 0
            share < 0.7 -> 1
            share < 0.8 -> 2
            share < 0.9 -> 3
            else -> 4
        }
        seconds[zone] += dt
    }
    return seconds
}

@Composable
fun WorkoutDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val details by vm.details.collectAsState()
    val workouts by vm.workouts.collectAsState()
    val overview by vm.overview.collectAsState()
    LaunchedEffect(id) { vm.loadWorkout(id) }

    val detail = details[id]
    val w: Workout? = detail?.value ?: workouts.value?.firstOrNull { it.id == id } ?: overview.value?.recentWorkouts?.firstOrNull { it.id == id }

    LazyColumn(modifier.statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            }
            if (detail?.refreshing == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (w == null) {
            item { Text(detail?.error ?: stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@LazyColumn
        }

        item {
            ElevatedSection(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).background(typeColor(w.type).copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                        Text(workoutEmoji(w.type), style = MaterialTheme.typography.headlineMedium)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(w.title ?: workoutLabel(w.type), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(dayAndTime(w.start) + " – " + clock(w.end), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sourceName(w.source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            val paceBased = w.paceSPerKm != null
            val tiles = buildList<Triple<String, String, Color?>> {
                add(Triple(stringResource(R.string.duration), duration(w.durationS), null))
                w.distanceM?.let { add(Triple(stringResource(R.string.distance), kilometers(it), MetricColor.distance)) }
                if (paceBased) add(Triple(stringResource(R.string.pace), pace(w.paceSPerKm!!), null))
                else w.avgSpeedMps?.let { add(Triple(stringResource(R.string.avg_speed), String.format(Locale.getDefault(), "%.1f km/h", speedKmh(it)), null)) }
                (w.activeKcal ?: w.totalKcal)?.let { add(Triple(stringResource(R.string.calories), "${it.toInt()} kcal", MetricColor.calories)) }
                w.steps?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.steps), number(it.toDouble()), MetricColor.steps)) }
                w.elevationGainM?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.elevation), "${it.toInt()} m", null)) }
            }
            ElevatedSection(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { (label, value, color) -> StatTile(label, value, Modifier.weight(1f), color ?: MaterialTheme.colorScheme.onSurface) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        val hr = w.samples?.heartRate.orEmpty()
        if (w.heartRate != null) {
            item {
                ElevatedSection(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.heart_rate), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth()) {
                            StatTile(stringResource(R.string.average), "${w.heartRate.avg} bpm", Modifier.weight(1f), MetricColor.heart)
                            StatTile(stringResource(R.string.max), "${w.heartRate.max} bpm", Modifier.weight(1f))
                            StatTile(stringResource(R.string.min), "${w.heartRate.min} bpm", Modifier.weight(1f))
                        }
                        if (hr.size >= 2) {
                            val start = epochSeconds(hr.first().t)
                            LineChart(
                                points = hr.map { epochSeconds(it.t) to it.bpm.toDouble() }, color = MetricColor.heart,
                                format = { it.toInt().toString() }, xFormat = elapsedFromEpoch(start),
                            )
                            val maxHr = Graph.prefs.maxHeartRate
                            val seconds = zoneSeconds(hr, maxHr)
                            val names = listOf(R.string.zone_light, R.string.zone_moderate, R.string.zone_aerobic, R.string.zone_hard, R.string.zone_max)
                            SegmentedBar(
                                segments = names.indices.map { Triple(stringResource(names[it]), seconds[it], ZoneColors[it]) },
                                valueText = { duration(it.toLong()) },
                            )
                            Text(stringResource(R.string.zone_note, maxHr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        val speed = w.samples?.speed.orEmpty()
        if (speed.size >= 2) {
            item {
                val start = epochSeconds(speed.first().t)
                val paceChart = w.paceSPerKm != null
                ElevatedSection(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(if (paceChart) R.string.pace else R.string.speed), style = MaterialTheme.typography.titleMedium)
                        LineChart(
                            points = speed.filter { it.mps > 0.3 }.map {
                                epochSeconds(it.t) to if (paceChart) 1000.0 / it.mps else speedKmh(it.mps)
                            },
                            color = MetricColor.distance,
                            format = { if (paceChart) String.format(Locale.ROOT, "%d:%02d", it.toInt() / 60, it.toInt() % 60) else String.format(Locale.getDefault(), "%.1f", it) },
                            xFormat = elapsedFromEpoch(start),
                            flipY = paceChart,
                        )
                    }
                }
            }
        }

        w.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            item { ElevatedSection(Modifier.fillMaxWidth()) { Text(notes, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
