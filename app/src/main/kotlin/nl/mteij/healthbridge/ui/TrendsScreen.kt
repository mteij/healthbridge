package nl.mteij.healthbridge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.SeriesPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private enum class Period { WEEK, MONTH, YEAR }

private enum class Metric(val key: String, val label: Int, val color: Color, val icon: ImageVector, val cumulative: Boolean) {
    STEPS("steps", R.string.steps, MetricColor.steps, Icons.Rounded.DirectionsWalk, true),
    DISTANCE("distance", R.string.distance, MetricColor.distance, Icons.Rounded.Straighten, true),
    CALORIES("active_calories", R.string.active_calories, MetricColor.calories, Icons.Rounded.LocalFireDepartment, true),
    SLEEP("sleep", R.string.sleep, MetricColor.sleep, Icons.Rounded.Bedtime, false),
    WEIGHT("weight", R.string.m_weight, MetricColor.weight, Icons.Rounded.MonitorWeight, false),
    RESTING_HR("resting_heart_rate", R.string.m_resting_hr, MetricColor.heart, Icons.Rounded.MonitorHeart, false);

    /** Chart and summary unit; distance arrives in metres and is shown in kilometres. */
    fun scale(value: Double) = if (this == DISTANCE) value / 1000 else value

    fun format(value: Double): String = when (this) {
        STEPS, CALORIES -> number(value)
        DISTANCE -> number(value, 1) + " km"
        SLEEP -> hoursMinutes((value * 60).toInt())
        WEIGHT -> number(value, 1) + " kg"
        RESTING_HR -> number(value) + " bpm"
    }
}

private fun rangeOf(period: Period, offset: Int): Pair<LocalDate, LocalDate> {
    val today = LocalDate.now()
    return when (period) {
        Period.WEEK -> today.with(DayOfWeek.MONDAY).plusWeeks(offset.toLong()).let { it to it.plusDays(6) }
        Period.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
        Period.YEAR -> today.withDayOfYear(1).plusYears(offset.toLong()).let { it to it.withDayOfYear(it.lengthOfYear()) }
    }
}

private class Stat(val headline: Double?, val average: Double?, val best: Double?, val days: Int)

