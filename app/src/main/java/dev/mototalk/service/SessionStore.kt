package dev.mototalk.service

import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.intercom.SessionState
import dev.mototalk.intercom.changesTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide session state. RideService writes it, the UI and the notification observe it. */
object SessionStore {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun update(reason: String, transform: (SessionState) -> SessionState) {
        synchronized(this) {
            val old = _state.value
            val new = transform(old)
            if (new == old) return
            _state.value = new
            for (c in old.changesTo(new)) {
                DiagnosticsLog.event(
                    "state",
                    mapOf("field" to c.field, "from" to c.from, "to" to c.to, "reason" to reason),
                )
            }
        }
    }
}
