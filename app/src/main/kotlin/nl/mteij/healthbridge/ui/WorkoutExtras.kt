package nl.mteij.healthbridge.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import nl.mteij.healthbridge.BuildConfig
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.BestEffort
import nl.mteij.healthbridge.data.Lap
import nl.mteij.healthbridge.data.RoutePoint
import nl.mteij.healthbridge.data.WorkoutSegment
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

// ---------------------------------------------------------------------------------------------
// Cards

@Composable
fun BestEffortsCard(efforts: List<BestEffort>, modifier: Modifier = Modifier) {
    if (efforts.isEmpty()) return
    ElevatedSection(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.best_efforts), style = MaterialTheme.typography.titleMedium)
            efforts.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(effortLabel(e.distanceM), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(duration(e.seconds.toLong()), Modifier.width(80.dp), style = MaterialTheme.typography.titleSmall)
                    Text(pace((e.seconds / (e.distanceM / 1000)).toLong()), Modifier.width(84.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun effortLabel(meters: Double): String = when {
    meters >= 42000 -> stringResource(R.string.marathon)
    meters >= 21000 -> stringResource(R.string.half_marathon)
    meters in 1600.0..1620.0 -> stringResource(R.string.one_mile)
    else -> "${String.format(Locale.getDefault(), "%.0f", meters / 1000)} km"
}

@Composable
fun LapsCard(laps: List<Lap>, modifier: Modifier = Modifier) {
    if (laps.isEmpty()) return
    ElevatedSection(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.laps), style = MaterialTheme.typography.titleMedium)
            laps.forEach { lap ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${lap.lap}", Modifier.width(32.dp), style = MaterialTheme.typography.labelLarge)
                    Text(duration(lap.seconds.toLong()), Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(lap.distanceM?.let { kilometers(it.toDouble()) } ?: "", Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(lap.paceSPerKm?.let { pace(it.toLong()) } ?: "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(lap.avgHr?.toString() ?: "", style = MaterialTheme.typography.bodySmall, color = MetricColor.heart)
                }
            }
        }
    }
}

@Composable
fun SegmentsCard(segments: List<WorkoutSegment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) return
    ElevatedSection(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.segments), style = MaterialTheme.typography.titleMedium)
            segments.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.type.replace('_', ' ').replaceFirstChar { it.uppercase() } + (s.repetitions?.let { " × $it" } ?: ""),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(clock(s.start), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(duration(s.seconds.toLong()), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}
