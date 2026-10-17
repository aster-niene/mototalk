package dev.mototalk.helmet

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.intercom.MessageType
import dev.mototalk.intercom.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * H0 radio test (MotoTalk_Helmet_Unit.md): while the phone plays music to the ESP32 over A2DP,
 * send voice-sized frames over Bluetooth serial at rising bitrates and see whether the music
 * stutters. The unit echoes every frame and reports its counters once a second.
 */
object H0Test {

    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val DEVICE_NAME = "MotoTalk-H0"

    /** kbit/s each way; 0 = music only, the baseline. */
    val LADDER = listOf(0, 64, 128, 256, 512)
    const val STEP_SECONDS = 30
    private const val FRAME_MS = 20
    private const val SETTLE_MS = 1_000L // let the last echoes of a rung come back

    enum class Phase { IDLE, CONNECTING, RUNNING, DONE, FAILED }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val message: String? = null,
        val step: Int = -1,
        val secondsLeft: Int = 0,
        val lastRttMs: Long? = null,
        val esp: Map<String, Long> = emptyMap(),
        val results: List<StepResult> = emptyList(),
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopRequested = false
    @Volatile private var latestEsp: Map<String, Long>? = null
    @Volatile private var current: StepCollector? = null
    private val collectors = CopyOnWriteArrayList<StepCollector>()
    private var thread: Thread? = null

    @Synchronized
    fun start(context: Context) {
        if (thread?.isAlive == true) return
        stopRequested = false
        latestEsp = null
        current = null
        collectors.clear()
        _ui.value = Ui(phase = Phase.CONNECTING)
        val app = context.applicationContext
        thread = Thread({ run(app) }, "h0-test").apply { start() }
    }

    fun stop() {
        stopRequested = true
        closeSocket()
    }

    /** One tap while listening to the board's DAC output. */
    fun heardStutter() {
        val c = current ?: return
        c.heardStutter()
        DiagnosticsLog.event("h0_heard_stutter", mapOf("kbps" to c.kbps))
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime() and 0xFFFF_FFFFL

    @SuppressLint("MissingPermission") // checked below
    private fun run(context: Context) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return fail("Нет разрешения Bluetooth: нажми Grant permissions.")
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) return fail("Bluetooth выключен.")
            val device = adapter.bondedDevices.firstOrNull { it.name?.startsWith(DEVICE_NAME) == true }
                ?: return fail("Сначала соедини телефон с $DEVICE_NAME в настройках Bluetooth.")

            DiagnosticsLog.event("h0_connecting", mapOf("device" to device.name, "ladder" to LADDER, "stepSeconds" to STEP_SECONDS))
            val t0 = SystemClock.elapsedRealtime()
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = s
            s.connect()
            DiagnosticsLog.event("h0_connected", mapOf("ms" to SystemClock.elapsedRealtime() - t0))
            Thread({ readLoop(s.inputStream) }, "h0-rx").apply { isDaemon = true }.start()

            val results = ladder(s)
            if (stopRequested) {
                _ui.update { it.copy(phase = Phase.IDLE, message = "Остановлено.") }
                DiagnosticsLog.event("h0_stopped", mapOf("steps" to results.size))
                return
            }
            val best = bestKbps(results)
            val message = when {
                results.none { it.musicPlayed } -> "Музыка не играла. Включи Spotify на $DEVICE_NAME и запусти ещё раз."
                best == null -> "Ни на одной ступени музыка не прошла без перебоев."
                else -> "Голос до $best кбит/с в каждую сторону не мешает музыке."
            }
            _ui.update { it.copy(phase = Phase.DONE, message = message, step = -1) }
            DiagnosticsLog.event("h0_done", mapOf("bestKbps" to best, "steps" to results.size))
        } catch (e: IOException) {
            if (stopRequested) {
                _ui.update { it.copy(phase = Phase.IDLE, message = "Остановлено.") }
            } else {
                fail("Связь с $DEVICE_NAME потеряна: ${e.message}. Плата включена и рядом?")
            }
        } catch (e: SecurityException) {
            fail("Нет разрешения Bluetooth: ${e.message}")
        } finally {
            closeSocket()
            current = null
        }
    }

    private fun ladder(s: BluetoothSocket): List<StepResult> {
        val out = s.outputStream
        val results = mutableListOf<StepResult>()
        var seq = 0L
        val frameNs = FRAME_MS * 1_000_000L
        for ((index, kbps) in LADDER.withIndex()) {
            if (stopRequested) break
            val c = StepCollector(kbps, seq, latestEsp)
            collectors += c
            current = c
            _ui.update { it.copy(phase = Phase.RUNNING, step = index, secondsLeft = STEP_SECONDS) }
            DiagnosticsLog.event("h0_step_start", mapOf("kbps" to kbps, "seq" to seq))

            val payload = ByteArray(kbps * FRAME_MS / 8) // kbit/s × 20 ms = bits → bytes
            val start = System.nanoTime()
            val end = start + STEP_SECONDS * 1_000_000_000L
            var next = start
            var shownLeft = STEP_SECONDS
            while (!stopRequested) {
                val now = System.nanoTime()
                if (now >= end) break
                val left = ((end - now) / 1_000_000_000L).toInt() + 1
                if (left != shownLeft) {
                    shownLeft = left
                    _ui.update { it.copy(secondsLeft = left) }
                }
                if (kbps == 0) {
                    Thread.sleep(200)
                    continue
                }
                val wait = next - now
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    continue
                }
                out.write(Protocol.encode(MessageType.AUDIO, seq, nowMs(), 0, payload))
                seq++
                c.onSent()
                next += frameNs
                // A blocked write means the link is full: a late voice frame is worthless, skip it.
                val behind = System.nanoTime() - next
                if (behind >= frameNs) {
                    val skipped = behind / frameNs
                    c.onLate(skipped.toInt())
                    next += skipped * frameNs
                }
            }
            if (stopRequested) break
            Thread.sleep(SETTLE_MS)
            val r = c.result()
            results += r
            DiagnosticsLog.event("h0_step", r.toLog())
            _ui.update { it.copy(results = results.toList()) }
        }
        return results
    }

    private fun readLoop(input: InputStream) {
        val reader = FrameReader()
        val buf = ByteArray(4096)
        var lastRtt: Long? = null
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (p in reader.feed(buf, n)) {
                    when (p.type) {
                        MessageType.AUDIO -> {
                            val rtt = Protocol.elapsedU32(nowMs(), p.timestampMs)
                            collectors.lastOrNull { it.startSeq <= p.seq }?.onEcho(rtt)
                            lastRtt = rtt
                        }
                        MessageType.STATE -> {
                            val stats = EspStats.parse(p.payload)
                            latestEsp = stats
                            current?.onEsp(stats)
                            DiagnosticsLog.event("h0_esp", stats + ("rttMs" to (lastRtt ?: -1L)) + ("badBytes" to reader.badBytes))
                            _ui.update { it.copy(esp = stats, lastRttMs = lastRtt) }
                        }
                        else -> Unit
                    }
                }
            }
        } catch (_: IOException) {
            // Socket closed: the sender notices on its next write.
        }
    }

    private fun fail(message: String) {
        _ui.update { it.copy(phase = Phase.FAILED, message = message, step = -1) }
        DiagnosticsLog.event("h0_failed", mapOf("reason" to message))
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}
