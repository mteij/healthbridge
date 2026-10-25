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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.DayTotal
import nl.mteij.healthbridge.data.LogEntry
import nl.mteij.healthbridge.health.HealthAccess
import nl.mteij.healthbridge.health.HealthStatus
import nl.mteij.healthbridge.health.RecordSpecs

@Composable
fun HomeScreen(vm: AppViewModel, onWorkout: (String) -> Unit, onWorkouts: () -> Unit, onSleep: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val overview by vm.overview.collectAsState()
    val health by vm.health.collectAsState()
    val running by Graph.engine.status.collectAsState()
    val log by Graph.log.entries.collectAsState()
    val prefsVersion by Graph.prefs.version.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshHealth()
        vm.loadOverview()
    }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refreshHealth() }

    val power = context.getSystemService(PowerManager::class.java)
    val batteryOk = power.isIgnoringBatteryOptimizations(context.packageName)
    val dataGranted = RecordSpecs.all.any { it.readPermission in health.granted }
    val backgroundGranted = HealthAccess.backgroundPermission in health.granted && health.backgroundSupported
    val setupDone = health.status == HealthStatus.AVAILABLE && dataGranted && backgroundGranted && batteryOk
    val data = overview.value
    val steps = data?.daily?.get("steps").orEmpty()
    val today = steps.lastOrNull()
    val goal = prefsVersion.let { Graph.prefs.stepGoal }

    LazyColumn(
        modifier.statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(todayTitle(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        lastSyncText(prefsVersion.let { Graph.prefs.lastSuccessMs }, running),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.syncNow(); vm.refreshAll() }, enabled = running == null) { Icon(Icons.Filled.Refresh, stringResource(R.string.sync_now)) }
            }
            if (running != null || overview.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        val failed = Graph.prefs.lastRunMs > Graph.prefs.lastSuccessMs && log.firstOrNull { it.level == LogEntry.Level.ERROR }?.time.let { it != null && it >= Graph.prefs.lastRunMs - 1000 }
        if (failed && running == null) {
            item {
                ElevatedSection(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.sync_failed), style = MaterialTheme.typography.titleSmall)
                            Text(log.first { it.level == LogEntry.Level.ERROR }.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (!setupDone) {
            item {
                ElevatedSection(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
                        SetupRow(health.status == HealthStatus.AVAILABLE, stringResource(R.string.setup_hc_title), stringResource(R.string.setup_hc_action)) {
                            runCatching { context.startActivity(HealthAccess.installIntent()) }
                        }
                        SetupRow(dataGranted, stringResource(R.string.setup_perm_title), stringResource(R.string.setup_perm_action), health.status == HealthStatus.AVAILABLE) {
                            permissionLauncher.launch(RecordSpecs.permissions)
                        }
                        SetupRow(
                            backgroundGranted,
                            stringResource(if (health.backgroundSupported) R.string.setup_bg_title else R.string.setup_bg_unsupported),
                            stringResource(R.string.setup_bg_action),
                            health.status == HealthStatus.AVAILABLE && health.backgroundSupported,
                        ) { permissionLauncher.launch(setOf(HealthAccess.backgroundPermission, HealthAccess.historyPermission)) }
                        SetupRow(batteryOk, stringResource(R.string.setup_battery_title), stringResource(R.string.setup_battery_action)) {
                            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                            runCatching { context.startActivity(request) }.onFailure {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        }
                    }
                }
            }
        }

        overview.error?.let { error -> if (data == null) item { Text(error, color = MaterialTheme.colorScheme.error) } }

        // --- steps hero
        item {
            ElevatedSection(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing((today?.value ?: 0.0).toFloat() / goal, MetricColor.steps, Modifier.size(116.dp), 12.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(number(today?.value), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.steps), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(stringResource(R.string.goal), number(goal.toDouble()))
                            val distance = data?.daily?.get("distance")?.lastOrNull()?.value
                            StatTile(stringResource(R.string.distance), distance?.let { kilometers(it) } ?: "–", color = MetricColor.distance)
                        }
                    }
                    BarChart(
                        values = steps.map { it.value }, labels = steps.map { weekdayShort(it.date) }, color = MetricColor.steps,
                    )
                }
            }
        }

        // --- calories + sleep
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val kcal = data?.daily?.get("active_calories").orEmpty()
                CaloriesCard(kcal, Modifier.weight(1f))
                SleepCard(data?.sleep?.lastOrNull(), onSleep, Modifier.weight(1f))
            }
        }

        // --- body measurements
        val measurements = data?.measurements.orEmpty().filter { it.value != null }
        if (measurements.isNotEmpty()) {
            item {
                ElevatedSection(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.body), style = MaterialTheme.typography.titleMedium)
                        measurements.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { m ->
                                    StatTile(measurementLabel(m.metric), "${number(m.value, if (m.metric == "weight" || m.metric == "vo2_max") 1 else 0)} ${m.unit}", Modifier.weight(1f),
                                        color = if (m.metric == "resting_heart_rate") MetricColor.heart else MaterialTheme.colorScheme.onSurface)
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }

        // --- recent workouts
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent_workouts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onWorkouts) { Text(stringResource(R.string.see_all)) }
            }
        }
        val recent = data?.recentWorkouts.orEmpty().take(3)
        if (data != null && recent.isEmpty()) item { Text(stringResource(R.string.no_workouts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        recent.forEach { w -> item(key = w.id) { WorkoutRow(w) { onWorkout(w.id) } } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun CaloriesCard(days: List<DayTotal>, modifier: Modifier) {
    ElevatedSection(modifier) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.active_calories), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${number(days.lastOrNull()?.value)} kcal", style = MaterialTheme.typography.titleLarge, color = MetricColor.calories, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            BarChart(days.map { it.value }, days.map { "" }, MetricColor.calories, height = 48.dp)
        }
    }
}

@Composable
private fun SleepCard(night: nl.mteij.healthbridge.data.SleepNight?, onClick: () -> Unit, modifier: Modifier) {
    ElevatedSection(modifier, onClick = onClick) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.sleep), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(night?.asleepMin?.let { hoursMinutes(it) } ?: "–", style = MaterialTheme.typography.titleLarge, color = MetricColor.sleep, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (night != null) SleepStageStrip(night)
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Filled.Check else Icons.Filled.Warning, null, tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!done) OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun lastSyncText(lastSuccess: Long, running: String?): String = when {
    running != null -> stringResource(R.string.syncing) + " " + running
    lastSuccess == 0L -> stringResource(R.string.not_synced_yet)
    else -> stringResource(R.string.synced_ago, relativeTime(lastSuccess))
}

@Composable
private fun measurementLabel(metric: String): String = when (metric) {
    "weight" -> stringResource(R.string.m_weight)
    "body_fat" -> stringResource(R.string.m_body_fat)
    "resting_heart_rate" -> stringResource(R.string.m_resting_hr)
    "vo2_max" -> stringResource(R.string.m_vo2)
    "hrv_rmssd" -> stringResource(R.string.m_hrv)
    "oxygen_saturation" -> stringResource(R.string.m_spo2)
    "height" -> stringResource(R.string.m_height)
    else -> metric
}

private fun todayTitle(): String =
    java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault()).format(java.time.LocalDate.now())
        .replaceFirstChar { it.uppercase() }
