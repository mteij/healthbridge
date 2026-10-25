package nl.mteij.healthbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.data.Workout
import nl.mteij.healthbridge.health.HealthAccess
import nl.mteij.healthbridge.health.HealthStatus
import nl.mteij.healthbridge.sync.SyncScheduler

data class HealthState(
    val status: HealthStatus = HealthStatus.AVAILABLE,
    val granted: Set<String> = emptySet(),
    val backgroundSupported: Boolean = true,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _workouts = MutableStateFlow<List<Workout>?>(null)
    val workouts: StateFlow<List<Workout>?> = _workouts

    private val _workoutsError = MutableStateFlow<String?>(null)
    val workoutsError: StateFlow<String?> = _workoutsError

    private val _health = MutableStateFlow(HealthState())
    val health: StateFlow<HealthState> = _health

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError

    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn

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

    fun loadWorkouts() {
        if (!Graph.prefs.isLoggedIn) return
        viewModelScope.launch {
            runCatching { Graph.api.workouts() }
                .onSuccess {
                    _workouts.value = it
                    _workoutsError.value = null
                }
                .onFailure { _workoutsError.value = it.message ?: it.javaClass.simpleName }
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
        _workouts.value = null
    }

    fun syncNow(full: Boolean = false) = SyncScheduler.syncNow(getApplication(), Graph.prefs, full)

    fun reschedule() = SyncScheduler.schedule(getApplication(), Graph.prefs)
}
