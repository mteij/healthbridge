package nl.mteij.healthbridge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
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
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.Workout
import java.util.Locale

/** ACSM intensity classes (share of maximal heart rate): light < 64 %, moderate 64-76 %, vigorous 77-95 %, peak >= 96 %. */
private val ZoneColors = listOf(Color(0xFF9AA7B2), Color(0xFF2EBD85), Color(0xFFFF8A3D), Color(0xFFFF4D6A))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkoutDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit, onProfile: () -> Unit) {
    val details by vm.details.collectAsState()
    val workouts by vm.workouts.collectAsState()
    val overview by vm.overview.collectAsState()
    LaunchedEffect(id) { vm.loadWorkout(id) }
    val listState = rememberLazyListState()

    val detail = details[id]
    val w: Workout? = detail?.value ?: workouts.value?.firstOrNull { it.id == id } ?: overview.value?.recentWorkouts?.firstOrNull { it.id == id }

    LazyColumn(Modifier, listState, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        stickyHeader {
            StickyHeader(true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120
                    if (scrolled && w != null) Text(w.title ?: workoutLabel(w.type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (detail?.refreshing == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (w == null) {
            item { Text(detail?.error ?: stringResource(R.string.loading), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@LazyColumn
        }

        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(workoutIcon(w.type), typeColor(w.type), size = 64.dp)
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
            val tiles = buildList<Triple<String, String, Color?>> {
                add(Triple(stringResource(R.string.duration), duration(w.durationS), null))
                w.distanceM?.let { add(Triple(stringResource(R.string.distance), kilometers(it), MetricColor.distance)) }
                if (w.paceSPerKm != null) add(Triple(stringResource(R.string.pace), pace(w.paceSPerKm), null))
                else w.avgSpeedMps?.let { add(Triple(stringResource(R.string.avg_speed), String.format(Locale.getDefault(), "%.1f km/h", speedKmh(it)), null)) }
                (w.activeKcal ?: w.totalKcal)?.let { add(Triple(stringResource(R.string.calories), "${it.toInt()} kcal", MetricColor.calories)) }
                w.steps?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.steps), number(it.toDouble()), MetricColor.steps)) }
                w.elevationGainM?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.elevation), "${it.toInt()} m", null)) }
            }
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
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
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.heart_rate), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth()) {
                            StatTile(stringResource(R.string.average), "${w.heartRate.avg} bpm", Modifier.weight(1f), MetricColor.heart)
                            StatTile(stringResource(R.string.max), "${w.heartRate.max} bpm", Modifier.weight(1f))
                            StatTile(stringResource(R.string.min), "${w.heartRate.min} bpm", Modifier.weight(1f))
                        }
                        if (hr.size >= 2) {
                            val start = epochSeconds(hr.first().t)
                            LineChart(hr.map { epochSeconds(it.t) to it.bpm.toDouble() }, MetricColor.heart, xFormat = elapsedFromEpoch(start))
                        }
                    }
                }
            }
            item {
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), onClick = if (w.zonesS == null) onProfile else null) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.intensity), style = MaterialTheme.typography.titleMedium)
                        val zones = w.zonesS
                        if (zones == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.zones_need_profile), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Icon(Icons.Rounded.ChevronRight, null)
                            }
                        } else {
                            val names = listOf(R.string.zone_light, R.string.zone_moderate, R.string.zone_vigorous, R.string.zone_peak)
                            SegmentedBar(
                                segments = names.indices.map { Triple(stringResource(names[it]), zones[it].toDouble(), ZoneColors[it]) },
                                valueText = { duration(it.toLong()) },
                            )
                            w.intensityMin?.let {
                                Text(stringResource(R.string.intensity_minutes, number(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (w.splits.size >= 2) {
            item {
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.splits), style = MaterialTheme.typography.titleMedium)
                        val fastest = w.splits.minOf { it.paceSPerKm }
                        val slowest = w.splits.maxOf { it.paceSPerKm }
                        w.splits.forEach { split ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (split.partial) number(split.km, 2) else "${split.km.toInt()}", Modifier.width(40.dp), style = MaterialTheme.typography.labelLarge)
                                Text(pace(split.paceSPerKm.toLong()).removeSuffix(" /km"), Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
                                // faster splits get longer bars
                                val share = if (slowest == fastest) 1f else 0.35f + 0.65f * (slowest - split.paceSPerKm).toFloat() / (slowest - fastest)
                                Box(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Box(Modifier.fillMaxWidth(share).height(10.dp).background(MetricColor.distance.copy(alpha = if (split.paceSPerKm == fastest) 1f else 0.55f), RoundedCornerShape(5.dp)))
                                }
                                Text(split.avgHr?.toString() ?: "", style = MaterialTheme.typography.bodySmall, color = MetricColor.heart)
                            }
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
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(if (paceChart) R.string.pace else R.string.speed), style = MaterialTheme.typography.titleMedium)
                        LineChart(
                            points = speed.filter { it.mps > 0.3 }.map { epochSeconds(it.t) to if (paceChart) 1000.0 / it.mps else speedKmh(it.mps) },
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
            item { ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { Text(notes, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) } }
        }
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
    }
}
