package dev.mototalk.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mototalk.audio.AudioObserver
import dev.mototalk.audio.AudioSession
import dev.mototalk.bluetooth.RadioObserver
import dev.mototalk.diag.DeviceInfo
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.diag.Preflight
import dev.mototalk.diag.ScreenObserver
import dev.mototalk.intercom.IntercomSession
import dev.mototalk.intercom.ServiceError
import dev.mototalk.intercom.SessionKind
import dev.mototalk.intercom.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Owns the ride session (POC requirements FR-8). Audio, transport, reconnect and the state machine
 * live here; the Activity only observes [SessionStore].
 *
 * Rules that keep the microphone alive in the background (Android 14+ while-in-use):
 * - started only from a visible Activity via [start];
 * - startForeground() is called exactly once per session, never again until Stop;
 * - the notification is updated with NotificationManager.notify(), not startForeground().
 *
 * M1: local audio (route, loopback, duck test, recording, calls). Nearby arrives in M2.
 */
class RideService : Service() {

    companion object {
        private const val ACTION_START = "dev.mototalk.action.START"
        private const val ACTION_STOP = "dev.mototalk.action.STOP"
        private const val ACTION_DUCK_TEST = "dev.mototalk.action.DUCK_TEST"
        private const val ACTION_RECORD = "dev.mototalk.action.RECORD"
        private const val ACTION_CONNECT = "dev.mototalk.action.CONNECT"
        private const val ACTION_ACCEPT = "dev.mototalk.action.ACCEPT"
        private const val ACTION_REJECT = "dev.mototalk.action.REJECT"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ENDPOINT = "endpointId"

        /** Observers outlive Stop by this long: SCO-down / A2DP-resume events arrive after release (checklist M5). */
        private const val OBSERVER_TAIL_MS = 4_000L

        /** Must be called while the app has a visible Activity. */
        fun start(context: Context, kind: SessionKind) {
            val intent = Intent(context, RideService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_KIND, kind.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, RideService::class.java).setAction(ACTION_STOP)

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        fun duckTest(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_DUCK_TEST))
        }

        fun recordSample(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_RECORD))
        }

        /** Connect to a rider found nearby (first pairing). */
        fun connect(context: Context, endpointId: String) = command(context, ACTION_CONNECT, endpointId)

        /** Accept / reject a first-time pairing after comparing the digits. */
        fun acceptPairing(context: Context, endpointId: String) = command(context, ACTION_ACCEPT, endpointId)

        fun rejectPairing(context: Context, endpointId: String) = command(context, ACTION_REJECT, endpointId)

        private fun command(context: Context, action: String, endpointId: String) {
            context.startService(Intent(context, RideService::class.java).setAction(action).putExtra(EXTRA_ENDPOINT, endpointId))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var inForeground = false
    private var audioSession: AudioSession? = null
    private var intercom: IntercomSession? = null
    private var audioObserver: AudioObserver? = null
    private var radioObserver: RadioObserver? = null
    private var screenObserver: ScreenObserver? = null
    private var observersTailPending = false
    private var notificationJob: Job? = null

    // stopSelf() without an id would also drop a START that is already queued and still owes
    // startForeground() — the system then crashes the app. Stop only if no newer start arrived.
    private var lastStartId = 0

    private val observersTailRunnable = Runnable {
        observersTailPending = false
        stopObservers()
        DiagnosticsLog.event("observers_stopped")
        stopSelf(lastStartId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                val kind = intent.getStringExtra(EXTRA_KIND)
                    ?.let { runCatching { SessionKind.valueOf(it) }.getOrNull() }
                    ?: SessionKind.RIDE
                handleStart(kind)
            }
            ACTION_STOP -> handleStop("user")
            ACTION_DUCK_TEST -> audioSession?.duckTest() ?: stopIfIdle()
            ACTION_RECORD -> audioSession?.recordSample() ?: stopIfIdle()
            ACTION_CONNECT -> intercom?.connect(intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()) ?: stopIfIdle()
            ACTION_ACCEPT -> intercom?.acceptPairing(intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()) ?: stopIfIdle()
            ACTION_REJECT -> intercom?.rejectPairing(intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()) ?: stopIfIdle()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(kind: SessionKind) {
        if (inForeground) {
            // Already foreground: the system does not require another startForeground() call.
            DiagnosticsLog.event("session_start_ignored", mapOf("kind" to kind, "reason" to "already_running"))
            return
        }
        if (observersTailPending) {
            handler.removeCallbacks(observersTailRunnable)
            observersTailPending = false
            stopObservers()
        }
        val initial = SessionState(running = true, kind = kind)
        try {
            ServiceCompat.startForeground(
                this,
                RideNotification.ID,
                RideNotification.build(this, initial),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            if (e !is SecurityException && e !is ForegroundServiceStartNotAllowedException) throw e
            DiagnosticsLog.event("fgs_start_failed", mapOf("kind" to kind, "error" to e.toString()))
            SessionStore.update("fgs_start_failed") {
                SessionState(error = ServiceError.FGS_START_DENIED, errorDetail = e.message)
            }
            stopSelf()
            return
        }
        inForeground = true

        DiagnosticsLog.event(
            "session_start",
            mapOf("kind" to kind, "device" to DeviceInfo.describe(), "preflight" to Preflight.read(this).toLog()),
        )
        SessionStore.update("session_start") { initial }

        notificationJob?.cancel()
        notificationJob = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            SessionStore.state.drop(1).collect { state ->
                if (state.running) nm.notify(RideNotification.ID, RideNotification.build(this@RideService, state))
            }
        }

        audioObserver = AudioObserver(this).also { it.start() }
        radioObserver = RadioObserver(this).also { it.start() }
        screenObserver = ScreenObserver(this).also { it.start() }
        val audio = AudioSession(this, kind).also { audioSession = it }
        // RIDE talks to the partner phone; LOOPBACK stays local.
        if (kind == SessionKind.RIDE) intercom = IntercomSession(this, audio).also { it.start() }
        audio.start()
    }

    private fun handleStop(reason: String) {
        if (!inForeground) {
            stopIfIdle()
            return
        }
        DiagnosticsLog.event("session_stop", mapOf("reason" to reason))
        inForeground = false
        notificationJob?.cancel()
        intercom?.stop()
        intercom = null
        audioSession?.stop()
        audioSession = null
        SessionStore.update("session_stop") { SessionState() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        observersTailPending = true
        handler.postDelayed(observersTailRunnable, OBSERVER_TAIL_MS)
    }

    private fun stopIfIdle() {
        if (!inForeground && !observersTailPending) stopSelf(lastStartId)
    }

    private fun stopObservers() {
        audioObserver?.stop()
        audioObserver = null
        radioObserver?.stop()
        radioObserver = null
        screenObserver?.stop()
        screenObserver = null
    }

    override fun onDestroy() {
        if (inForeground) {
            // Destroyed without Stop (e.g. killed by the system): record it, reset the shared state.
            DiagnosticsLog.event("session_stop", mapOf("reason" to "service_destroyed"))
            inForeground = false
            intercom?.stop()
            intercom = null
            audioSession?.stop()
            audioSession = null
            SessionStore.update("service_destroyed") { SessionState() }
        }
        handler.removeCallbacksAndMessages(null)
        stopObservers()
        scope.cancel()
        super.onDestroy()
    }
}
