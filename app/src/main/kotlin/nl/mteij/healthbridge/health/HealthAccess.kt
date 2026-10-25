package nl.mteij.healthbridge.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
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

    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.android.vending")
        data = Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding")
        putExtra("overlay", true)
        putExtra("callerId", "nl.mteij.healthbridge")
    }

    val backgroundPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    val historyPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
}
