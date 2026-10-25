package nl.mteij.healthbridge.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One colour per kind of data, the way Samsung Health and Google Fit do it. */
object MetricColor {
    val steps = Color(0xFF2EBD85)
    val calories = Color(0xFFFF8A3D)
    val distance = Color(0xFF3B9BFF)
    val heart = Color(0xFFFF4D6A)
    val sleep = Color(0xFF8E7CFF)
    val weight = Color(0xFF29B6C5)
}

@Composable
fun ElevatedSection(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(24.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors) { content() }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors) { content() }
    }
}

@Composable
fun ProgressRing(progress: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 12.dp, content: @Composable BoxScope.() -> Unit = {}) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "ring")
    val track = color.copy(alpha = 0.18f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val topLeft = Offset(s / 2, s / 2)
            val arc = Size(size.width - s, size.height - s)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round))
            if (animated > 0f) drawArc(color, -90f, 360f * animated, false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Bars for a few values (a week of steps). `null` values are drawn as an empty slot. */
@Composable
fun BarChart(values: List<Double?>, labels: List<String>, color: Color, modifier: Modifier = Modifier, highlight: Int = values.lastIndex, height: Dp = 96.dp) {
    val max = (values.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val animated by animateFloatAsState(1f, tween(700), label = "bars")
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val slot = size.width / values.size
            val barWidth = slot * 0.5f
            values.forEachIndexed { i, v ->
                val h = if (v == null) 0f else (v / max).toFloat() * size.height * animated
                val x = i * slot + (slot - barWidth) / 2
                val alpha = if (i == highlight) 1f else 0.4f
                drawRoundRect(color.copy(alpha = 0.12f), Offset(x, 0f), Size(barWidth, size.height), androidx.compose.ui.geometry.CornerRadius(barWidth / 2))
                if (h > 0f) {
                    drawRoundRect(color.copy(alpha = alpha), Offset(x, size.height - h.coerceAtLeast(barWidth)), Size(barWidth, h.coerceAtLeast(barWidth)), androidx.compose.ui.geometry.CornerRadius(barWidth / 2))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, label ->
                Text(
                    label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = if (i == highlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Filled line chart over time. Touch or drag to read a value; [format] turns y into text.
 * Points are (x, y) with x increasing; at most a few hundred are expected.
 */
@Composable
fun LineChart(
    points: List<Pair<Double, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    format: (Double) -> String = { it.toInt().toString() },
    xFormat: (Double) -> String = { "" },
    flipY: Boolean = false,
) {
    if (points.size < 2) return
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val tipBackground = MaterialTheme.colorScheme.inverseSurface
    val tipText = MaterialTheme.colorScheme.inverseOnSurface
    var selected by remember { mutableStateOf<Int?>(null) }

    val minX = points.first().first
    val maxX = points.last().first
    val rawMin = points.minOf { it.second }
    val rawMax = points.maxOf { it.second }
    val pad = ((rawMax - rawMin) * 0.08).coerceAtLeast(1.0)
    val minY = rawMin - pad
    val maxY = rawMax + pad
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(points) {
                detectTapGestures(onPress = { offset ->
                    selected = nearest(points, offset.x, size.width.toFloat(), minX, maxX)
                    tryAwaitRelease()
                    selected = null
                })
            }
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { selected = nearest(points, it.x, size.width.toFloat(), minX, maxX) },
                    onDragEnd = { selected = null },
                    onDragCancel = { selected = null },
                    onDrag = { change, _ -> selected = nearest(points, change.position.x, size.width.toFloat(), minX, maxX) },
                )
            },
    ) {
        val left = 0f
        val bottom = size.height - 18.dp.toPx()
        val top = 4.dp.toPx()
        val width = size.width
        fun px(x: Double) = left + ((x - minX) / (maxX - minX)).toFloat() * width
        fun py(y: Double): Float {
            val t = ((y - minY) / (maxY - minY)).toFloat()
            return if (flipY) top + t * (bottom - top) else bottom - t * (bottom - top)
        }

        // three light grid lines with their values
        for (i in 0..2) {
            val value = minY + (maxY - minY) * i / 2
            val y = py(value)
            drawLine(gridColor.copy(alpha = 0.5f), Offset(0f, y), Offset(width, y), 1.dp.toPx())
            drawText(measurer, format(value), Offset(2.dp.toPx(), y - 13.dp.toPx()), labelStyle)
        }
        drawText(measurer, xFormat(minX), Offset(0f, size.height - 14.dp.toPx()), labelStyle)
        val endLabel = measurer.measure(xFormat(maxX), labelStyle)
        drawText(endLabel, topLeft = Offset(width - endLabel.size.width, size.height - 14.dp.toPx()))

        val line = Path()
        points.forEachIndexed { i, (x, y) -> if (i == 0) line.moveTo(px(x), py(y)) else line.lineTo(px(x), py(y)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(px(maxX), bottom)
            lineTo(px(minX), bottom)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.02f)), startY = top, endY = bottom))
        drawPath(line, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))

        selected?.let { i ->
            val (x, y) = points[i]
            drawLine(color.copy(alpha = 0.5f), Offset(px(x), top), Offset(px(x), bottom), 1.5.dp.toPx())
            drawCircle(Color.White, 6.dp.toPx(), Offset(px(x), py(y)))
            drawCircle(color, 4.dp.toPx(), Offset(px(x), py(y)))
            val text = measurer.measure(format(y) + "  " + xFormat(x), TextStyle(fontSize = 12.sp, color = tipText))
            val boxW = text.size.width + 16.dp.toPx()
            val boxH = text.size.height + 8.dp.toPx()
            val boxX = (px(x) - boxW / 2).coerceIn(0f, width - boxW)
            drawRoundRect(tipBackground, Offset(boxX, top), Size(boxW, boxH), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
            drawText(text, topLeft = Offset(boxX + 8.dp.toPx(), top + 4.dp.toPx()))
        }
    }
}

private fun nearest(points: List<Pair<Double, Double>>, touchX: Float, width: Float, minX: Double, maxX: Double): Int {
    val x = minX + (touchX / width).coerceIn(0f, 1f) * (maxX - minX)
    var lo = 0
    var hi = points.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].first < x) lo = mid + 1 else hi = mid
    }
    return if (lo > 0 && x - points[lo - 1].first < points[lo].first - x) lo - 1 else lo
}

