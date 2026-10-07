package dev.mototalk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.diag.Names
import dev.mototalk.intercom.LocalAudio
import dev.mototalk.intercom.SessionKind
import dev.mototalk.service.SessionStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The LocalAudio axis of a session (POC requirements FR-1, FR-2, FR-7 duck test, FR-11 local part).
 * All control logic runs on the main thread; [VoiceIo] does the blocking I/O on its own thread.
 *
 * Route lifecycle: setMode(IN_COMMUNICATION) → start streams → setCommunicationDevice(SCO)
 * → wait for the SCO callback (10 s) → READY. Lost SCO → DEVICE_LOST, mute, retry with backoff.
 * A cellular call → release everything (PAUSED_BY_CALL) and reopen the route when it ends.
 */
class AudioSession(private val context: Context, kind: SessionKind) : VoiceIo.Events {

    companion object {
        private const val ROUTE_TIMEOUT_MS = 10_000L
        private const val CALL_END_DEBOUNCE_MS = 1_000L
        private const val ROUTE_CHECK_DELAY_MS = 500L
        private const val MAX_RECORD_RESTARTS = 3
        private const val DUCK_TEST_MS = 5_000L
        private const val RECORD_SECONDS = 10
        private const val HEARTBEAT_MS = 60_000L
    }

    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val io = VoiceIo(this).apply { monitor = kind == SessionKind.LOOPBACK }

    private var state = LocalAudio.OFF
    private var stopped = false
    private var retryAttempt = 0
    private var recordRestarts = 0
    private var routeRequestedAt = 0L
    private var micSilenced = false
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var duckActive = false
    private var partnerSpeaking = false

