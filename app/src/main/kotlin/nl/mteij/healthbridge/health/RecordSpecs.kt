package nl.mteij.healthbridge.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.reflect.KClass

class Page(val records: List<Record>, val nextPageToken: String?)

/**
 * One Health Connect record type: how to read it, and how to turn it into the JSON shape the
 * server (and the workout aggregation) expects. Field names and units mirror what
 * react-native-health-connect produced, so data synced by the old app stays compatible.
 */
class Spec<T : Record>(
    /** Collection name on the server, e.g. `heartRate`. */
    val key: String,
    val kClass: KClass<T>,
    /** Display name for the settings screen. */
    val label: String,
    /** Category in the settings screen: activity, heart, sleep, body or vitals. */
    val group: String,
    private val fields: JsonObjectBuilder.(T) -> Unit,
) {
    val readPermission: String get() = HealthPermission.getReadPermission(kClass)

    suspend fun read(client: HealthConnectClient, from: Instant, to: Instant, pageToken: String?): Page {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = kClass,
                timeRangeFilter = TimeRangeFilter.between(from, to),
                pageSize = PAGE_SIZE,
                pageToken = pageToken,
            ),
        )
        return Page(response.records, response.pageToken?.takeIf { it.isNotEmpty() })
    }

    suspend fun newChangesToken(client: HealthConnectClient): String =
        client.getChangesToken(ChangesTokenRequest(setOf(kClass)))

    // IntervalRecord / InstantaneousRecord are internal to the library, so read the times reflectively.
    private fun getter(name: String) = kClass.java.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
    private val startGetter by lazy { getter("getStartTime") }
    private val endGetter by lazy { getter("getEndTime") }
    private val timeGetter by lazy { getter("getTime") }

    @Suppress("UNCHECKED_CAST")
    fun encode(record: Record): JsonObject = buildJsonObject {
        val r = record as T
        put("metadata", metadata(r))
        val start = startGetter
        val end = endGetter
        if (start != null && end != null) {
            put("startTime", (start.invoke(r) as Instant).toString())
            put("endTime", (end.invoke(r) as Instant).toString())
        } else {
            put("time", (timeGetter!!.invoke(r) as Instant).toString())
        }
        fields(r)
    }

    private fun metadata(r: Record) = buildJsonObject {
        val m = r.metadata
        put("id", m.id)
        put("dataOrigin", m.dataOrigin.packageName)
        put("lastModifiedTime", m.lastModifiedTime.toString())
        m.clientRecordId?.let { put("clientRecordId", it) }
        put("recordingMethod", m.recordingMethod)
        m.device?.let { d ->
            put("device", buildJsonObject {
                d.manufacturer?.let { put("manufacturer", it) }
                d.model?.let { put("model", it) }
                put("type", d.type)
            })
        }
    }

    companion object {
        const val PAGE_SIZE = 1000
    }
}

private inline fun <reified T : Record> spec(
    key: String,
    label: String,
    group: String,
    noinline fields: JsonObjectBuilder.(T) -> Unit,
) = Spec(key, T::class, label, group, fields)


private fun JsonObjectBuilder.length(name: String, l: androidx.health.connect.client.units.Length) =
    put(name, buildJsonObject { put("inMeters", l.inMeters) })

private fun JsonObjectBuilder.energy(name: String, e: androidx.health.connect.client.units.Energy) =
    put(name, buildJsonObject { put("inKilocalories", e.inKilocalories) })

private fun JsonObjectBuilder.mass(name: String, m: androidx.health.connect.client.units.Mass) =
    put(name, buildJsonObject { put("inKilograms", m.inKilograms) })

private fun JsonObjectBuilder.percentage(name: String, p: androidx.health.connect.client.units.Percentage) =
    put(name, buildJsonObject { put("value", p.value) })

private fun JsonObjectBuilder.power(name: String, p: androidx.health.connect.client.units.Power) =
    put(name, buildJsonObject { put("inWatts", p.inWatts) })

