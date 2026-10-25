package nl.mteij.healthbridge.sync

import android.content.Context
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.mteij.healthbridge.data.Api
import nl.mteij.healthbridge.data.ApiException
import nl.mteij.healthbridge.data.Prefs
import nl.mteij.healthbridge.data.SyncLog
import nl.mteij.healthbridge.health.HealthAccess
import nl.mteij.healthbridge.health.HealthStatus
import nl.mteij.healthbridge.health.RecordSpecs
import nl.mteij.healthbridge.health.Spec
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** How a run ended; decides whether WorkManager retries it. */
enum class Outcome { SUCCESS, RETRY, FAILED }

/** Where a run is: the data type it is on, how many types there are, and records sent so far. */
data class Progress(val label: String = "", val index: Int = 0, val total: Int = 0, val records: Int = 0) {
    val fraction: Float? get() = if (total > 0) index.toFloat() / total else null
}

/**
 * Pushes Health Connect data to the server.
 *
 * Per record type the first run backfills a time window and then follows Health Connect's changes
 * feed, so later runs only transfer what is new, edited or deleted. A failure in one type never
 * stops the others, and every failure ends up in the log.
 */
class SyncEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val api: Api,
    private val log: SyncLog,
) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<Progress?>(null)
    private var historyGranted = false
    private var recordsSent = 0

    /** Progress while a run is active (or queued), null when idle. */
    val progress: StateFlow<Progress?> = _progress

    /** Shows the progress bar right away, before the background task has actually started. */
    fun markQueued() {
        if (_progress.value == null) _progress.value = Progress()
    }

    suspend fun run(trigger: String, full: Boolean = false): Outcome = mutex.withLock {
        try {
            runLocked(trigger, full)
        } finally {
            _progress.value = null
        }
    }

    private suspend fun runLocked(trigger: String, full: Boolean): Outcome {
        prefs.lastRunMs = System.currentTimeMillis()
        log.info("Sync started ($trigger${if (full) ", full" else ""})")

        when (HealthAccess.status(context)) {
            HealthStatus.AVAILABLE -> Unit
            else -> {
                log.error("Health Connect is not available or needs an update")
                return Outcome.FAILED
            }
        }
        if (!prefs.isLoggedIn) {
            log.error("Not logged in")
            return Outcome.FAILED
        }

        val client = HealthAccess.client(context)
        val granted = HealthAccess.granted(context)
        historyGranted = HealthAccess.historyPermission in granted
        if (HealthAccess.backgroundPermission !in granted) {
            log.warn("Background access is not granted; syncing only works while the app is open")
        } else if (!HealthAccess.backgroundReadSupported(context)) {
            log.warn("This Health Connect version does not support background reads; update Health Connect")
        }
        val disabled = prefs.disabledTypes
        val specs = RecordSpecs.all.filter { it.readPermission in granted && it.key !in disabled }
        if (specs.isEmpty()) {
            log.error("No Health Connect permissions granted")
            return Outcome.FAILED
        }

        // A full re-import is remembered per type, so a killed or retried run resumes where it stopped.
        if (full) prefs.pendingFull = specs.map { it.key }.toSet()
        recordsSent = 0

        var uploaded = 0
        var deleted = 0
        var failures = 0
        for ((index, spec) in specs.withIndex()) {
            _progress.value = Progress(spec.label, index, specs.size, recordsSent)
            try {
                val result = syncType(client, spec, spec.key in prefs.pendingFull)
                prefs.pendingFull = prefs.pendingFull - spec.key
                uploaded += result.first
                deleted += result.second
            } catch (e: IOException) {
                // network or server trouble: stop and let the caller retry later
                log.error("${e.message ?: e.javaClass.simpleName}", spec.key)
                return if (e is ApiException && e.code in 400..499 && e.code != 429) Outcome.FAILED else Outcome.RETRY
            } catch (e: IllegalStateException) {
                // Health Connect reports rate limits and a temporarily unavailable service this way
                log.warn("Health Connect is busy or rate limiting, will retry: ${e.message}", spec.key)
                return Outcome.RETRY
            } catch (e: SecurityException) {
                failures++
                log.error("Permission denied: ${e.message}", spec.key)
            } catch (e: Exception) {
                failures++
                log.error("${e.javaClass.simpleName}: ${e.message}", spec.key)
            }
        }

        prefs.lastRunMs = System.currentTimeMillis()
        if (failures == 0) prefs.lastSuccessMs = prefs.lastRunMs
        val summary = "Sync finished: $uploaded uploaded, $deleted deleted" +
            if (failures > 0) ", $failures type(s) failed" else ""
        if (failures > 0) log.warn(summary) else log.info(summary)
        return if (failures == 0) Outcome.SUCCESS else Outcome.FAILED
    }

    /** Returns (uploaded, deleted). */
    private suspend fun syncType(
        client: androidx.health.connect.client.HealthConnectClient,
        spec: Spec<out androidx.health.connect.client.records.Record>,
        full: Boolean,
    ): Pair<Int, Int> {
        val token = if (full) null else prefs.changesToken(spec.key)
        if (token == null) return backfill(client, spec) to 0

        var current: String = token
        var uploaded = 0
        var deleted = 0
        while (true) {
            val response = client.getChanges(current)
            if (response.changesTokenExpired) {
                log.warn("Changes token expired, doing a full resync", spec.key)
                prefs.setChangesToken(spec.key, null)
                return backfill(client, spec) to deleted
            }
            val upserts = response.changes.filterIsInstance<UpsertionChange>().map { spec.encode(it.record) }
            val deletions = response.changes.filterIsInstance<DeletionChange>().map { it.recordId }
            batches(upserts).forEach { api.upload(spec.key, it); recordsSent += it.size; bump(spec) }
            deletions.chunked(MAX_BATCH_RECORDS).forEach { api.delete(spec.key, it) }
            uploaded += upserts.size
            deleted += deletions.size
            current = response.nextChangesToken
            prefs.setChangesToken(spec.key, current)
            if (!response.hasMore) break
        }
        if (uploaded + deleted > 0) log.info("$uploaded uploaded, $deleted deleted", spec.key)
        return uploaded to deleted
    }

    private suspend fun backfill(
        client: androidx.health.connect.client.HealthConnectClient,
        spec: Spec<out androidx.health.connect.client.records.Record>,
    ): Int {
        // take the changes token first so nothing written during the backfill is missed
        val changesToken = spec.newChangesToken(client)
        val now = Instant.now()
        // without the history permission Health Connect rejects reads older than 30 days
        val days = if (historyGranted) prefs.backfillDays else minOf(prefs.backfillDays, MAX_DAYS_WITHOUT_HISTORY)
        if (days < prefs.backfillDays) log.warn("History permission missing: importing only the last $days days", spec.key)
        val from = now.minus(Duration.ofDays(days.toLong()))
        var pageToken: String? = null
        var uploaded = 0
        do {
            val page = spec.read(client, from, now, pageToken)
            batches(page.records.map { spec.encode(it) }).forEach { api.upload(spec.key, it); recordsSent += it.size; bump(spec) }
            uploaded += page.records.size
            pageToken = page.nextPageToken
        } while (pageToken != null)
        prefs.setChangesToken(spec.key, changesToken)
        log.info("Initial import: $uploaded records from the last $days days", spec.key)
        return uploaded
    }

    private fun bump(spec: Spec<out androidx.health.connect.client.records.Record>) {
        _progress.value = _progress.value?.copy(records = recordsSent)
    }

    /** Fewer, larger requests: up to [MAX_BATCH_RECORDS] records or roughly [MAX_BATCH_CHARS] of JSON each. */
    private fun batches(records: List<kotlinx.serialization.json.JsonObject>): List<List<kotlinx.serialization.json.JsonObject>> {
        val out = mutableListOf<List<kotlinx.serialization.json.JsonObject>>()
        var current = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var size = 0
        for (record in records) {
            val length = record.toString().length
            if (current.isNotEmpty() && (current.size >= MAX_BATCH_RECORDS || size + length > MAX_BATCH_CHARS)) {
                out += current
                current = mutableListOf()
                size = 0
            }
            current += record
            size += length
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private companion object {
        const val MAX_BATCH_RECORDS = 1000
        const val MAX_BATCH_CHARS = 2_000_000
        const val MAX_DAYS_WITHOUT_HISTORY = 29
    }
}
