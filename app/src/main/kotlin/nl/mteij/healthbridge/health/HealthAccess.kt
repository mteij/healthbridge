package nl.mteij.healthbridge.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission

enum class HealthStatus { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

/** Thin wrapper so the UI and the sync engine ask Health Connect the same questions. */
object HealthAccess {
    private const val PROVIDER = "com.google.android.apps.healthdata"

    fun status(context: Context): HealthStatus = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthStatus.UPDATE_REQUIRED
        else -> HealthStatus.UNAVAILABLE
    }

    fun client(context: Context): HealthConnectClient = HealthConnectClient.getOrCreate(context, PROVIDER)

    suspend fun granted(context: Context): Set<String> =
        client(context).permissionController.getGrantedPermissions()

    /** Background reads need both the permission and a Health Connect version that supports them. */
    fun backgroundReadSupported(context: Context): Boolean = runCatching {
        client(context).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.android.vending")
        data = Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding")
        putExtra("overlay", true)
        putExtra("callerId", "nl.mteij.healthbridge")
    }

    val backgroundPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    val historyPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /**
     * Health Connect has no "read all routes" permission: the user allows each workout's route in its own
     * consent screen (ExerciseRouteRequestContract). This lists the workouts whose route is there but not yet shared.
     */
    suspend fun sessionsNeedingRouteConsent(context: Context, since: java.time.Instant): List<String> {
        val client = client(context)
        val out = mutableListOf<String>()
        var token: String? = null
        do {
            val page = client.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    androidx.health.connect.client.records.ExerciseSessionRecord::class,
                    androidx.health.connect.client.time.TimeRangeFilter.between(since, java.time.Instant.now()),
                    pageSize = 1000, pageToken = token,
                ),
            )
            page.records.filter { it.exerciseRouteResult is androidx.health.connect.client.records.ExerciseRouteResult.ConsentRequired }
                .mapTo(out) { it.metadata.id }
            token = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return out
    }

    suspend fun session(context: Context, id: String): androidx.health.connect.client.records.ExerciseSessionRecord? =
        runCatching { client(context).readRecord(androidx.health.connect.client.records.ExerciseSessionRecord::class, id).record }.getOrNull()
}
