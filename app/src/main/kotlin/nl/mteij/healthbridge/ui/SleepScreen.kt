package nl.mteij.healthbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

@Composable
fun SleepScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.sleep.collectAsState()
    LaunchedEffect(Unit) { vm.loadSleep() }
    val nights = state.value.orEmpty().sortedBy { it.date }
    val last = nights.lastOrNull()

    LazyColumn(modifier.statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.sleep), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        if (last == null) {
            item { Text(state.error ?: stringResource(R.string.no_sleep), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@LazyColumn
        }
        item {
            ElevatedSection(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.last_night), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(last.asleepMin?.let { hoursMinutes(it) } ?: "–", style = MaterialTheme.typography.headlineMedium, color = MetricColor.sleep, fontWeight = FontWeight.Bold)
                    Text("${clock(last.start)} – ${last.end?.let { clock(it) } ?: ""}  ·  ${stringResource(R.string.in_bed)} ${hoursMinutes(last.inBedMin)}", style = MaterialTheme.typography.bodySmall)
                    val stages = listOf("deep" to R.string.stage_deep, "light" to R.string.stage_light, "rem" to R.string.stage_rem, "awake" to R.string.stage_awake)
                    val colors = listOf(Color(0xFF5B4BD6), Color(0xFF8E7CFF), Color(0xFFB9A9FF), Color(0xFFFFC857))
                    if (last.stagesMin.isNotEmpty()) {
                        SegmentedBar(
                            segments = stages.mapIndexed { i, (key, label) -> Triple(stringResource(label), (last.stagesMin[key] ?: 0).toDouble(), colors[i]) },
                            valueText = { hoursMinutes(it.toInt()) },
                        )
                    }
                }
            }
        }
        item {
            ElevatedSection(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.last_14_nights), style = MaterialTheme.typography.titleMedium)
                    BarChart(nights.map { it.asleepMin?.div(60.0) }, nights.map { weekdayShort(it.date) }, MetricColor.sleep)
                }
            }
        }
        items(nights.reversed(), key = { it.start }) { night ->
            ElevatedSection(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        Text(night.date, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(night.asleepMin?.let { hoursMinutes(it) } ?: "–", style = MaterialTheme.typography.titleSmall, color = MetricColor.sleep)
                    }
                    SleepStageStrip(night)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
