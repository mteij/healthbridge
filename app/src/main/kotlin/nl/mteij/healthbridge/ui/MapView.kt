package nl.mteij.healthbridge.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import nl.mteij.healthbridge.BuildConfig
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.RoutePoint
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Raster tile sources. Opening a map tells the tile server roughly where the route is. These public
 * servers are meant for light, attributed use; tiles are cached on disk and in memory.
 */
enum class MapStyle(val key: String, val label: Int, val credit: String, val url: (Int, Int, Int) -> String) {
    STANDARD("standard", R.string.map_standard, "© OpenStreetMap", { z, x, y -> "https://tile.openstreetmap.org/$z/$x/$y.png" }),
    TERRAIN("terrain", R.string.map_terrain, "© OpenTopoMap (CC-BY-SA) · © OpenStreetMap", { z, x, y -> "https://tile.opentopomap.org/$z/$x/$y.png" }),
    SATELLITE("satellite", R.string.map_satellite, "Esri, Maxar, Earthstar Geographics", { z, x, y -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x" }),
    DARK("dark", R.string.map_dark, "© OpenStreetMap · © CARTO", { z, x, y -> "https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png" });

    companion object {
        fun saved(): MapStyle = entries.firstOrNull { it.key == Graph.prefs.mapStyle } ?: STANDARD
    }
}

private object TileLoader {
    private var client: OkHttpClient? = null
    private val memory = LruCache<String, ImageBitmap>(160)

    private fun client(context: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "map-tiles"), 100L * 1024 * 1024))
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Healthbridge/${BuildConfig.VERSION_NAME} (https://github.com/mteij/healthbridge)").build())
            }
            .build().also { client = it }
    }

    private fun key(style: MapStyle, z: Int, x: Int, y: Int) = "${style.key}/$z/$x/$y"

    fun cached(style: MapStyle, z: Int, x: Int, y: Int): ImageBitmap? = memory.get(key(style, z, x, y))

    suspend fun load(context: Context, style: MapStyle, z: Int, x: Int, y: Int): ImageBitmap? {
        cached(style, z, x, y)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                client(context).newCall(Request.Builder().url(style.url(z, x, y)).build()).execute().use { response ->
                    response.body?.byteStream()?.let { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                }
            }.getOrNull()?.also { memory.put(key(style, z, x, y), it) }
        }
    }
}

private const val TILE_PX = 512.0
private const val MAX_ZOOM = 17.0

/** Web Mercator position as a fraction of the world: x and y both in 0..1. */
private fun fracX(lon: Double) = (lon + 180) / 360
private fun fracY(lat: Double): Double {
    val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1 - ln(tan(rad) + 1 / kotlin.math.cos(rad)) / PI) / 2
}

private fun log2(v: Double) = ln(v) / ln(2.0)

/**
 * A slippy map with polylines drawn on top. A single route is drawn bold with start and end dots;
 * `heat` draws many routes translucent and additive so frequently used roads glow (a personal heatmap).
 * Only an [interactive] map takes pan and pinch gestures, so one inside a scrolling list does not steal swipes.
 */
