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
import kotlinx.serialization.json.jsonPrimitive
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

    private val _allWorkouts = MutableStateFlow(Loadable<List<Workout>>())
    val allWorkouts: StateFlow<Loadable<List<Workout>>> = _allWorkouts

    /** Up to 500 workouts for the records page; heavier than the 200 the list uses, so it is cached longer. */
    fun loadAllWorkouts(force: Boolean = false) = load(
        "workouts-all${maxHr()}", "workouts?limit=500${hrQuery()}", { json.decodeFromString(ListSerializer(Workout.serializer()), it) },
        _allWorkouts, force, 10 * 60_000,
    )

    private val _routes = MutableStateFlow(Loadable<List<nl.mteij.healthbridge.data.RouteTrack>>())
    val routes: StateFlow<Loadable<List<nl.mteij.healthbridge.data.RouteTrack>>> = _routes

    fun loadRoutes(force: Boolean = false) = load(
        "routes", "routes?limit=500", { json.decodeFromString(ListSerializer(nl.mteij.healthbridge.data.RouteTrack.serializer()), it) }, _routes, force, 10 * 60_000,
    )

    private val _manualRecords = MutableStateFlow<Map<Int, nl.mteij.healthbridge.data.ManualRecord>>(emptyMap())
    /** Hand entered records by distance in metres, stored on the server as `personalRecord` records. */
    val manualRecords: StateFlow<Map<Int, nl.mteij.healthbridge.data.ManualRecord>> = _manualRecords

    fun loadManualRecords() {
        if (!Graph.prefs.isLoggedIn) return
        viewModelScope.launch {
            runCatching {
                val text = Graph.api.fetchRecords("personalRecord")
                val rows = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray
                rows.mapNotNull { row ->
                    val data = (row as kotlinx.serialization.json.JsonObject)["data"] as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val distance = data["distanceM"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                    val seconds = data["seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                    val date = data["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    distance.toInt() to nl.mteij.healthbridge.data.ManualRecord(distance, seconds, date, data["note"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() })
                }.toMap()
            }.getOrNull()?.let { _manualRecords.value = it }
        }
    }

    fun saveManualRecord(record: nl.mteij.healthbridge.data.ManualRecord) {
        val id = "pr-${record.distanceM.toInt()}"
        val json = kotlinx.serialization.json.buildJsonObject {
            put("metadata", kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(id))
                put("dataOrigin", kotlinx.serialization.json.JsonPrimitive(MANUAL_SLEEP_SOURCE))
            })
            put("startTime", kotlinx.serialization.json.JsonPrimitive("${record.date}T12:00:00Z"))
            put("endTime", kotlinx.serialization.json.JsonPrimitive("${record.date}T12:00:00Z"))
            put("distanceM", kotlinx.serialization.json.JsonPrimitive(record.distanceM))
            put("seconds", kotlinx.serialization.json.JsonPrimitive(record.seconds))
            put("date", kotlinx.serialization.json.JsonPrimitive(record.date))
            record.note?.let { put("note", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        _manualRecords.value = _manualRecords.value + (record.distanceM.toInt() to record)
        viewModelScope.launch { runCatching { Graph.api.upload("personalRecord", listOf(json)) }.onFailure { loadManualRecords() } }
    }

    fun deleteManualRecord(distanceM: Int) {
        _manualRecords.value = _manualRecords.value - distanceM
        viewModelScope.launch { runCatching { Graph.api.delete("personalRecord", listOf("pr-$distanceM")) }.onFailure { loadManualRecords() } }
    }

    /** A night entered by hand is stored as a sleep session from the "healthbridge.manual" source, one per wake-up day. */
    fun saveManualSleep(start: java.time.Instant, end: java.time.Instant, onDone: (Throwable?) -> Unit) {
        val wakeDay = end.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val record = kotlinx.serialization.json.buildJsonObject {
            put("metadata", kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive("manual-$wakeDay"))
                put("dataOrigin", kotlinx.serialization.json.JsonPrimitive(MANUAL_SLEEP_SOURCE))
            })
            put("startTime", kotlinx.serialization.json.JsonPrimitive(start.toString()))
            put("endTime", kotlinx.serialization.json.JsonPrimitive(end.toString()))
            put("stages", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        viewModelScope.launch {
            val failure = runCatching { Graph.api.upload("sleepSession", listOf(record)) }.exceptionOrNull()
            if (failure == null) { loadedAt.clear(); loadSleep(force = true); loadOverview(force = true) }
            onDone(failure)
        }
    }

    fun deleteManualSleep(id: String) {
        viewModelScope.launch {
            runCatching { Graph.api.delete("sleepSession", listOf(id)) }
            loadedAt.clear(); loadSleep(force = true); loadOverview(force = true)
        }
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

    private val _refreshes = MutableStateFlow(0)
    /** Bumped by every pull-to-refresh; screens with their own loaders key on it to reload. */
    val refreshes: StateFlow<Int> = _refreshes

    fun refreshAll() {
        _refreshes.value += 1
        loadOverview(force = true)
        loadWorkouts(force = true)
        loadSleep(force = true)
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

const val MANUAL_SLEEP_SOURCE = "healthbridge.manual"
