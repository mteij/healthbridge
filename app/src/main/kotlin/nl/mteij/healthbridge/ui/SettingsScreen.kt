package nl.mteij.healthbridge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.BuildConfig
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onLog: () -> Unit, onProfile: () -> Unit, onDataTypes: () -> Unit) {
    val prefs = Graph.prefs
    val version by prefs.version.collectAsState()
    val interval = prefs.syncIntervalMinutes.also { version }
    val backfill = prefs.backfillDays
    val unmetered = prefs.unmeteredOnly

    LazyColumn(Modifier, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("${prefs.username} · ${prefs.serverUrl.removePrefix("https://")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column {
                    SettingsRow(Icons.Rounded.Person, stringResource(R.string.profile), onClick = onProfile)
                    SettingsRow(Icons.Rounded.Tune, stringResource(R.string.data_sources), onClick = onDataTypes)
                    SettingsRow(Icons.Rounded.Article, stringResource(R.string.sync_log), onClick = onLog)
                }
            }
        }

        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sync, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.sync), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(stringResource(R.string.sync_interval), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h", 360 to "6 h").forEach { (minutes, label) ->
                            FilterChip(selected = interval == minutes, onClick = { prefs.syncIntervalMinutes = minutes; vm.reschedule() }, label = { Text(label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.unmetered_only), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = unmetered, onCheckedChange = { prefs.unmeteredOnly = it; vm.reschedule() })
                    }
                    Text(stringResource(R.string.backfill), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 90, 180, 365).forEach { days ->
                            FilterChip(selected = backfill == days, onClick = { prefs.backfillDays = days }, label = { Text("$days") })
                        }
                    }
                    OutlinedButton(onClick = { vm.syncNow(full = true) }) { Text(stringResource(R.string.full_resync)) }
                }
            }
        }

        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                SettingsRow(Icons.Rounded.Logout, stringResource(R.string.sign_out), chevron = false, onClick = { vm.logout() })
            }
        }
        item {
            Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, chevron: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
