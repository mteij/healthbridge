package nl.mteij.healthbridge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The server answered, but not with success. `code` is the HTTP status. */
class ApiException(val code: Int, message: String) : IOException(message)

@Serializable
data class HeartRateStats(val avg: Int, val min: Int, val max: Int, val samples: Int)

@Serializable
data class HrPoint(val t: String, val bpm: Int)

@Serializable
data class SpeedPoint(val t: String, val mps: Double)

@Serializable
data class Samples(
    @SerialName("heart_rate") val heartRate: List<HrPoint> = emptyList(),
    val speed: List<SpeedPoint> = emptyList(),
)

@Serializable
data class Split(val km: Double, val seconds: Int, @SerialName("pace_s_per_km") val paceSPerKm: Int, @SerialName("avg_hr") val avgHr: Int? = null, val partial: Boolean = false)

@Serializable
data class SeriesPoint(val date: String, val value: Double, val unit: String = "")

@Serializable
data class WeekSummary(val workouts: Int = 0, @SerialName("intensity_min") val intensityMin: Double? = null)

@Serializable
data class DayTotal(val date: String, val value: Double? = null, val unit: String = "", val source: String? = null)

@Serializable
data class Measurement(val metric: String, val value: Double? = null, val unit: String = "", val time: String? = null)

@Serializable
data class SleepNight(
    val date: String,
    val start: String,
    val end: String? = null,
    @SerialName("in_bed_min") val inBedMin: Int = 0,
    @SerialName("asleep_min") val asleepMin: Int? = null,
    @SerialName("stages_min") val stagesMin: Map<String, Int> = emptyMap(),
)

@Serializable
data class OverviewData(
    val date: String,
    val daily: Map<String, List<DayTotal>> = emptyMap(),
    val sleep: List<SleepNight> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    @SerialName("recent_workouts") val recentWorkouts: List<Workout> = emptyList(),
    val week: WeekSummary = WeekSummary(),
)

@Serializable
data class Workout(
    val id: String,
    val type: String,
    val title: String? = null,
    val source: String? = null,
    val start: String,
    val end: String,
    @SerialName("duration_s") val durationS: Long,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("active_kcal") val activeKcal: Double? = null,
    @SerialName("total_kcal") val totalKcal: Double? = null,
    val steps: Long? = null,
    @SerialName("heart_rate") val heartRate: HeartRateStats? = null,
    @SerialName("avg_speed_mps") val avgSpeedMps: Double? = null,
    @SerialName("pace_s_per_km") val paceSPerKm: Long? = null,
    @SerialName("max_speed_mps") val maxSpeedMps: Double? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    val notes: String? = null,
    val samples: Samples? = null,
    @SerialName("zones_s") val zonesS: List<Int>? = null,
    @SerialName("intensity_min") val intensityMin: Double? = null,
    val splits: List<Split> = emptyList(),
)

/** Compresses larger request bodies; the server accepts `Content-Encoding: gzip`. */
private object GzipRequestInterceptor : Interceptor {
    private const val MIN_BYTES = 1024L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (body == null || request.header("Content-Encoding") != null || body.contentLength() in 0 until MIN_BYTES) {
            return chain.proceed(request)
        }
        val buffer = Buffer()
        GzipSink(buffer).buffer().use { body.writeTo(it) }
        val compressed = buffer.readByteString().toRequestBody(body.contentType())
        return chain.proceed(request.newBuilder().header("Content-Encoding", "gzip").method(request.method, compressed).build())
    }
}

class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(GzipRequestInterceptor)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = prefs.serverUrl.trimEnd('/') + "/api/v2/" + path

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw ApiException(response.code, detail ?: "HTTP ${response.code}")
            }
            return text
        }
    }

    private fun readSession(text: String) {
        val obj = json.parseToJsonElement(text).jsonObject
        prefs.token = obj.getValue("token").jsonPrimitive.content
        prefs.refreshToken = obj.getValue("refresh").jsonPrimitive.content
        // the server issues 12 hour tokens; renew a little early
        prefs.tokenExpiryMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(11)
    }

    suspend fun login(server: String, username: String, password: String) = withContext(Dispatchers.IO) {
        prefs.serverUrl = server
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(jsonType)
        readSession(execute(Request.Builder().url(url("login")).post(body).build()))
        prefs.username = username
        prefs.password = password
    }

    /** Make sure we hold a valid token: refresh it, or log in again with the stored password. */
    private fun ensureSession() {
        val token = prefs.token
        if (token != null && System.currentTimeMillis() < prefs.tokenExpiryMs) return
        val refresh = prefs.refreshToken
        if (refresh != null) {
            val ok = runCatching {
                val body = buildJsonObject { put("refresh", refresh) }.toString().toRequestBody(jsonType)
                readSession(execute(Request.Builder().url(url("refresh")).post(body).build()))
            }.isSuccess
            if (ok) return
        }
        val password = prefs.password ?: throw ApiException(401, "Not logged in")
        val body = buildJsonObject {
            put("username", prefs.username)
            put("password", password)
        }.toString().toRequestBody(jsonType)
        readSession(execute(Request.Builder().url(url("login")).post(body).build()))
    }

    private fun authed(build: (Request.Builder) -> Request.Builder): String {
        ensureSession()
        fun attempt() = execute(build(Request.Builder()).header("Authorization", "Bearer ${prefs.token}").build())
        return try {
            attempt()
        } catch (e: ApiException) {
            if (e.code != 401 && e.code != 403) throw e
            prefs.clearSession()
            ensureSession()
            attempt()
        }
    }

    suspend fun upload(type: String, records: List<JsonObject>) = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext
        val body = buildJsonObject { put("data", JsonArray(records)) }.toString().toRequestBody(jsonType)
        authed { it.url(url("sync/$type")).post(body) }
    }

    suspend fun delete(type: String, ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val body = buildJsonObject {
            put("uuid", JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString().toRequestBody(jsonType)
        authed { it.url(url("sync/$type")).delete(body) }
    }

    /** Raw JSON of a GET endpoint, so callers can cache the text and decode it themselves. */
    suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        authed { it.url(url(path)).get() }
    }

    val format: Json get() = json

}
