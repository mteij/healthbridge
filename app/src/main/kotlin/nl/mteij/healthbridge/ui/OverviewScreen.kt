package nl.mteij.healthbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.LogEntry
import nl.mteij.healthbridge.data.Workout
import nl.mteij.healthbridge.health.HealthAccess
import nl.mteij.healthbridge.health.HealthStatus
import nl.mteij.healthbridge.health.RecordSpecs

@Composable
fun OverviewScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val health by vm.health.collectAsState()
    val workouts by vm.workouts.collectAsState()
    val workoutsError by vm.workoutsError.collectAsState()
    val running by Graph.engine.status.collectAsState()
    val log by Graph.log.entries.collectAsState()
    val prefsVersion by Graph.prefs.version.collectAsState()
    var resumes by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumes++
        vm.refreshHealth()
    }
    LaunchedEffect(resumes, running == null) { vm.loadWorkouts() }

    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.refreshHealth()
    }

    val power = context.getSystemService(PowerManager::class.java)
    val batteryOk = power.isIgnoringBatteryOptimizations(context.packageName)
    val dataGranted = RecordSpecs.all.any { it.readPermission in health.granted }
    val backgroundGranted = HealthAccess.backgroundPermission in health.granted && health.backgroundSupported
    val setupDone = health.status == HealthStatus.AVAILABLE && dataGranted && backgroundGranted && batteryOk
    val lastError = log.firstOrNull { it.level == LogEntry.Level.ERROR }

    LazyColumn(
        modifier.statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }

        item {
            StatusCard(
                running = running,
                lastSuccess = prefsVersion.let { Graph.prefs.lastSuccessMs },
                lastRun = Graph.prefs.lastRunMs,
                lastError = lastError,
                onSync = { vm.syncNow() },
            )
        }

        if (!setupDone) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
                        SetupRow(
                            done = health.status == HealthStatus.AVAILABLE,
                            title = stringResource(R.string.setup_hc_title),
                            action = stringResource(R.string.setup_hc_action),
                        ) { runCatching { context.startActivity(HealthAccess.installIntent()) } }
                        SetupRow(
                            done = dataGranted,
                            title = stringResource(R.string.setup_perm_title),
                            action = stringResource(R.string.setup_perm_action),
                            enabled = health.status == HealthStatus.AVAILABLE,
                        ) { permissionLauncher.launch(RecordSpecs.permissions) }
                        SetupRow(
                            done = backgroundGranted,
                            title = stringResource(if (health.backgroundSupported) R.string.setup_bg_title else R.string.setup_bg_unsupported),
                            action = stringResource(R.string.setup_bg_action),
                            enabled = health.status == HealthStatus.AVAILABLE && health.backgroundSupported,
                        ) { permissionLauncher.launch(setOf(HealthAccess.backgroundPermission, HealthAccess.historyPermission)) }
                        SetupRow(
                            done = batteryOk,
                            title = stringResource(R.string.setup_battery_title),
                            action = stringResource(R.string.setup_battery_action),
                        ) {
                            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                            runCatching { context.startActivity(request) }.onFailure {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent_workouts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.loadWorkouts(force = true) }) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.refresh)) }
            }
        }
        workoutsError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        val list = workouts
        if (list != null && list.isEmpty()) {
            item { Text(stringResource(R.string.no_workouts), style = MaterialTheme.typography.bodyMedium) }
        }
        items(list.orEmpty(), key = { it.id }) { WorkoutCard(it) }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}

@Composable
private fun StatusCard(running: String?, lastSuccess: Long, lastRun: Long, lastError: LogEntry?, onSync: () -> Unit) {
    val failed = lastRun > lastSuccess && lastError != null && lastError.time >= lastRun - 1000
    val container = when {
        running != null -> MaterialTheme.colorScheme.secondaryContainer
        failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (failed) Icons.Filled.Warning else Icons.Filled.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        running != null -> stringResource(R.string.syncing)
                        failed -> stringResource(R.string.sync_failed)
                        lastSuccess == 0L -> stringResource(R.string.not_synced_yet)
                        else -> stringResource(R.string.synced_ago, relativeTime(lastSuccess))
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (running != null) {
                Text(running, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (failed && lastError != null) {
                Text(lastError.message, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onSync, enabled = running == null) { Text(stringResource(R.string.sync_now)) }
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Filled.Check else Icons.Filled.Warning, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!done) OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun WorkoutCard(w: Workout) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(workoutEmoji(w.type), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.title ?: workoutLabel(w.type), style = MaterialTheme.typography.titleMedium)
                    Text(isoToDateTime(w.start), style = MaterialTheme.typography.bodySmall)
                }
            }
            val stats = buildList {
                add(duration(w.durationS))
                w.distanceM?.let { add(kilometers(it)) }
                w.paceSPerKm?.let { add(pace(it)) }
                w.heartRate?.let { add("♥ ${it.avg} (${it.min}–${it.max})") }
                (w.activeKcal ?: w.totalKcal)?.let { add("${it.toInt()} kcal") }
            }
            Text(stats.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