    private val retryRunnable = Runnable { requestSco("retry") }
    private val routeTimeoutRunnable = Runnable { onRouteTimeout() }
    private val routeCheckRunnable = Runnable { checkStreamRouting() }
    private val callEndRunnable = Runnable { if (!isCallMode(am.mode)) resumeAfterCall() }
    private val duckEndRunnable = Runnable { endDuckTest("timer") }
    private var heartbeatFrames = 0L
    private val heartbeatRunnable = object : Runnable {
        // Positive evidence that the microphone keeps delivering frames (checklist M11).
        override fun run() {
            val frames = io.framesRead
            DiagnosticsLog.event(
                "audio_heartbeat",
                mapOf(
                    "localAudio" to state,
                    "frames" to frames - heartbeatFrames,
                    "micDbfs" to AudioStatsStore.state.value.micDbfs,
                    "record" to io.recordRoutedType()?.let(Names::deviceType),
                    "track" to io.trackRoutedType()?.let(Names::deviceType),
                    "silenced" to micSilenced,
                ),
            )
            heartbeatFrames = frames
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode -> onModeChanged(mode) }
    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device -> onCommunicationDeviceChanged(device) }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { isHeadset(it.type) }) onScoDeviceAppeared()
        }
    }
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            checkSilenced(configs)
        }
    }

    fun start() {
        am.addOnModeChangedListener(context.mainExecutor, modeListener)
        am.addOnCommunicationDeviceChangedListener(context.mainExecutor, communicationDeviceListener)
        am.registerAudioDeviceCallback(deviceCallback, handler)
        am.registerAudioRecordingCallback(recordingCallback, handler)
        AudioStatsStore.update { it.copy(commDevice = am.communicationDevice?.let(::label)) }
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
        if (isCallMode(am.mode)) {
            setState(LocalAudio.PAUSED_BY_CALL, "start_during_call_${Names.audioMode(am.mode)}")
            return
        }
        openRoute("start")
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        releaseFocus("stop")
        DiagnosticsLog.event("route_release", mapOf("state" to state))
        io.stop()
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
        am.removeOnModeChangedListener(modeListener)
        am.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
        am.unregisterAudioDeviceCallback(deviceCallback)
        am.unregisterAudioRecordingCallback(recordingCallback)
        setState(LocalAudio.OFF, "stop")
        AudioStatsStore.reset()
    }

    /** RIDE: helmet-mic frames go to the intercom (I/O thread). Set before or during the session. */
    fun setFrameSink(sink: ((ShortArray, Int) -> Unit)?) {
        io.frameSink = sink
    }

    /** RIDE: where the partner's voice comes from (I/O thread). */
    fun setPlayoutSource(source: (() -> ShortArray?)?) {
        io.playoutSource = source
    }

    /** FR-7: duck the music while the partner speaks. Main thread. */
    fun setPartnerSpeech(speaking: Boolean) {
        if (stopped || partnerSpeaking == speaking) return
        partnerSpeaking = speaking
        updateFocus(if (speaking) "partner_speech" else "partner_silent")
    }

    /** FR-2 "Duck test": hold AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK for 5 s (checklist M7, M8). */
    fun duckTest() {
        if (stopped || duckActive) return
        duckActive = true
        val granted = updateFocus("duck_test")
        DiagnosticsLog.event("duck_test_start", mapOf("granted" to granted, "localAudio" to state, "durationMs" to DUCK_TEST_MS))
        if (!granted) {
            duckActive = false
            return
        }
        AudioStatsStore.update { it.copy(duckActive = true) }
        handler.postDelayed(duckEndRunnable, DUCK_TEST_MS)
    }

    /**
     * One AudioFocusRequest per session (FR-7), held while the duck test or the partner's speech wants it.
     * Returns whether focus is held afterwards.
     */
    private fun updateFocus(reason: String): Boolean {
        val want = duckActive || partnerSpeaking
        if (want == focusHeld) return focusHeld
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                DiagnosticsLog.event("audio_focus_change", mapOf("change" to Names.focusChange(change)))
            }, handler)
            .build()
            .also { focusRequest = it }
        if (want) {
            val result = am.requestAudioFocus(request)
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            DiagnosticsLog.event("focus_request", mapOf("reason" to reason, "result" to Names.focusRequestResult(result)))
        } else {
            val result = am.abandonAudioFocusRequest(request)
            focusHeld = false
            DiagnosticsLog.event("focus_abandon", mapOf("reason" to reason, "result" to Names.focusRequestResult(result)))
        }
        AudioStatsStore.update { it.copy(duckActive = duckActive, musicDucked = focusHeld) }
        return focusHeld
    }

    /** FR-2 "Record 10 s": microphone audio into a WAV file (checklist M2). */
    fun recordSample() {
        if (stopped) return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(Recordings.dir(context), "rec-$stamp-${DiagnosticsLog.phone}.wav")
        val accepted = io.recordNext(file, RECORD_SECONDS)
        DiagnosticsLog.event(
            "recording_start",
            mapOf(
                "file" to file.name, "seconds" to RECORD_SECONDS, "accepted" to accepted, "localAudio" to state,
                "recordRouted" to io.recordRoutedType()?.let(Names::deviceType),
            ),
        )
    }

    // --- Route ---

    private fun openRoute(reason: String) {
        setState(LocalAudio.ROUTING, reason)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        DiagnosticsLog.event("audio_mode_set", mapOf("requested" to "IN_COMMUNICATION", "effective" to Names.audioMode(am.mode)))
        io.muted = true
        if (!io.start(handler)) {
            setState(LocalAudio.DEVICE_LOST, "audio_io_failed")
            return
        }
        retryAttempt = 0
        recordRestarts = 0
        requestSco(reason)
    }

    private fun requestSco(reason: String) {
        if (stopped || state == LocalAudio.PAUSED_BY_CALL) return
        handler.removeCallbacks(retryRunnable)
        val sco = am.availableCommunicationDevices.let { devices ->
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
        if (sco == null) {
            DiagnosticsLog.event("route_request", mapOf("reason" to reason, "device" to null, "attempt" to retryAttempt))
            if (state != LocalAudio.DEVICE_LOST) setState(LocalAudio.DEVICE_LOST, "no_sco_device")
            scheduleRetry()
            return
        }
        routeRequestedAt = SystemClock.elapsedRealtime()
        val accepted = am.setCommunicationDevice(sco)
        DiagnosticsLog.event(
            "route_request",
            mapOf("reason" to reason, "device" to label(sco), "accepted" to accepted, "attempt" to retryAttempt),
        )
        if (!accepted) {
            scheduleRetry()
            return
        }
        if (isHeadset(am.communicationDevice?.type)) {
            onScoActive("already_active")
            return
        }
        handler.removeCallbacks(routeTimeoutRunnable)
        handler.postDelayed(routeTimeoutRunnable, ROUTE_TIMEOUT_MS)
    }

    private fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) {
        AudioStatsStore.update { it.copy(commDevice = device?.let(::label)) }
        if (stopped) return
        if (isHeadset(device?.type)) {
            if (state == LocalAudio.ROUTING || state == LocalAudio.DEVICE_LOST) onScoActive("callback")
        } else if (state == LocalAudio.READY) {
            onScoLost("comm_device_${device?.type?.let(Names::deviceType) ?: "none"}")
        }
    }

    private fun onScoActive(how: String) {
        handler.removeCallbacks(routeTimeoutRunnable)
        handler.removeCallbacks(retryRunnable)
        retryAttempt = 0
        recordRestarts = 0
        DiagnosticsLog.event(
            "route_ready",
            mapOf("how" to how, "sinceRequestMs" to SystemClock.elapsedRealtime() - routeRequestedAt),
        )
        io.armFirstMicAudio(routeRequestedAt)
        io.muted = false
        setState(LocalAudio.READY, "sco_active")
        handler.removeCallbacks(routeCheckRunnable)
        handler.postDelayed(routeCheckRunnable, ROUTE_CHECK_DELAY_MS)
    }

    /** The recorder was created before SCO came up; make sure it followed the route. */
    private fun checkStreamRouting() {
        val record = io.recordRoutedType()
        val track = io.trackRoutedType()
        DiagnosticsLog.event(
            "io_routing",
            mapOf("record" to record?.let(Names::deviceType), "track" to track?.let(Names::deviceType)),
        )
        if (state != LocalAudio.READY || isHeadset(record)) return
        if (recordRestarts >= MAX_RECORD_RESTARTS) {
            DiagnosticsLog.event("record_not_on_sco", mapOf("restarts" to recordRestarts))
            return
        }
        recordRestarts++
        DiagnosticsLog.event("record_restart", mapOf("attempt" to recordRestarts))
        io.requestRecordRestart()
        handler.postDelayed(routeCheckRunnable, ROUTE_CHECK_DELAY_MS * 2)
    }

    private fun onScoLost(reason: String) {
        io.muted = true
        setState(LocalAudio.DEVICE_LOST, reason)
        am.clearCommunicationDevice()
        scheduleRetry()
    }

    private fun onRouteTimeout() {
        DiagnosticsLog.event("route_timeout", mapOf("afterMs" to ROUTE_TIMEOUT_MS))
        am.clearCommunicationDevice()
        if (state != LocalAudio.DEVICE_LOST) setState(LocalAudio.DEVICE_LOST, "route_timeout")
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (stopped || state == LocalAudio.PAUSED_BY_CALL) return
        val delay = retryDelayMs(retryAttempt++)
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun onScoDeviceAppeared() {
        if (stopped || state != LocalAudio.DEVICE_LOST) return
        retryAttempt = 0
        requestSco("sco_device_added")
    }

    // --- Calls (FR-11, local part) ---

    private fun onModeChanged(mode: Int) {
        if (stopped) return
        if (isCallMode(mode)) {
            handler.removeCallbacks(callEndRunnable)
            if (state != LocalAudio.PAUSED_BY_CALL) pauseForCall(mode)
        } else if (state == LocalAudio.PAUSED_BY_CALL) {
            handler.removeCallbacks(callEndRunnable)
            handler.postDelayed(callEndRunnable, CALL_END_DEBOUNCE_MS)
        }
    }

    private fun pauseForCall(mode: Int) {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(routeTimeoutRunnable)
        handler.removeCallbacks(routeCheckRunnable)
        releaseFocus("call")
        io.stop()
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
        setState(LocalAudio.PAUSED_BY_CALL, "call_${Names.audioMode(mode)}")
    }

    private fun resumeAfterCall() {
        if (stopped || state != LocalAudio.PAUSED_BY_CALL) return
        DiagnosticsLog.event("call_ended", mapOf("mode" to Names.audioMode(am.mode)))
        openRoute("call_ended")
    }

    // --- Misc ---

    private fun endDuckTest(reason: String) {
        handler.removeCallbacks(duckEndRunnable)
        if (!duckActive) return
        duckActive = false
        updateFocus("duck_test_end")
        DiagnosticsLog.event("duck_test_end", mapOf("reason" to reason))
    }

    /** Releases any held focus: on Stop and calls nothing may stay ducked. */
    private fun releaseFocus(reason: String) {
        handler.removeCallbacks(duckEndRunnable)
        duckActive = false
        partnerSpeaking = false
        updateFocus(reason)
    }

    private fun checkSilenced(configs: List<AudioRecordingConfiguration>) {
        val sessionId = io.recordSessionId ?: return
        val ours = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
        if (ours.isClientSilenced == micSilenced) return
        micSilenced = ours.isClientSilenced
        DiagnosticsLog.event("mic_silenced", mapOf("silenced" to micSilenced))
        AudioStatsStore.update { it.copy(micSilenced = micSilenced) }
    }

    private fun setState(next: LocalAudio, reason: String) {
        state = next
        SessionStore.update(reason) { it.copy(localAudio = next) }
    }

    /** Bluetooth Classic (HFP/SCO) or LE Audio headset — the helmet route. */
    private fun isHeadset(type: Int?) =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun label(device: AudioDeviceInfo) = "${Names.deviceType(device.type)} ${device.productName}"

    // --- VoiceIo.Events (called on I/O threads) ---

    override fun onIoError(what: String, detail: String) {
        DiagnosticsLog.event("audio_io_error", mapOf("what" to what, "detail" to detail))
        AudioStatsStore.update { it.copy(ioError = "$what: $detail") }
    }

    override fun onFirstMicAudio(sinceArmMs: Long, dbfs: Double) {
        DiagnosticsLog.event("first_sco_mic_audio", mapOf("sinceRequestMs" to sinceArmMs, "dbfs" to dbfs))
    }

    override fun onRecordingSaved(file: File, seconds: Double, peakDbfs: Double, rmsDbfs: Double) {
        DiagnosticsLog.event(
            "recording_saved",
            mapOf("file" to file.name, "seconds" to seconds, "peakDbfs" to peakDbfs, "rmsDbfs" to rmsDbfs),
        )
    }
}