object RecordSpecs {
    val all: List<Spec<out Record>> = listOf(
        spec<ExerciseSessionRecord>("exerciseSession", "Workouts", "activity") { r ->
            put("exerciseType", r.exerciseType)
            r.title?.let { put("title", it) }
            r.notes?.let { put("notes", it) }
            put("segments", buildJsonArray {
                r.segments.forEach { s ->
                    add(buildJsonObject {
                        put("startTime", s.startTime.toString())
                        put("endTime", s.endTime.toString())
                        put("segmentType", s.segmentType)
                        put("repetitions", s.repetitions)
                    })
                }
            })
            put("laps", buildJsonArray {
                r.laps.forEach { l ->
                    add(buildJsonObject {
                        put("startTime", l.startTime.toString())
                        put("endTime", l.endTime.toString())
                        l.length?.let { length("length", it) }
                    })
                }
            })
        },
        spec<HeartRateRecord>("heartRate", "Heart rate", "heart") { r ->
            put("samples", buildJsonArray {
                r.samples.forEach { s ->
                    add(buildJsonObject {
                        put("time", s.time.toString())
                        put("beatsPerMinute", s.beatsPerMinute)
                    })
                }
            })
        },
        spec<DistanceRecord>("distance", "Distance", "activity") { r -> length("distance", r.distance) },
        spec<SpeedRecord>("speed", "Speed", "activity") { r ->
            put("samples", buildJsonArray {
                r.samples.forEach { s ->
                    add(buildJsonObject {
                        put("time", s.time.toString())
                        put("speed", buildJsonObject { put("inMetersPerSecond", s.speed.inMetersPerSecond) })
                    })
                }
            })
        },
        spec<StepsRecord>("steps", "Steps", "activity") { r -> put("count", r.count) },
        spec<ActiveCaloriesBurnedRecord>("activeCaloriesBurned", "Active calories", "activity") { r -> energy("energy", r.energy) },
        spec<TotalCaloriesBurnedRecord>("totalCaloriesBurned", "Total calories", "activity") { r -> energy("energy", r.energy) },
        spec<ElevationGainedRecord>("elevationGained", "Elevation gained", "activity") { r -> length("elevation", r.elevation) },
        spec<SleepSessionRecord>("sleepSession", "Sleep", "sleep") { r ->
            r.title?.let { put("title", it) }
            r.notes?.let { put("notes", it) }
            put("stages", buildJsonArray {
                r.stages.forEach { s ->
                    add(buildJsonObject {
                        put("startTime", s.startTime.toString())
                        put("endTime", s.endTime.toString())
                        put("stage", s.stage)
                    })
                }
            })
        },
        spec<WeightRecord>("weight", "Weight", "body") { r -> mass("weight", r.weight) },
        spec<HeightRecord>("height", "Height", "body") { r -> length("height", r.height) },
        spec<LeanBodyMassRecord>("leanBodyMass", "Lean body mass", "body") { r -> mass("mass", r.mass) },
        spec<BodyFatRecord>("bodyFat", "Body fat", "body") { r -> percentage("percentage", r.percentage) },
        spec<RestingHeartRateRecord>("restingHeartRate", "Resting heart rate", "heart") { r ->
            put("beatsPerMinute", r.beatsPerMinute)
        },
        spec<HeartRateVariabilityRmssdRecord>("heartRateVariabilityRmssd", "Heart rate variability", "heart") { r ->
            put("heartRateVariabilityMillis", r.heartRateVariabilityMillis)
        },
        spec<Vo2MaxRecord>("vo2Max", "VO2 max", "heart") { r ->
            put("vo2MillilitersPerMinuteKilogram", r.vo2MillilitersPerMinuteKilogram)
            put("measurementMethod", r.measurementMethod)
        },
        spec<OxygenSaturationRecord>("oxygenSaturation", "Oxygen saturation", "heart") { r -> percentage("percentage", r.percentage) },
        spec<RespiratoryRateRecord>("respiratoryRate", "Respiratory rate", "heart") { r -> put("rate", r.rate) },
        spec<BloodPressureRecord>("bloodPressure", "Blood pressure", "vitals") { r ->
            put("systolic", buildJsonObject { put("inMillimetersOfMercury", r.systolic.inMillimetersOfMercury) })
            put("diastolic", buildJsonObject { put("inMillimetersOfMercury", r.diastolic.inMillimetersOfMercury) })
        },
        spec<BloodGlucoseRecord>("bloodGlucose", "Blood glucose", "vitals") { r ->
            put("level", buildJsonObject { put("inMillimolesPerLiter", r.level.inMillimolesPerLiter) })
        },
        spec<BodyTemperatureRecord>("bodyTemperature", "Body temperature", "body") { r ->
            put("temperature", buildJsonObject { put("inCelsius", r.temperature.inCelsius) })
        },
        spec<BasalMetabolicRateRecord>("basalMetabolicRate", "Basal metabolic rate", "body") { r ->
            put("basalMetabolicRate", buildJsonObject { put("inKilocaloriesPerDay", r.basalMetabolicRate.inKilocaloriesPerDay) })
        },
        spec<PowerRecord>("power", "Power", "activity") { r ->
            put("samples", buildJsonArray {
                r.samples.forEach { s ->
                    add(buildJsonObject {
                        put("time", s.time.toString())
                        power("power", s.power)
                    })
                }
            })
        },
        spec<StepsCadenceRecord>("stepsCadence", "Steps cadence", "activity") { r ->
            put("samples", buildJsonArray {
                r.samples.forEach { s ->
                    add(buildJsonObject {
                        put("time", s.time.toString())
                        put("rate", s.rate)
                    })
                }
            })
        },
        spec<FloorsClimbedRecord>("floorsClimbed", "Floors climbed", "activity") { r -> put("floors", r.floors) },
        spec<HydrationRecord>("hydration", "Hydration", "vitals") { r ->
            put("volume", buildJsonObject { put("inLiters", r.volume.inLiters) })
        },
    )

    val byKey: Map<String, Spec<out Record>> = all.associateBy { it.key }

    /** Everything the app may ask Health Connect for. */
    val permissions: Set<String> =
        all.map { it.readPermission }.toSet() +
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY +
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
}
