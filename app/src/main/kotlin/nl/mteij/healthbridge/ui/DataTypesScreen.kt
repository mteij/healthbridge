package nl.mteij.healthbridge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.health.RecordSpecs

/** Which kinds of data are synced, grouped by category, with one switch per group and per type. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DataTypesScreen(onBack: () -> Unit) {
    val prefs = Graph.prefs
    val version by prefs.version.collectAsState()
    val disabled = prefs.disabledTypes.also { version }

    val groups = listOf(
        Triple("activity", R.string.group_activity, Icons.Rounded.DirectionsRun to MetricColor.steps),
        Triple("heart", R.string.group_heart, Icons.Rounded.MonitorHeart to MetricColor.heart),
        Triple("sleep", R.string.group_sleep, Icons.Rounded.Bedtime to MetricColor.sleep),
        Triple("body", R.string.group_body, Icons.Rounded.MonitorWeight to MetricColor.weight),
        Triple("vitals", R.string.group_vitals, Icons.Rounded.Bloodtype to MetricColor.calories),
    )

    LazyColumn(Modifier, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        stickyHeader {
            StickyHeader(true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.data_sources), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        groups.forEach { (key, title, iconAndColor) ->
            val specs = RecordSpecs.all.filter { it.group == key }
            if (specs.isEmpty()) return@forEach
            item(key = key) {
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(iconAndColor.first, iconAndColor.second, size = 40.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(
                                checked = specs.any { it.key !in disabled },
                                onCheckedChange = { on -> prefs.disabledTypes = if (on) disabled - specs.map { it.key }.toSet() else disabled + specs.map { it.key } },
                            )
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        specs.forEach { spec ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(spec.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = spec.key !in disabled,
                                    onCheckedChange = { on -> prefs.disabledTypes = if (on) disabled - spec.key else disabled + spec.key },
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
    }
}
