package dev.mototalk.intercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {

    private val active = SessionState(
        running = true,
        kind = SessionKind.RIDE,
        link = Link.CONNECTED,
        localAudio = LocalAudio.READY,
        remoteAudio = RemoteAudio.READY,
    )

    @Test
    fun intercomActiveOnlyWhenAllThreeAxesReady() {
        assertTrue(active.intercomActive)
        assertFalse(active.copy(link = Link.RECONNECTING).intercomActive)
        assertFalse(active.copy(localAudio = LocalAudio.PAUSED_BY_CALL).intercomActive)
        assertFalse(active.copy(remoteAudio = RemoteAudio.IN_CALL).intercomActive)
        assertFalse(SessionState().intercomActive)
    }

    @Test
    fun changesListEachAxisSeparately() {
        val next = active.copy(link = Link.RECONNECTING, localAudio = LocalAudio.DEVICE_LOST)
        assertEquals(
            listOf(
                StateChange("link", Link.CONNECTED, Link.RECONNECTING),
                StateChange("localAudio", LocalAudio.READY, LocalAudio.DEVICE_LOST),
                StateChange("intercomActive", true, false),
            ),
            active.changesTo(next),
        )
    }

    @Test
    fun noChangesForEqualStates() {
        assertTrue(active.changesTo(active.copy()).isEmpty())
    }
}
