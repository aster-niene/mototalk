package dev.mototalk.diag

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager

/** Human-readable names for platform int constants, so diagnostics logs can be read without a lookup table. */
object Names {

    fun audioMode(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
        else -> "MODE_$mode"
    }

    fun focusChange(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "GAIN_TRANSIENT"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "GAIN_TRANSIENT_MAY_DUCK"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "GAIN_TRANSIENT_EXCLUSIVE"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> "FOCUS_$change"
    }

    fun focusRequestResult(result: Int): String = when (result) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "FAILED"
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
        else -> "RESULT_$result"
    }

    fun deviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        else -> "TYPE_$type"
    }

    fun usage(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "VOICE_COMMUNICATION_SIGNALLING"
        AudioAttributes.USAGE_ALARM -> "ALARM"
        AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "NOTIFICATION_RINGTONE"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "ASSISTANCE_ACCESSIBILITY"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "ASSISTANCE_NAVIGATION_GUIDANCE"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "ASSISTANCE_SONIFICATION"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        else -> "USAGE_$usage"
    }

    fun contentType(type: Int): String = when (type) {
        AudioAttributes.CONTENT_TYPE_UNKNOWN -> "UNKNOWN"
        AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
        AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
        AudioAttributes.CONTENT_TYPE_MOVIE -> "MOVIE"
        AudioAttributes.CONTENT_TYPE_SONIFICATION -> "SONIFICATION"
        else -> "CONTENT_$type"
    }

    fun profileConnectionState(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "STATE_$state"
    }

    fun headsetAudioState(state: Int): String = when (state) {
        BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> "AUDIO_DISCONNECTED"
        BluetoothHeadset.STATE_AUDIO_CONNECTING -> "AUDIO_CONNECTING"
        BluetoothHeadset.STATE_AUDIO_CONNECTED -> "AUDIO_CONNECTED"
        else -> "STATE_$state"
    }

    fun a2dpPlayingState(state: Int): String = when (state) {
        BluetoothA2dp.STATE_PLAYING -> "PLAYING"
        BluetoothA2dp.STATE_NOT_PLAYING -> "NOT_PLAYING"
        else -> "STATE_$state"
    }

    fun adapterState(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "STATE_$state"
    }

    fun wifiState(state: Int): String = when (state) {
        WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
        WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
        WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
        WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
        else -> "STATE_$state"
    }
}
