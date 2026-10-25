package nl.mteij.healthbridge.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings and credentials, stored encrypted on the device. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "healthbridge_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _version = MutableStateFlow(0)

    /** Increments on every write, so Compose can re-read values by collecting this. */
    val version: StateFlow<Int> = _version

    private fun bump() {
        _version.value++
    }

    private fun string(key: String, default: String? = null) = sp.getString(key, default)

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        bump()
    }

    var serverUrl: String
        get() = string("serverUrl", DEFAULT_SERVER)!!
        set(v) = put { putString("serverUrl", v.trim().trimEnd('/')) }
    var username: String
        get() = string("username", "")!!
        set(v) = put { putString("username", v) }
    var password: String?
        get() = string("password")
        set(v) = put { putString("password", v) }
    var token: String?
        get() = string("token")
        set(v) = put { putString("token", v) }
    var refreshToken: String?
        get() = string("refresh")
        set(v) = put { putString("refresh", v) }
    var tokenExpiryMs: Long
        get() = sp.getLong("tokenExpiryMs", 0)
        set(v) = put { putLong("tokenExpiryMs", v) }

    val isLoggedIn: Boolean get() = !password.isNullOrEmpty() && username.isNotEmpty()

    var syncIntervalMinutes: Int
        get() = sp.getInt("syncIntervalMinutes", 60)
        set(v) = put { putInt("syncIntervalMinutes", v) }
    var unmeteredOnly: Boolean
        get() = sp.getBoolean("unmeteredOnly", false)
        set(v) = put { putBoolean("unmeteredOnly", v) }
    var backfillDays: Int
        get() = sp.getInt("backfillDays", 90)
        set(v) = put { putInt("backfillDays", v) }

    /** Record types the user switched off; everything else that is permitted gets synced. */
    var disabledTypes: Set<String>
        get() = sp.getStringSet("disabledTypes", emptySet())!!.toSet()
        set(v) = put { putStringSet("disabledTypes", v) }

    /** Types whose full re-import has not finished yet; survives the app being killed mid-import. */
    var pendingFull: Set<String>
        get() = sp.getStringSet("pendingFull", emptySet())!!.toSet()
        set(v) = put { putStringSet("pendingFull", v) }

    var notificationsAsked: Boolean
        get() = sp.getBoolean("notificationsAsked", false)
        set(v) = put { putBoolean("notificationsAsked", v) }

    /** Health profile. Zero or empty means "not set". */
    var birthYear: Int
        get() = sp.getInt("birthYear", 0)
        set(v) = put { putInt("birthYear", v) }
    var sex: String
        get() = string("sex", "")!!
        set(v) = put { putString("sex", v) }
    var manualHeightCm: Float
        get() = sp.getFloat("manualHeightCm", 0f)
        set(v) = put { putFloat("manualHeightCm", v) }
    var manualWeightKg: Float
        get() = sp.getFloat("manualWeightKg", 0f)
        set(v) = put { putFloat("manualWeightKg", v) }

    /** Highest believable heart rate seen in the user's own workouts; refined whenever workouts load. */
    var observedMaxHr: Int
        get() = sp.getInt("observedMaxHr", 0)
        set(v) = put { putInt("observedMaxHr", v) }

    var lastRunMs: Long
        get() = sp.getLong("lastRunMs", 0)
        set(v) = put { putLong("lastRunMs", v) }
    var lastSuccessMs: Long
        get() = sp.getLong("lastSuccessMs", 0)
        set(v) = put { putLong("lastSuccessMs", v) }

    fun changesToken(type: String): String? = string("changes_$type")
    fun setChangesToken(type: String, token: String?) = put {
        if (token == null) remove("changes_$type") else putString("changes_$type", token)
    }

    fun clearSession() = put {
        remove("token")
        remove("refresh")
        remove("tokenExpiryMs")
    }

    fun logout() {
        put {
            remove("password")
            remove("token")
            remove("refresh")
            remove("tokenExpiryMs")
            remove("lastRunMs")
            remove("lastSuccessMs")
            sp.all.keys.filter { it.startsWith("changes_") }.forEach { remove(it) }
        }
    }

    companion object {
        const val DEFAULT_SERVER = "https://healthbridge.mteij.nl"
    }
}
