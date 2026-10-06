package dev.mototalk.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mototalk.audio.AudioObserver
import dev.mototalk.bluetooth.RadioObserver
import dev.mototalk.diag.DeviceInfo
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.diag.Preflight
import dev.mototalk.intercom.ServiceError
import dev.mototalk.intercom.SessionKind
import dev.mototalk.intercom.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * M0: lifecycle, notification and diagnostics observers only. Audio routing arrives in M1, Nearby in M2.
 */
class RideService : Service() {

    companion object {
        private const val ACTION_START = "dev.mototalk.action.START"
        private const val ACTION_STOP = "dev.mototalk.action.STOP"
        private const val EXTRA_KIND = "kind"

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inForeground = false
    private var audioObserver: AudioObserver? = null
    private var radioObserver: RadioObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val kind = intent.getStringExtra(EXTRA_KIND)
                    ?.let { runCatching { SessionKind.valueOf(it) }.getOrNull() }
                    ?: SessionKind.RIDE
                handleStart(kind)
            }
            ACTION_STOP -> handleStop("user")
            else -> if (!inForeground) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(kind: SessionKind) {
        if (inForeground) {
            // Already foreground: the system does not require another startForeground() call.
            DiagnosticsLog.event("session_start_ignored", mapOf("kind" to kind, "reason" to "already_running"))
            return
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

        audioObserver = AudioObserver(this).also { it.start() }
        radioObserver = RadioObserver(this).also { it.start() }

        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            SessionStore.state.drop(1).collect { state ->
                if (state.running) nm.notify(RideNotification.ID, RideNotification.build(this@RideService, state))
            }
        }
    }

    private fun handleStop(reason: String) {
        if (inForeground) {
            DiagnosticsLog.event("session_stop", mapOf("reason" to reason))
            tearDown()
            SessionStore.update("session_stop") { SessionState() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun tearDown() {
        inForeground = false
        audioObserver?.stop()
        audioObserver = null
        radioObserver?.stop()
        radioObserver = null
    }

    override fun onDestroy() {
        if (inForeground) {
            // Destroyed without Stop (e.g. killed by the system): record it, reset the shared state.
            DiagnosticsLog.event("session_stop", mapOf("reason" to "service_destroyed"))
            tearDown()
            SessionStore.update("service_destroyed") { SessionState() }
        }
        scope.cancel()
        super.onDestroy()
    }
}
