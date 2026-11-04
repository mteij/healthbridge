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
// Route map on OpenStreetMap tiles

/**
 * Loads OpenStreetMap raster tiles. The tile usage policy asks for an identifying User-Agent and for
 * caching, so tiles are kept on disk. Opening a map reveals roughly where the route is to the tile
 * server, which is why nothing is requested for workouts without a route.
 */
private object TileLoader {
    private var client: OkHttpClient? = null

    private fun client(context: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "map-tiles"), 50L * 1024 * 1024))
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Healthbridge/${BuildConfig.VERSION_NAME} (https://github.com/mteij/healthbridge)").build())
            }
            .build().also { client = it }
    }

    suspend fun load(context: Context, z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            client(context).newCall(Request.Builder().url("https://tile.openstreetmap.org/$z/$x/$y.png").build()).execute().use { response ->
                response.body?.byteStream()?.let { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }
        }.getOrNull()
    }
}

private const val TILE_PX = 512f

private fun worldX(lon: Double, zoom: Int) = (lon + 180) / 360 * 2.0.pow(zoom) * TILE_PX

private fun worldY(lat: Double, zoom: Int): Double {
    val rad = Math.toRadians(lat)
    return (1 - ln(tan(rad) + 1 / kotlin.math.cos(rad)) / PI) / 2 * 2.0.pow(zoom) * TILE_PX
}

@Composable
fun RouteMap(points: List<RoutePoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val context = LocalContext.current.applicationContext
    val lineColor = MaterialTheme.colorScheme.primary
    val tiles = remember(points) { mutableStateMapOf<Triple<Int, Int, Int>, ImageBitmap>() }

    BoxWithConstraints(modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        // the largest zoom at which the whole route fits with some margin
        var zoom = 17
        while (zoom > 2 && (worldX(maxLon, zoom) - worldX(minLon, zoom) > width * 0.8f || worldY(minLat, zoom) - worldY(maxLat, zoom) > height * 0.8f)) zoom--
        val originX = ((worldX(minLon, zoom) + worldX(maxLon, zoom)) / 2 - width / 2).toFloat()
        val originY = ((worldY(minLat, zoom) + worldY(maxLat, zoom)) / 2 - height / 2).toFloat()
        val limit = 1 shl zoom
        val xs = Math.floor(originX / TILE_PX.toDouble()).toInt()..Math.floor((originX + width) / TILE_PX.toDouble()).toInt()
        val ys = Math.floor(originY / TILE_PX.toDouble()).toInt()..Math.floor((originY + height) / TILE_PX.toDouble()).toInt()

        LaunchedEffect(points, zoom) {
            coroutineScope {
                xs.flatMap { x -> ys.map { y -> x to y } }
                    .filter { (x, y) -> y in 0 until limit && Triple(zoom, Math.floorMod(x, limit), y) !in tiles }
                    .map { (x, y) -> async { TileLoader.load(context, zoom, Math.floorMod(x, limit), y)?.let { tiles[Triple(zoom, Math.floorMod(x, limit), y)] = it } } }
                    .awaitAll()
            }
        }

        Canvas(Modifier.fillMaxWidth().height(280.dp)) {
            for (x in xs) for (y in ys) {
                tiles[Triple(zoom, Math.floorMod(x, limit), y)]?.let { image ->
                    drawImage(
                        image, dstOffset = IntOffset((x * TILE_PX - originX).toInt(), (y * TILE_PX - originY).toInt()),
                        dstSize = IntSize(TILE_PX.toInt() + 1, TILE_PX.toInt() + 1),
                    )
                }
            }
            fun at(p: RoutePoint) = Offset((worldX(p.lon, zoom) - originX).toFloat(), (worldY(p.lat, zoom) - originY).toFloat())
            val path = Path().apply {
                points.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            }
            drawPath(path, Color.White, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, lineColor, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Color(0xFF2EBD85), 7.dp.toPx(), at(points.first()))
            drawCircle(Color(0xFFFF4D6A), 7.dp.toPx(), at(points.last()))
        }
        Text(
            "© OpenStreetMap", Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = Color.DarkGray,
        )
    }
}


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
