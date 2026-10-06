package dev.mototalk.intercom

/** Session state as three independent axes (POC requirements FR-9). */
enum class Link { IDLE, SEARCHING, CONNECTING, CONNECTED, RECONNECTING }

enum class LocalAudio { OFF, ROUTING, READY, DEVICE_LOST, PAUSED_BY_CALL }

enum class RemoteAudio { UNKNOWN, READY, DEVICE_LOST, IN_CALL, STOPPED }

enum class SessionKind { RIDE, LOOPBACK }

/** Reasons a session did not start. */
enum class ServiceError { PERMISSION_MISSING, FGS_START_DENIED }

data class SessionState(
    val running: Boolean = false,
    val kind: SessionKind? = null,
    val link: Link = Link.IDLE,
    val localAudio: LocalAudio = LocalAudio.OFF,
    val remoteAudio: RemoteAudio = RemoteAudio.UNKNOWN,
    val error: ServiceError? = null,
    val errorDetail: String? = null,
) {
    /** Computed, never stored: AUDIO is sent and ducking is allowed only in this state. */
    val intercomActive: Boolean
        get() = link == Link.CONNECTED && localAudio == LocalAudio.READY && remoteAudio == RemoteAudio.READY
}

data class StateChange(val field: String, val from: Any?, val to: Any?)

/** Field-by-field difference, used to log every axis transition separately. */
fun SessionState.changesTo(next: SessionState): List<StateChange> = buildList {
    fun check(field: String, a: Any?, b: Any?) {
        if (a != b) add(StateChange(field, a, b))
    }
    check("running", running, next.running)
    check("kind", kind, next.kind)
    check("link", link, next.link)
    check("localAudio", localAudio, next.localAudio)
    check("remoteAudio", remoteAudio, next.remoteAudio)
    check("error", error, next.error)
    check("intercomActive", intercomActive, next.intercomActive)
}
