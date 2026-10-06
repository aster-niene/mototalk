package dev.mototalk.diag

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.mototalk.BuildConfig
import java.util.UUID

/**
 * Samsung exposes the platform version as `Build.VERSION.SEM_PLATFORM_INT`:
 * 100000 = One UI 1.0, 150100 = One UI 6.1, ... (90000 was Samsung Experience 9).
 * The raw value is logged too, in case the mapping is off.
 */
internal fun oneUiFromSemPlatformInt(sem: Int): String? {
    if (sem < 100000) return null
    val v = sem - 90000
    return "${v / 10000}.${(v % 10000) / 100}"
}

object DeviceInfo {

    fun semPlatformInt(): Int? = runCatching {
        Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
    }.getOrNull()

    fun describe(): Map<String, Any?> {
        val sem = semPlatformInt()
        return linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "oneUi" to sem?.let(::oneUiFromSemPlatformInt),
            "semPlatformInt" to sem,
            "build" to Build.DISPLAY,
            "securityPatch" to Build.VERSION.SECURITY_PATCH,
            "appVersion" to BuildConfig.VERSION_NAME,
        )
    }
}

/** Stable per-install identity (POC requirements FR-3): random UUID created on first launch. */
object DeviceIdentity {

    private const val PREFS = "identity"
    private const val KEY_DEVICE_ID = "deviceId"

    @Synchronized
    fun deviceId(context: Context): UUID {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return UUID.fromString(it) }
        return UUID.randomUUID().also { id -> prefs.edit { putString(KEY_DEVICE_ID, id.toString()) } }
    }

    /** Label used in log lines and file names, e.g. `SM-S918B-3fa2`. */
    fun phoneLabel(context: Context): String =
        "${Build.MODEL}-${deviceId(context).toString().take(4)}"
}
