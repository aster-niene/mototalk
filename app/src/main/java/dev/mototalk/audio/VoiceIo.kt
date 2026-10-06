package dev.mototalk.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.diag.Names
import java.io.File
import kotlin.math.max

/**
 * Voice capture and playback (POC requirements FR-1): AudioRecord (VOICE_COMMUNICATION) and
 * AudioTrack (USAGE_VOICE_COMMUNICATION), 16 kHz mono PCM16, 20 ms frames, on one I/O thread.
 *
 * Both streams run continuously for the whole session: Android keeps an app as the owner of
 * MODE_IN_COMMUNICATION only while it has an active VOICE_COMMUNICATION stream. Without voice
 * the track plays silence and the recorder keeps reading.
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO is checked before a session can start.
class VoiceIo(private val events: Events) {

    interface Events {
        fun onIoError(what: String, detail: String)
        fun onFirstMicAudio(sinceArmMs: Long, dbfs: Double)
        fun onRecordingSaved(file: File, seconds: Double, peakDbfs: Double, rmsDbfs: Double)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms
        private const val BYTES_PER_SAMPLE = 2
        private const val LEVEL_EVERY_FRAMES = 5 // 100 ms
        private const val FRAMES_PER_SECOND = SAMPLE_RATE / FRAME_SAMPLES
    }

    /** Play the microphone back into the same headset (Loopback). */
    @Volatile var monitor = false

    /** When muted the track plays silence (no SCO route: the voice would go to the phone earpiece). */
    @Volatile var muted = true

    @Volatile private var running = false
    @Volatile private var restartRecordRequested = false
    @Volatile private var firstAudioArmedAt = 0L

    @Volatile private var recBuffer: ShortArray? = null
    @Volatile private var recFile: File? = null
    private var recPos = 0

    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var handler: Handler? = null

    /** Frames read since this object was created; survives restarts (heartbeat in the log). */
    @Volatile var framesRead = 0L
        private set

    val recordSessionId: Int? get() = record?.audioSessionId

    fun recordRoutedType(): Int? = record?.routedDevice?.type

    fun trackRoutedType(): Int? = track?.routedDevice?.type

    /** Creates and starts both streams. Returns false if the platform refused to create them. */
    fun start(routingHandler: Handler): Boolean {
        if (running) return true
        handler = routingHandler
        try {
            track = createTrack().also { it.play() }
            record = createRecord().also { it.startRecording() }
        } catch (e: Exception) {
            events.onIoError("create_streams", e.toString())
            releaseStreams()
            return false
        }
        running = true
        thread = Thread(::loop, "voice-io").also { it.start() }
        return true
    }

    fun stop() {
        if (!running && thread == null) return
        running = false
        runCatching { record?.stop() } // unblocks read()
        runCatching { track?.pause(); track?.flush() } // unblocks write()
        thread?.join(1_000)
        thread = null
        releaseStreams()
        if (recBuffer != null) {
            DiagnosticsLog.event("recording_aborted", mapOf("file" to recFile?.name, "capturedSamples" to recPos))
        }
        recBuffer = null
        AudioStatsStore.update { it.copy(recordingSecondsLeft = 0, micDbfs = null) }
    }

    /** Recreate the recorder on the I/O thread, e.g. when it did not follow the SCO route. */
    fun requestRecordRestart() {
        restartRecordRequested = true
    }

    /** Report the first non-zero frame captured over SCO after [sinceMonoMs]. */
    fun armFirstMicAudio(sinceMonoMs: Long) {
        firstAudioArmedAt = sinceMonoMs
    }

    /** Capture the next [seconds] of microphone audio into a WAV file. False if already recording. */
    fun recordNext(file: File, seconds: Int): Boolean {
        if (recBuffer != null || !running) return false
        recPos = 0
        recFile = file
        recBuffer = ShortArray(SAMPLE_RATE * seconds)
        AudioStatsStore.update { it.copy(recordingSecondsLeft = seconds) }
        return true
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(FRAME_SAMPLES)
        val silence = ShortArray(FRAME_SAMPLES)
        var frames = 0L
        var readErrors = 0
        while (running) {
            if (restartRecordRequested) {
                restartRecordRequested = false
                restartRecord()
            }
            val rec = record ?: break
            val n = rec.read(buf, 0, FRAME_SAMPLES)
            if (!running) break
            if (n <= 0) {
                if (readErrors++ == 0) events.onIoError("record_read", "code=$n")
                SystemClock.sleep(10)
                continue
            }
            frames++
            framesRead++

            val armedAt = firstAudioArmedAt
            if (armedAt != 0L && Dsp.hasSignal(buf, n) && rec.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                firstAudioArmedAt = 0L
                events.onFirstMicAudio(SystemClock.elapsedRealtime() - armedAt, Dsp.rmsDbfs(buf, n))
            }
            if (frames % LEVEL_EVERY_FRAMES == 0L) {
                val level = Dsp.rmsDbfs(buf, n)
                AudioStatsStore.update { it.copy(micDbfs = level) }
            }
            appendRecording(buf, n)

            track?.write(if (!muted && monitor) buf else silence, 0, n)
        }
    }

    private fun appendRecording(buf: ShortArray, n: Int) {
        val target = recBuffer ?: return
        val count = minOf(n, target.size - recPos)
        System.arraycopy(buf, 0, target, recPos, count)
        recPos += count
        if (recPos % (FRAME_SAMPLES * FRAMES_PER_SECOND) < n) {
            val left = (target.size - recPos) / SAMPLE_RATE
            AudioStatsStore.update { it.copy(recordingSecondsLeft = left) }
        }
        if (recPos < target.size) return
        val file = recFile ?: return
        recBuffer = null
        AudioStatsStore.update { it.copy(recordingSecondsLeft = 0) }
        Thread({
            try {
                Wav.write(file, SAMPLE_RATE, target)
                events.onRecordingSaved(
                    file, target.size.toDouble() / SAMPLE_RATE, Dsp.peakDbfs(target), Dsp.rmsDbfs(target),
                )
            } catch (e: Exception) {
                events.onIoError("recording_write", e.toString())
            }
        }, "wav-writer").start()
    }

    private fun restartRecord() {
        runCatching { record?.stop() }
        record?.release()
        record = try {
            createRecord().also { it.startRecording() }
        } catch (e: Exception) {
            events.onIoError("record_restart", e.toString())
            null
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, FRAME_SAMPLES * BYTES_PER_SAMPLE * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack not initialized" }
        t.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { r -> onRouted("track", r) }, handler)
        val ms = t.bufferSizeInFrames * 1000 / SAMPLE_RATE
        AudioStatsStore.update { it.copy(trackBufferMs = ms) }
        DiagnosticsLog.event("track_created", mapOf("minBufferBytes" to minBuf, "bufferMs" to ms))
        return t
    }

    private fun createRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = max(minBuf, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4)
        val r = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(size)
            .build()
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        r.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { rt -> onRouted("record", rt) }, handler)
        val ms = size / BYTES_PER_SAMPLE * 1000 / SAMPLE_RATE
        AudioStatsStore.update { it.copy(recordBufferMs = ms) }
        DiagnosticsLog.event(
            "record_created",
            mapOf("minBufferBytes" to minBuf, "bufferMs" to ms, "sampleRate" to r.sampleRate, "sessionId" to r.audioSessionId),
        )
        return r
    }

    private fun onRouted(stream: String, routing: AudioRouting) {
        val type = routing.routedDevice?.type?.let(Names::deviceType)
        DiagnosticsLog.event("${stream}_routed", mapOf("device" to type, "name" to routing.routedDevice?.productName?.toString()))
        AudioStatsStore.update { if (stream == "record") it.copy(recordRouted = type) else it.copy(trackRouted = type) }
    }

    private fun releaseStreams() {
        runCatching { record?.release() }
        runCatching { track?.release() }
        record = null
        track = null
    }
}
