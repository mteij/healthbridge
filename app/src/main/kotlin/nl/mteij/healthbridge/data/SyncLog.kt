package nl.mteij.healthbridge.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class LogEntry(
    val time: Long,
    val level: Level,
    val message: String,
    val type: String? = null,
) {
    enum class Level { INFO, WARN, ERROR }
}

/**
 * Persistent, bounded log of what the sync did. Failures are shown to the user instead of
 * being swallowed, which is the main thing the original app got wrong.
 */
class SyncLog(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("sync_log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LogEntry.serializer())

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LogEntry>> = _entries

    private fun load(): List<LogEntry> = runCatching {
        json.decodeFromString(serializer, sp.getString("entries", "[]")!!)
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(level: LogEntry.Level, message: String, type: String? = null) {
        val next = (listOf(LogEntry(System.currentTimeMillis(), level, message, type)) + _entries.value).take(MAX)
        _entries.value = next
        sp.edit().putString("entries", json.encodeToString(serializer, next)).apply()
    }

    fun info(message: String, type: String? = null) = add(LogEntry.Level.INFO, message, type)
    fun warn(message: String, type: String? = null) = add(LogEntry.Level.WARN, message, type)
    fun error(message: String, type: String? = null) = add(LogEntry.Level.ERROR, message, type)

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        sp.edit().remove("entries").apply()
    }

    private companion object {
        const val MAX = 200
    }
}
