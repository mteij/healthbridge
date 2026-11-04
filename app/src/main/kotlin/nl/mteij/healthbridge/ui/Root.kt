package nl.mteij.healthbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R

@Composable
fun Root(vm: AppViewModel = viewModel()) {
    val version by Graph.prefs.version.collectAsState()
    if (version < 0 || !Graph.prefs.isLoggedIn) {
        LoginScreen(vm)
        return
    }

    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val tabs = listOf(
        NavTab("home", stringResource(R.string.tab_home), Icons.Rounded.Home),
        NavTab("workouts", stringResource(R.string.tab_workouts), Icons.Rounded.DirectionsRun),
        NavTab("trends", stringResource(R.string.tab_trends), Icons.Rounded.ShowChart),
        NavTab("settings", stringResource(R.string.tab_settings), Icons.Rounded.Settings),
    )
    val progress by Graph.engine.progress.collectAsState()

    var bannerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(progress == null) { if (progress == null) bannerDismissed = false }

    // Text outside a Surface would fall back to black; give the whole app the theme's content colour
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(nav, startDestination = "home") {
            composable("home") {
                PullToRefresh(vm) {
                    HomeScreen(
                        vm, onWorkout = { nav.navigate("workout/$it") }, onWorkouts = { nav.navigate("workouts") },
                        onSleep = { nav.navigate("sleep") }, onProfile = { nav.navigate("profile") },
                    )
                }
            }
            composable("workouts") { PullToRefresh(vm) { WorkoutsScreen(vm, onWorkout = { nav.navigate("workout/$it") }) } }
            composable("trends") { PullToRefresh(vm) { TrendsScreen(vm) } }
            composable("settings") {
                SettingsScreen(vm, onLog = { nav.navigate("log") }, onProfile = { nav.navigate("profile") }, onDataTypes = { nav.navigate("datatypes") })
            }
            composable("datatypes") { DataTypesScreen(onBack = { nav.popBackStack() }) }
            composable("workout/{id}") { entry ->
                WorkoutDetailScreen(vm, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onProfile = { nav.navigate("profile") })
            }
            composable("sleep") { SleepScreen(vm, onBack = { nav.popBackStack() }) }
            composable("profile") { ProfileScreen(vm, onBack = { nav.popBackStack() }) }
            composable("log") { ActivityScreen(onBack = { nav.popBackStack() }) }
        }

        // import / sync progress, visible on every screen
        AnimatedVisibility(
            progress != null && !bannerDismissed, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it }, exit = slideOutVertically { -it },
        ) {
            val p = progress
            Surface(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                    // swipe the banner away; it comes back with the next sync
                    .pointerInput(Unit) { detectDragGestures { _, drag -> if (drag.y < -8f || kotlin.math.abs(drag.x) > 12f) bannerDismissed = true } },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 8.dp,
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    val line = when {
                        p == null || p.total == 0 -> stringResource(R.string.syncing)
                        else -> "${stringResource(R.string.syncing)} · ${p.label} (${p.index + 1}/${p.total})" +
                            if (p.records > 0) " · ${number(p.records.toDouble())}" else ""
                    }
                    Text(line, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
                    val fraction = p?.fraction
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                IconButton(onClick = { bannerDismissed = true }, Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.dismiss), tint = MaterialTheme.colorScheme.inverseOnSurface)
                }
                }
            }
        }

        AnimatedVisibility(
            route in tabs.map { it.route }, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            FloatingNavBar(
                tabs, route,
                onSelect = { target ->
                    nav.navigate(target) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
    }
}
