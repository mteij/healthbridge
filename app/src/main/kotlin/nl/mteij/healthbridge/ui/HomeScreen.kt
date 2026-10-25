package nl.mteij.healthbridge.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
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
import nl.mteij.healthbridge.data.HealthMath
import nl.mteij.healthbridge.data.LogEntry
import nl.mteij.healthbridge.data.Measurement
import nl.mteij.healthbridge.data.SleepNight
import nl.mteij.healthbridge.health.HealthAccess
import nl.mteij.healthbridge.health.HealthStatus
import nl.mteij.healthbridge.health.RecordSpecs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, onWorkout: (String) -> Unit, onWorkouts: () -> Unit, onSleep: () -> Unit, onProfile: () -> Unit) {
    val context = LocalContext.current
    val overview by vm.overview.collectAsState()
    val health by vm.health.collectAsState()
    val progress by Graph.engine.progress.collectAsState()
    val log by Graph.log.entries.collectAsState()
    val prefsVersion by Graph.prefs.version.collectAsState()
    val listState = rememberLazyListState()
    var selected by remember { mutableIntStateOf(6) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshHealth()
        vm.loadOverview()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Graph.prefs.notificationsAsked) {
            Graph.prefs.notificationsAsked = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refreshHealth() }

    val power = context.getSystemService(PowerManager::class.java)
    val batteryOk = power.isIgnoringBatteryOptimizations(context.packageName)
    val dataGranted = RecordSpecs.all.any { it.readPermission in health.granted }
    val backgroundGranted = HealthAccess.backgroundPermission in health.granted && health.backgroundSupported
    val setupDone = health.status == HealthStatus.AVAILABLE && dataGranted && backgroundGranted && batteryOk

    val data = overview.value
    val steps = data?.daily?.get("steps").orEmpty()
    val distance = data?.daily?.get("distance").orEmpty()
    val kcal = data?.daily?.get("active_calories").orEmpty()
    val day = selected.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    val date = steps.getOrNull(day)?.date
    val age = prefsVersion.let { HealthMath.age(Graph.prefs.birthYear) }
    val goal = HealthMath.stepGoal(age)
    val night = data?.sleep?.firstOrNull { it.date == date }
    val dayWorkouts = data?.recentWorkouts.orEmpty().filter { localDate(it.start) == date }
    val measurements = data?.measurements.orEmpty().filter { it.value != null }.associateBy { it.metric }
    val failed = Graph.prefs.lastRunMs > Graph.prefs.lastSuccessMs &&
        log.firstOrNull { it.level == LogEntry.Level.ERROR }?.time.let { it != null && it >= Graph.prefs.lastRunMs - 1000 }

    LazyColumn(Modifier, listState, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dayTitle(date), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        syncLine(prefsVersion.let { Graph.prefs.lastSuccessMs }, progress != null),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.syncNow(); vm.refreshAll() }, enabled = progress == null) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.sync_now))
                }
            }
        }

        stickyHeader {
            StickyHeader(rememberStuck(listState, 2)) { DayStrip(steps.map { it.date }, day) { selected = it } }
        }

        if (failed && progress == null) {
            item {
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
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
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
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

        if (age == null) {
            item {
                ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), onClick = onProfile) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.profile_prompt), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
        }

        overview.error?.let { error -> if (data == null) item { Text(error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) } }

        // steps
        item {
            ElevatedSection(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing((steps.getOrNull(day)?.value ?: 0.0).toFloat() / goal, MetricColor.steps, Modifier.size(112.dp), 12.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(number(steps.getOrNull(day)?.value), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.steps), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(stringResource(R.string.goal), number(goal.toDouble()))
                            StatTile(stringResource(R.string.distance), distance.getOrNull(day)?.value?.let { kilometers(it) } ?: "–", color = MetricColor.distance)
                            StatTile(stringResource(R.string.active_calories), "${number(kcal.getOrNull(day)?.value)} kcal", color = MetricColor.calories)
                        }
                    }
                    BarChart(steps.map { it.value }, steps.map { weekdayShort(it.date) }, MetricColor.steps, highlight = day)
                }
            }
        }

        // weekly activity + sleep
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActivityCard(data?.week?.intensityMin, data?.week?.workouts ?: 0, Modifier.weight(1f))
                SleepCard(night, onSleep, Modifier.weight(1f))
            }
        }

        // body
        if (measurements.isNotEmpty()) {
            item { BodyCard(measurements, age, Modifier.padding(horizontal = 16.dp)) }
        }

        // workouts of the selected day
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workouts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onWorkouts) { Text(stringResource(R.string.see_all)) }
            }
        }
        if (data != null && dayWorkouts.isEmpty()) {
            item { Text(stringResource(R.string.no_workouts_day), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        dayWorkouts.forEach { w -> item(key = w.id) { WorkoutRow(w, Modifier.padding(horizontal = 16.dp)) { onWorkout(w.id) } } }
    }
}

@Composable
private fun DayStrip(dates: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        dates.forEachIndexed { i, date ->
            val active = i == selected
            Column(
                Modifier
                    .clickable { onSelect(i) }
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Text(weekdayShort(date), style = MaterialTheme.typography.labelSmall, color = color)
                Text(date.takeLast(2).trimStart('0'), style = MaterialTheme.typography.titleSmall, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ActivityCard(minutes: Double?, workouts: Int, modifier: Modifier) {
    ElevatedSection(modifier) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.active_minutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            ProgressRing(((minutes ?: 0.0) / HealthMath.WEEKLY_ACTIVE_MINUTES).toFloat(), MetricColor.heart, Modifier.size(88.dp), 9.dp) {
                Text(minutes?.let { number(it) } ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.of_goal_week, HealthMath.WEEKLY_ACTIVE_MINUTES), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SleepCard(night: SleepNight?, onClick: () -> Unit, modifier: Modifier) {
    ElevatedSection(modifier, onClick = onClick) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bedtime, null, tint = MetricColor.sleep, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sleep), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(night?.asleepMin?.let { hoursMinutes(it) } ?: "–", style = MaterialTheme.typography.titleLarge, color = MetricColor.sleep, fontWeight = FontWeight.Bold)
            if (night != null) {
                SleepStageStrip(night)
                val enough = (night.asleepMin ?: 0) >= HealthMath.SLEEP_TARGET_MINUTES
                Text(
                    stringResource(if (enough) R.string.sleep_target_met else R.string.sleep_target, HealthMath.SLEEP_TARGET_MINUTES / 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enough) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BodyCard(measurements: Map<String, Measurement>, age: Int?, modifier: Modifier) {
    val weight = measurements["weight"]?.value ?: Graph.prefs.manualWeightKg.toDouble().takeIf { it > 0 }
    val height = measurements["height"]?.value ?: Graph.prefs.manualHeightCm.toDouble().takeIf { it > 0 }?.div(100)
    val bmi = if (weight != null && height != null) HealthMath.bmi(weight, height) else null
    ElevatedSection(modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp)) {
            weight?.let { StatTile(stringResource(R.string.m_weight), "${number(it, 1)} kg", Modifier.weight(1f)) }
            bmi?.let {
                val label = when (HealthMath.bmiClass(it)) {
                    nl.mteij.healthbridge.data.BmiClass.UNDERWEIGHT -> R.string.bmi_under
                    nl.mteij.healthbridge.data.BmiClass.NORMAL -> R.string.bmi_normal
                    nl.mteij.healthbridge.data.BmiClass.OVERWEIGHT -> R.string.bmi_over
                    nl.mteij.healthbridge.data.BmiClass.OBESE -> R.string.bmi_obese
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("BMI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(number(it, 1), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            measurements["resting_heart_rate"]?.value?.let {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MonitorHeart, null, tint = MetricColor.heart, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.m_resting_hr), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${number(it)} bpm", style = MaterialTheme.typography.titleLarge, color = MetricColor.heart)
                }
            }
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.Check else Icons.Rounded.Warning, null, tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!done) OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun syncLine(lastSuccess: Long, running: Boolean): String = when {
    running -> stringResource(R.string.syncing)
    lastSuccess == 0L -> stringResource(R.string.not_synced_yet)
    else -> stringResource(R.string.synced_ago, relativeTime(lastSuccess))
}

private fun localDate(iso: String): String? = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.getOrNull()

private fun dayTitle(date: String?): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: LocalDate.now()
    val formatter = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault())
    return formatter.format(d).replaceFirstChar { it.uppercase() }
}
