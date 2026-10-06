package dev.mototalk.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Logs screen off/on and unlock, so a locked-screen test (checklist M11) is visible in the log. */
class ScreenObserver(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> "screen_off"
                Intent.ACTION_SCREEN_ON -> "screen_on"
                Intent.ACTION_USER_PRESENT -> "screen_unlocked"
                else -> return
            }
            DiagnosticsLog.event(event)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // USER_PRESENT comes from SystemUI, not system_server: NOT_EXPORTED would drop it.
        // All three are protected broadcasts, so EXPORTED cannot be spoofed.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }
}
