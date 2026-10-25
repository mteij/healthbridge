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
import nl.mteij.healthbridge.data.DayTotal
import nl.mteij.healthbridge.data.HealthMath
import nl.mteij.healthbridge.data.OverviewData
import nl.mteij.healthbridge.data.SeriesPoint
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

    private val _trends = MutableStateFlow<Map<String, Loadable<List<SeriesPoint>>>>(emptyMap())
    val trends: StateFlow<Map<String, Loadable<List<SeriesPoint>>>> = _trends

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
        onLoaded: (T) -> Unit = {},
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
            }.onSuccess {
                state.value = Loadable(it)
                onLoaded(it)
            }
                .onFailure { state.value = state.value.copy(refreshing = false, error = it.message ?: it.javaClass.simpleName) }
        }
    }

    /** Heart rate maximum used for zones: from the profile's age, raised by what workouts show. */
    fun maxHr(): Int? = HealthMath.maxHr(HealthMath.age(Graph.prefs.birthYear), Graph.prefs.observedMaxHr.takeIf { it > 0 })

    private fun hrQuery() = maxHr()?.let { "&max_hr=$it" }.orEmpty()

    fun loadOverview(force: Boolean = false) = load(
        "overview${maxHr()}", "overview?tz=$tz${hrQuery()}", { json.decodeFromString(OverviewData.serializer(), it) }, _overview, force,
    )

    fun loadWorkouts(force: Boolean = false) = load(
        "workouts${maxHr()}", "workouts?limit=200${hrQuery()}", { json.decodeFromString(ListSerializer(Workout.serializer()), it) }, _workouts, force, 120_000,
        onLoaded = { list ->
            HealthMath.observedMaxHr(list.mapNotNull { it.heartRate?.max })?.let { Graph.prefs.observedMaxHr = it }
        },
    )

    /** Called after the profile changes: zones depend on the maximum heart rate. */
    fun profileChanged() {
        loadedAt.clear()
        _overview.value = Loadable()
        _workouts.value = Loadable()
        _details.value = emptyMap()
        loadOverview(force = true)
        loadWorkouts(force = true)
    }

    /** One metric over a date range, keyed so the screen can show this period and the previous one. */
    fun loadTrend(metric: String, from: LocalDate, to: LocalDate, force: Boolean = false): String {
        val key = "trend-$metric-$from-$to"
        val body = metric in setOf("weight", "resting_heart_rate", "vo2_max", "body_fat")
        val path = when {
            metric == "sleep" -> "sleep?from=$from&to=$to&tz=$tz"
            body -> "series?name=$metric&from=$from&to=$to&tz=$tz"
            else -> "daily?metric=$metric&from=$from&to=$to&tz=$tz"
        }
        val state = MutableStateFlow(_trends.value[key] ?: Loadable())
        if (key !in loadedAt) {
            viewModelScope.launch { state.collect { value -> _trends.value = _trends.value + (key to value) } }
        }
        load(
            key, path,
            { text ->
                when {
                    metric == "sleep" -> json.decodeFromString(ListSerializer(SleepNight.serializer()), text)
                        .mapNotNull { n -> n.asleepMin?.let { SeriesPoint(n.date, it / 60.0, "h") } }
                    body -> json.decodeFromString(ListSerializer(SeriesPoint.serializer()), text)
                    else -> json.decodeFromString(ListSerializer(DayTotal.serializer()), text)
                        .mapNotNull { d -> d.value?.let { SeriesPoint(d.date, it, d.unit) } }
                }
            },
            state, force, if (to < LocalDate.now()) 30 * 60_000 else 60_000,
        )
        return key
    }

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
        load("workout-$id-${maxHr()}", "workouts/$id?points=240${hrQuery()}", { json.decodeFromString(Workout.serializer(), it) }, state, force, 5 * 60_000)
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
