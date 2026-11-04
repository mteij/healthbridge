package nl.mteij.healthbridge.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import kotlinx.coroutines.launch
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.health.HealthAccess
import nl.mteij.healthbridge.health.RecordSpecs

/**
 * Health Connect hands out a workout's GPS route only after the user allows it for that workout.
 * This walks a list of workouts, shows the system consent screen for each one, and uploads the
 * workout again with its route. Declining or leaving a screen stops the walk.
 */
class RouteConsent internal constructor(
    val remaining: Int,
    val start: (List<String>) -> Unit,
)

@Composable
fun rememberRouteConsent(onUploaded: () -> Unit): RouteConsent {
    val context: Context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf(emptyList<String>()) }
    lateinit var launcher: androidx.activity.result.ActivityResultLauncher<String>

    fun next(rest: List<String>) {
        queue = rest
        if (rest.isNotEmpty()) launcher.launch(rest.first())
        else onUploaded()
    }

    launcher = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        val current = queue.firstOrNull() ?: return@rememberLauncherForActivityResult
        if (route == null) {
            queue = emptyList()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            HealthAccess.session(context, current)?.let { session ->
                runCatching { Graph.api.upload("exerciseSession", listOf(RecordSpecs.encodeSessionWithRoute(session, route))) }
            }
            next(queue.drop(1))
        }
    }
    return RouteConsent(queue.size) { ids -> if (ids.isNotEmpty()) next(ids) }
}
