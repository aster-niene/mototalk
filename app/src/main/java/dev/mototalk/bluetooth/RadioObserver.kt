package dev.mototalk.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.diag.Names

/**
 * Logs Bluetooth headset (HFP/SCO), A2DP, adapter and Wi-Fi state broadcasts
 * (POC requirements FR-12, checklist M5 timestamps t2/t4).
 * Requires BLUETOOTH_CONNECT; the session does not start without it.
 */
class RadioObserver(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> log(
                    "bt_headset_connection", intent,
                    "state" to Names.profileConnectionState(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                    "previous" to Names.profileConnectionState(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)),
                )
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> log(
                    "bt_sco_audio", intent,
                    "state" to Names.headsetAudioState(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                    "previous" to Names.headsetAudioState(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)),
                )
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> log(
                    "bt_a2dp_connection", intent,
                    "state" to Names.profileConnectionState(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                    "previous" to Names.profileConnectionState(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)),
                )
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> log(
                    "bt_a2dp_playing", intent,
                    "state" to Names.a2dpPlayingState(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                    "previous" to Names.a2dpPlayingState(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)),
                )
                BluetoothAdapter.ACTION_STATE_CHANGED -> DiagnosticsLog.event(
                    "bt_adapter",
                    mapOf("state" to Names.adapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))),
                )
                WifiManager.WIFI_STATE_CHANGED_ACTION -> DiagnosticsLog.event(
                    "wifi_state",
                    mapOf("state" to Names.wifiState(intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1))),
                )
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        // Bluetooth profile broadcasts come from com.android.bluetooth (uid 1002), not system_server:
        // a NOT_EXPORTED receiver never gets them. All actions here are protected broadcasts,
        // so EXPORTED cannot be spoofed by other apps.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }

    private fun log(event: String, intent: Intent, vararg fields: Pair<String, Any?>) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val name = try {
            device?.name
        } catch (e: SecurityException) {
            null
        }
        DiagnosticsLog.event(event, mapOf(*fields) + ("device" to name))
    }
}
