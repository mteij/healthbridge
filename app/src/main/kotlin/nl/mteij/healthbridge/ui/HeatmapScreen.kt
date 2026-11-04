package nl.mteij.healthbridge.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.R

/** Every GPS track of the user drawn on one map, so the roads used most glow. Strava's global heatmap is their data and not available here. */
@Composable
fun HeatmapScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.routes.collectAsState()
    LaunchedEffect(Unit) { vm.loadRoutes() }
    var style by remember { mutableStateOf(MapStyle.saved()) }
    var filter by remember { mutableStateOf<String?>(null) }
    val tracks = state.value.orEmpty()
    val types = tracks.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }
    val shown = tracks.filter { filter == null || it.type == filter }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.heatmap), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.routes_count, shown.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.all)) })
            types.forEach { (type, count) ->
                FilterChip(selected = filter == type, onClick = { filter = if (filter == type) null else type }, label = { Text("${workoutLabel(type)} · $count") })
            }
        }
        MapStylePicker(style, { style = it })
        if (shown.isEmpty() && state.value != null) {
            Text(stringResource(R.string.no_routes), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TileMap(
                shown.map { track -> track.points.map { it[0] to it[1] } }, style,
                height = screenHeight - 300.dp, interactive = true, heat = true,
            )
        }
    }
}