@Composable
fun TileMap(
    lines: List<List<Pair<Double, Double>>>,
    style: MapStyle,
    modifier: Modifier = Modifier,
    height: Dp = 280.dp,
    interactive: Boolean = false,
    heat: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val lineColor = MaterialTheme.colorScheme.primary
    val frac = remember(lines) { lines.map { line -> line.map { fracX(it.second) to fracY(it.first) } } }

    // initial view: fit the route; for a heatmap fit where most of the activity is, ignoring far away outliers
    val bounds = remember(frac) {
        val xs = frac.flatMap { l -> l.map { it.first } }.sorted()
        val ys = frac.flatMap { l -> l.map { it.second } }.sorted()
        if (xs.isEmpty()) doubleArrayOf(0.5, 0.5, 0.5, 0.5) else {
            val lo = if (heat) 0.05 else 0.0
            val hi = if (heat) 0.95 else 1.0
            fun q(v: List<Double>, p: Double) = v[((v.size - 1) * p).toInt()]
            doubleArrayOf(q(xs, lo), q(xs, hi), q(ys, lo), q(ys, hi))
        }
    }
    var zoom by remember(bounds) { mutableDoubleStateOf(-1.0) }
    var centerX by remember(bounds) { mutableDoubleStateOf((bounds[0] + bounds[1]) / 2) }
    var centerY by remember(bounds) { mutableDoubleStateOf((bounds[2] + bounds[3]) / 2) }
    val redraw = remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (zoom < 0) {
            val spanX = (bounds[1] - bounds[0]).coerceAtLeast(1e-7)
            val spanY = (bounds[3] - bounds[2]).coerceAtLeast(1e-7)
            zoom = log2(min(w * 0.8 / (spanX * TILE_PX), h * 0.8 / (spanY * TILE_PX))).coerceIn(2.0, MAX_ZOOM)
        }
        val mapSize = TILE_PX * 2.0.pow(zoom)
        val z = floor(zoom).toInt().coerceIn(2, MAX_ZOOM.toInt())
        val tilePx = (TILE_PX * 2.0.pow(zoom - z)).toFloat()
        val originX = (centerX * mapSize - w / 2).toFloat()
        val originY = (centerY * mapSize - h / 2).toFloat()
        val limit = 1 shl z
        val xs = floor(originX / tilePx).toInt()..floor((originX + w) / tilePx).toInt()
        val ys = floor(originY / tilePx).toInt()..floor((originY + h) / tilePx).toInt()

        LaunchedEffect(style, z, xs, ys) {
            coroutineScope {
                xs.flatMap { x -> ys.map { y -> x to y } }
                    .filter { (x, y) -> y in 0 until limit && TileLoader.cached(style, z, Math.floorMod(x, limit), y) == null }
                    .map { (x, y) -> async { if (TileLoader.load(context, style, z, Math.floorMod(x, limit), y) != null) redraw.intValue++ } }
                    .awaitAll()
            }
        }

        val gestures = if (interactive) {
            Modifier.pointerInput(Unit) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val oldSize = TILE_PX * 2.0.pow(zoom)
                    val newZoom = (zoom + log2(gestureZoom.toDouble())).coerceIn(2.0, MAX_ZOOM)
                    val newSize = TILE_PX * 2.0.pow(newZoom)
                    // keep the point under the fingers where it is, then follow the drag
                    val worldX = centerX * oldSize + centroid.x - size.width / 2
                    val worldY = centerY * oldSize + centroid.y - size.height / 2
                    val scale = newSize / oldSize
                    centerX = ((worldX * scale - centroid.x + size.width / 2) - pan.x) / newSize
                    centerY = ((worldY * scale - centroid.y + size.height / 2) - pan.y) / newSize
                    zoom = newZoom
                }
            }
        } else Modifier

        Canvas(Modifier.fillMaxWidth().height(height).then(gestures)) {
            redraw.intValue // redraw when a tile arrives
            for (x in xs) for (y in ys) {
                TileLoader.cached(style, z, Math.floorMod(x, limit), y)?.let { image ->
                    drawImage(
                        image, dstOffset = IntOffset((x * tilePx - originX).toInt(), (y * tilePx - originY).toInt()),
                        dstSize = IntSize(tilePx.toInt() + 1, tilePx.toInt() + 1),
                    )
                }
            }
            fun at(p: Pair<Double, Double>) = Offset((p.first * mapSize - originX).toFloat(), (p.second * mapSize - originY).toFloat())
            frac.forEach { line ->
                if (line.size < 2) return@forEach
                val path = Path().apply { line.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
                if (heat) {
                    drawPath(path, Color(0xFFFF5A1F).copy(alpha = 0.45f), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
                } else {
                    drawPath(path, Color.White, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, lineColor, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (!heat) frac.firstOrNull()?.let { line ->
                if (line.size >= 2) {
                    drawCircle(Color(0xFF2EBD85), 7.dp.toPx(), at(line.first()))
                    drawCircle(Color(0xFFFF4D6A), 7.dp.toPx(), at(line.last()))
                }
            }
        }
        Text(
            style.credit, Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = Color.DarkGray,
        )
    }
}

@Composable
fun MapStylePicker(selected: MapStyle, onSelect: (MapStyle) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapStyle.entries.forEach { style ->
            FilterChip(selected = style == selected, onClick = { Graph.prefs.mapStyle = style.key; onSelect(style) }, label = { Text(stringResource(style.label)) })
        }
    }
}

/** One workout's route with a choice of map layers. */
@Composable
fun RouteMap(points: List<RoutePoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    var style by remember { mutableStateOf(MapStyle.saved()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TileMap(listOf(points.map { it.lat to it.lon }), style)
        MapStylePicker(style, { style = it })
    }
}