/** A horizontal bar split into coloured segments, with a legend underneath. */
@Composable
fun SegmentedBar(segments: List<Triple<String, Double, Color>>, modifier: Modifier = Modifier, valueText: (Double) -> String) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1.0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            segments.filter { it.second > 0 }.forEach { (_, value, color) ->
                Box(Modifier.weight((value / total).toFloat()).fillMaxSize().background(color, RoundedCornerShape(7.dp)))
            }
        }
        segments.forEach { (name, value, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
                Text(name, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                Text(valueText(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

// ---------------------------------------------------------------------------------------------
// Layout building blocks

/** Opaque header that stays at the top while the list scrolls; it clears the status bar once stuck. */
@Composable
fun StickyHeader(stuck: Boolean, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = if (stuck) 3.dp else 0.dp,
    ) {
        Column(
            (if (stuck) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** True once the item at [index] has reached the top of the list. */
@Composable
fun rememberStuck(state: androidx.compose.foundation.lazy.LazyListState, index: Int): Boolean {
    val stuck by remember(state, index) { androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex >= index } }
    return stuck
}

/** Pill shaped search field with a search icon and a clear button. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                androidx.compose.material3.IconButton(onClick = { onValueChange("") }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, null)
                }
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

data class NavTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Floating pill navigation: only the selected tab shows its label. */
@Composable
fun FloatingNavBar(tabs: List<NavTab>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val active = tab.route == selected
                val background by androidx.compose.animation.animateColorAsState(
                    if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "tab",
                )
                Row(
                    Modifier
                        .background(background, RoundedCornerShape(26.dp))
                        .clickable { onSelect(tab.route) }
                        .padding(horizontal = if (active) 18.dp else 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        tab.icon, tab.label,
                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.animation.AnimatedVisibility(active) {
                        Text(
                            tab.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.navigationBarsPadding(): Modifier = this.navigationBarsPadding()
private fun Modifier.androidx_clickable(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/** Round icon badge used for workouts and sections. */
@Composable
fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(modifier.size(size).background(color.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}
