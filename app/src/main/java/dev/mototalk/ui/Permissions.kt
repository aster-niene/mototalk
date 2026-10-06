package dev.mototalk.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Runtime permissions per session kind (POC requirements FR-8, §6). */
object Permissions {

    val loopback = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    val ride = loopback + listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    )

    /** Requested, but never blocks a session: without it the FGS notice is only in Task Manager. */
    val optional = listOf(Manifest.permission.POST_NOTIFICATIONS)

    val all = ride + optional

    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
}
