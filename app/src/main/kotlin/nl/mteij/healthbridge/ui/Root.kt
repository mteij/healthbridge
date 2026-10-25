package nl.mteij.healthbridge.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val Tabs = listOf(
    Tab("home", R.string.tab_home, Icons.Filled.Home),
    Tab("workouts", R.string.tab_workouts, Icons.AutoMirrored.Filled.List),
    Tab("settings", R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
fun Root(vm: AppViewModel = viewModel()) {
    val version by Graph.prefs.version.collectAsState()
    if (version < 0 || !Graph.prefs.isLoggedIn) {
        LoginScreen(vm)
        return
    }

    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        bottomBar = {
            if (route in Tabs.map { it.route }) {
                NavigationBar {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        NavHost(nav, startDestination = "home", modifier = modifier) {
            composable("home") {
                HomeScreen(vm, onWorkout = { nav.navigate("workout/$it") }, onWorkouts = { nav.navigate("workouts") }, onSleep = { nav.navigate("sleep") })
            }
            composable("workouts") { WorkoutsScreen(vm, onWorkout = { nav.navigate("workout/$it") }) }
            composable("settings") { SettingsScreen(vm, onLog = { nav.navigate("log") }) }
            composable("workout/{id}") { entry ->
                WorkoutDetailScreen(vm, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("sleep") { SleepScreen(vm, onBack = { nav.popBackStack() }) }
            composable("log") { ActivityScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
