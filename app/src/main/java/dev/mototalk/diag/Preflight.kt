package dev.mototalk.diag

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * Radio checks before START RIDE (POC requirements FR-8, §8.7):
 * Bluetooth must be on, Wi-Fi should be on for Nearby's Wi-Fi upgrade,
 * and the phone should NOT be joined to a Wi-Fi network during P2P tests
 * (otherwise Nearby may route through the shared access point).
 */
data class Preflight(
    val bluetoothOn: Boolean,
    val wifiOn: Boolean,
    val wifiNetworkConnected: Boolean,
) {
    fun toLog(): Map<String, Any?> = mapOf(
        "bluetoothOn" to bluetoothOn,
        "wifiOn" to wifiOn,
        "wifiNetworkConnected" to wifiNetworkConnected,
    )

    companion object {
        fun read(context: Context): Preflight {
            val bluetoothOn = runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            }.getOrDefault(false)
            val wifiOn = runCatching {
                context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
            }.getOrDefault(false)
            // Any joined Wi-Fi network counts, not just the default one: an unvalidated router
            // stays connected behind cellular, and Nearby could still route through it.
            val wifiNetworkConnected = runCatching {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                @Suppress("DEPRECATION")
                cm?.allNetworks?.any { network ->
                    cm.getNetworkCapabilities(network)?.let {
                        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    } == true
                } == true
            }.getOrDefault(false)
            return Preflight(bluetoothOn, wifiOn, wifiNetworkConnected)
        }
    }
}
