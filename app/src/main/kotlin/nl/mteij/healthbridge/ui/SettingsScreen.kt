package nl.mteij.healthbridge.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.BuildConfig
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.health.RecordSpecs

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val prefs = Graph.prefs
    val version by prefs.version.collectAsState()
    // reading `version` makes this recompose whenever a preference changes
    val interval = prefs.syncIntervalMinutes.also { version }
    val backfill = prefs.backfillDays
    val unmetered = prefs.unmeteredOnly
    val disabled = prefs.disabledTypes

    Column(
        modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))

        Text(stringResource(R.string.account), style = MaterialTheme.typography.titleMedium)
        Text("${prefs.username} @ ${prefs.serverUrl}", style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()
        Text(stringResource(R.string.sync_interval), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sync_interval_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h", 360 to "6 h").forEach { (minutes, label) ->
                FilterChip(
                    selected = interval == minutes,
                    onClick = { prefs.syncIntervalMinutes = minutes; vm.reschedule() },
                    label = { Text(label) },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.unmetered_only), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.unmetered_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = unmetered, onCheckedChange = { prefs.unmeteredOnly = it; vm.reschedule() })
        }

        HorizontalDivider()
        Text(stringResource(R.string.backfill), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.backfill_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 90, 180, 365).forEach { days ->
                FilterChip(selected = backfill == days, onClick = { prefs.backfillDays = days }, label = { Text("$days") })
            }
        }
        OutlinedButton(onClick = { vm.syncNow(full = true) }) { Text(stringResource(R.string.full_resync)) }

        HorizontalDivider()
        Text(stringResource(R.string.data_types), style = MaterialTheme.typography.titleMedium)
        RecordSpecs.all.forEach { spec ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(spec.label, Modifier.weight(1f))
                Switch(
                    checked = spec.key !in disabled,
                    onCheckedChange = { on -> prefs.disabledTypes = if (on) disabled - spec.key else disabled + spec.key },
                )
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = { vm.logout() }) { Text(stringResource(R.string.sign_out)) }
        Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        Text("", Modifier.padding(16.dp))
    }
}
