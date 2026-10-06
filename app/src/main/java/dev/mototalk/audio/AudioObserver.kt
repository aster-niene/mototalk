package dev.mototalk.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.diag.Names

/**
 * Logs what the audio system does while a session runs (POC requirements FR-12, checklist M5–M9):
 * audio mode changes (calls), communication device changes (SCO up/down),
 * audio devices added/removed (helmet on/off) and other apps' players (Spotify, Maps).
 */
class AudioObserver(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val executor = context.mainExecutor
    private val handler = Handler(Looper.getMainLooper())

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        DiagnosticsLog.event("audio_mode", mapOf("mode" to Names.audioMode(mode)))
    }

    private val communicationDeviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        DiagnosticsLog.event("comm_device", mapOf("device" to device?.let(::describe)))
    }

    // Registration immediately reports every connected device; mark that first report as the initial list.
    private var initialDevicesReported = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val initial = !initialDevicesReported
            initialDevicesReported = true
            DiagnosticsLog.event(
                "audio_devices_added",
                mapOf("initial" to initial, "devices" to addedDevices.map(::describe)),
            )
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            DiagnosticsLog.event("audio_devices_removed", mapOf("devices" to removedDevices.map(::describe)))
        }
    }

    // The callback fires many times with the same list; log only real changes.
    private var lastPlayers: List<Map<String, Any?>>? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val players = configs.map(::describe)
            if (players == lastPlayers) return
            lastPlayers = players
            DiagnosticsLog.event("playback_configs", mapOf("players" to players))
        }
    }

    fun start() {
        audioManager.addOnModeChangedListener(executor, modeListener)
        audioManager.addOnCommunicationDeviceChangedListener(executor, communicationDeviceListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        DiagnosticsLog.event("audio_snapshot", snapshot())
    }

    fun stop() {
        audioManager.removeOnModeChangedListener(modeListener)
        audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "mode" to Names.audioMode(audioManager.mode),
        "commDevice" to audioManager.communicationDevice?.let(::describe),
        "availableCommDevices" to audioManager.availableCommunicationDevices.map(::describe),
        "players" to audioManager.activePlaybackConfigurations.map(::describe),
    )

    private fun describe(device: AudioDeviceInfo): Map<String, Any?> = mapOf(
        "id" to device.id,
        "type" to Names.deviceType(device.type),
        "name" to device.productName.toString(),
        "sink" to device.isSink,
        "sampleRates" to device.sampleRates.toList(),
    )

    private fun describe(config: AudioPlaybackConfiguration): Map<String, Any?> {
        val raw = config.toString()
        val parsed = parsePlayer(raw)
        return linkedMapOf(
            "piid" to parsed.piid,
            "state" to parsed.state,
            "usage" to Names.usage(config.audioAttributes.usage),
            "contentType" to Names.contentType(config.audioAttributes.contentType),
        ).apply { if (parsed.piid == null || parsed.state == null) put("raw", raw) }
    }
}

internal data class ParsedPlayer(val piid: Int?, val state: String?)

/** Player id and state are not public API; the anonymized toString() carries them ("piid:22511 ... state:started"). */
internal fun parsePlayer(raw: String): ParsedPlayer = ParsedPlayer(
    piid = Regex("""piid:(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull(),
    state = Regex("""state:(\w+)""").find(raw)?.groupValues?.get(1),
)
