package nl.mteij.healthbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.data.OverviewData
import nl.mteij.healthbridge.data.SleepNight
import nl.mteij.healthbridge.data.Workout
import nl.mteij.healthbridge.health.HealthAccess
import nl.mteij.healthbridge.health.HealthStatus
import nl.mteij.healthbridge.sync.SyncScheduler
import java.time.LocalDate
import java.util.TimeZone

data class HealthState(
    val status: HealthStatus = HealthStatus.AVAILABLE,
    val granted: Set<String> = emptySet(),
    val backgroundSupported: Boolean = true,
)

/** A value that is shown from the disk cache first and replaced when the server answers. */
data class Loadable<T>(val value: T? = null, val refreshing: Boolean = false, val error: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val tz: String get() = TimeZone.getDefault().id

    private val _overview = MutableStateFlow(Loadable<OverviewData>())
    val overview: StateFlow<Loadable<OverviewData>> = _overview

    private val _workouts = MutableStateFlow(Loadable<List<Workout>>())
    val workouts: StateFlow<Loadable<List<Workout>>> = _workouts

    private val _details = MutableStateFlow<Map<String, Loadable<Workout>>>(emptyMap())
    val details: StateFlow<Map<String, Loadable<Workout>>> = _details

    private val _sleep = MutableStateFlow(Loadable<List<SleepNight>>())
    val sleep: StateFlow<Loadable<List<SleepNight>>> = _sleep

    private val _health = MutableStateFlow(HealthState())
    val health: StateFlow<HealthState> = _health

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError

    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn

    private val loadedAt = mutableMapOf<String, Long>()

    private val json get() = Graph.api.format

    /**
     * Shows the cached copy immediately, then refreshes it from the server unless it is fresher
     * than [maxAgeMs]. Failures keep the cached data on screen and only record the error.
     */
    private fun <T> load(
        key: String,
        path: String,
        decode: (String) -> T,
        state: MutableStateFlow<Loadable<T>>,
        force: Boolean,
        maxAgeMs: Long = 60_000,
    ) {
        if (!Graph.prefs.isLoggedIn) return
        viewModelScope.launch {
            if (state.value.value == null) {
                withContext(Dispatchers.IO) { Graph.cache.read(key) }?.let { text ->
                    runCatching { withContext(Dispatchers.Default) { decode(text) } }.getOrNull()?.let { state.value = Loadable(it) }
                }
            }
            val now = System.currentTimeMillis()
            if (!force && now - (loadedAt[key] ?: 0) < maxAgeMs) return@launch
            loadedAt[key] = now
            state.value = state.value.copy(refreshing = true, error = null)
            runCatching {
                val text = Graph.api.getText(path)
                val value = withContext(Dispatchers.Default) { decode(text) }
                withContext(Dispatchers.IO) { Graph.cache.write(key, text) }
                value
            }.onSuccess { state.value = Loadable(it) }
                .onFailure { state.value = state.value.copy(refreshing = false, error = it.message ?: it.javaClass.simpleName) }
        }
    }

    fun loadOverview(force: Boolean = false) = load(
        "overview", "overview?tz=$tz", { json.decodeFromString(OverviewData.serializer(), it) }, _overview, force,
    )

    fun loadWorkouts(force: Boolean = false) = load(
        "workouts", "workouts?limit=200", { json.decodeFromString(ListSerializer(Workout.serializer()), it) }, _workouts, force, 120_000,
    )

    fun loadSleep(force: Boolean = false) {
        val to = LocalDate.now()
        load(
            "sleep", "sleep?from=${to.minusDays(13)}&to=$to&tz=$tz",
            { json.decodeFromString(ListSerializer(SleepNight.serializer()), it) }, _sleep, force,
        )
    }

    fun loadWorkout(id: String, force: Boolean = false) {
        val state = MutableStateFlow(_details.value[id] ?: Loadable())
        // reuse the generic loader, then mirror the result into the map
        viewModelScope.launch {
            state.collect { value -> _details.value = _details.value + (id to value) }
        }
        load("workout-$id", "workouts/$id?points=240", { json.decodeFromString(Workout.serializer(), it) }, state, force, 5 * 60_000)
    }

    fun refreshAll() {
        loadOverview(force = true)
        loadWorkouts(force = true)
    }

    fun refreshHealth() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val status = HealthAccess.status(context)
            val granted = if (status == HealthStatus.AVAILABLE) {
                runCatching { HealthAccess.granted(context) }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            val background = status == HealthStatus.AVAILABLE && HealthAccess.backgroundReadSupported(context)
            _health.value = HealthState(status, granted, background)
        }
    }

    fun login(server: String, username: String, password: String) {
        viewModelScope.launch {
            _loggingIn.value = true
            _loginError.value = null
            runCatching { Graph.api.login(server, username, password) }
                .onSuccess { SyncScheduler.schedule(getApplication(), Graph.prefs) }
                .onFailure { _loginError.value = it.message ?: it.javaClass.simpleName }
            _loggingIn.value = false
        }
    }

    fun logout() {
        SyncScheduler.cancel(getApplication())
        Graph.prefs.logout()
        Graph.cache.clear()
        _overview.value = Loadable()
        _workouts.value = Loadable()
        _sleep.value = Loadable()
        _details.value = emptyMap()
        loadedAt.clear()
    }

    fun syncNow(full: Boolean = false) = SyncScheduler.syncNow(getApplication(), Graph.prefs, full)

    fun reschedule() = SyncScheduler.schedule(getApplication(), Graph.prefs)
}