private fun statOf(metric: Metric, points: List<SeriesPoint>): Stat {
    val values = points.map { metric.scale(it.value) }
    if (values.isEmpty()) return Stat(null, null, null, 0)
    return when {
        metric.cumulative -> Stat(values.sum(), values.average(), values.max(), values.size)
        metric == Metric.WEIGHT -> Stat(values.last(), values.average(), values.max(), values.size)
        else -> Stat(values.average(), values.average(), values.max(), values.size)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrendsScreen(vm: AppViewModel) {
    var metricIndex by rememberSaveable { mutableIntStateOf(0) }
    var periodIndex by rememberSaveable { mutableIntStateOf(0) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val metric = Metric.entries[metricIndex]
    val period = Period.entries[periodIndex]
    val (from, to) = rangeOf(period, offset)
    val (prevFrom, prevTo) = rangeOf(period, offset - 1)

    val trends by vm.trends.collectAsState()
    val currentKey = "trend-${metric.key}-$from-$to"
    val previousKey = "trend-${metric.key}-$prevFrom-$prevTo"
    LaunchedEffect(metric, period, offset) {
        vm.loadTrend(metric.key, from, to)
        vm.loadTrend(metric.key, prevFrom, prevTo)
    }
    val current = trends[currentKey]
    val points = current?.value.orEmpty()
    val stat = statOf(metric, points)
    val previous = statOf(metric, trends[previousKey]?.value.orEmpty())
    val listState = rememberLazyListState()

    LazyColumn(Modifier, listState, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item { Text(stringResource(R.string.tab_trends), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }

        stickyHeader {
            StickyHeader(rememberStuck(listState, 2)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric.entries.forEachIndexed { i, m ->
                        FilterChip(
                            selected = i == metricIndex, onClick = { metricIndex = i },
                            leadingIcon = { Icon(m.icon, null, Modifier.size(18.dp)) },
                            label = { Text(stringResource(m.label)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Period.entries.forEachIndexed { i, p ->
                            FilterChip(
                                selected = i == periodIndex, onClick = { periodIndex = i; offset = 0 },
                                label = { Text(stringResource(when (p) { Period.WEEK -> R.string.period_week; Period.MONTH -> R.string.period_month; Period.YEAR -> R.string.period_year })) },
                            )
                        }
                    }
                    IconButton(onClick = { offset-- }) { Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.back)) }
                    Text(rangeLabel(period, from, to), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { offset++ }, enabled = offset < 0) { Icon(Icons.Rounded.ChevronRight, null) }
                }
                if (current?.refreshing == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(if (metric.cumulative) R.string.total else R.string.average).uppercase(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stat.headline?.let { metric.format(it) } ?: "–",
                        style = MaterialTheme.typography.headlineLarge, color = metric.color, fontWeight = FontWeight.Bold,
                    )
                    val delta = if (stat.headline != null && previous.headline != null && previous.headline != 0.0) (stat.headline - previous.headline) / previous.headline * 100 else null
                    if (delta != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (delta >= 0) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${if (delta >= 0) "+" else "−"}${number(abs(delta))}% ${stringResource(R.string.vs_previous)} (${previous.headline?.let { metric.format(it) }})",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (metric.cumulative && stat.average != null) {
                        Row(Modifier.padding(top = 10.dp)) {
                            StatTile(stringResource(R.string.daily_average), metric.format(stat.average), Modifier.weight(1f))
                            StatTile(stringResource(R.string.best_day), metric.format(stat.best ?: 0.0), Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    if (points.isEmpty() && current?.refreshing != true) {
                        Text(stringResource(R.string.no_data_period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (metric == Metric.WEIGHT || metric == Metric.RESTING_HR) {
                        if (points.size >= 2) {
                            LineChart(
                                points.map { LocalDate.parse(it.date).toEpochDay().toDouble() to it.value }, metric.color,
                                format = { number(it, if (metric == Metric.WEIGHT) 1 else 0) },
                                xFormat = { java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(LocalDate.ofEpochDay(it.toLong())) },
                            )
                        } else {
                            Text(stringResource(R.string.not_enough_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val buckets = buckets(metric, period, from, to, points)
                        BarChart(buckets.map { it.second }, buckets.map { it.first }, metric.color, highlight = -1)
                    }
                }
            }
        }
    }
}

private fun rangeLabel(period: Period, from: LocalDate, to: LocalDate): String {
    val locale = Locale.getDefault()
    return when (period) {
        Period.WEEK -> "${from.dayOfMonth} ${from.month.getDisplayName(TextStyle.SHORT, locale)} – ${to.dayOfMonth} ${to.month.getDisplayName(TextStyle.SHORT, locale)}"
        Period.MONTH -> "${from.month.getDisplayName(TextStyle.FULL, locale)} ${from.year}".replaceFirstChar { it.uppercase() }
        Period.YEAR -> "${from.year}"
    }
}

/** Bars for the chart: a day each for a week or month, a month each for a year. */
private fun buckets(metric: Metric, period: Period, from: LocalDate, to: LocalDate, points: List<SeriesPoint>): List<Pair<String, Double?>> {
    val byDay = points.associate { LocalDate.parse(it.date) to metric.scale(it.value) }
    val today = LocalDate.now()
    val locale = Locale.getDefault()
    return when (period) {
        Period.WEEK, Period.MONTH -> generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { day ->
            val label = when {
                period == Period.WEEK -> day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
                day.dayOfMonth % 7 == 1 -> day.dayOfMonth.toString()
                else -> ""
            }
            label to if (day.isAfter(today)) null else byDay[day]
        }.toList()
        Period.YEAR -> (1..12).map { month ->
            val values = byDay.filterKeys { it.monthValue == month }.values
            val value = when {
                values.isEmpty() -> null
                metric.cumulative -> values.sum()
                else -> values.average()
            }
            LocalDate.of(from.year, month, 1).month.getDisplayName(TextStyle.NARROW, locale) to value
        }
    }
}
