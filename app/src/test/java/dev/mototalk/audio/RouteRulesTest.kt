package dev.mototalk.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRulesTest {

    @Test
    fun callModesPauseTheIntercom() {
        assertTrue(isCallMode(AudioManager.MODE_RINGTONE))
        assertTrue(isCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(isCallMode(AudioManager.MODE_CALL_SCREENING))
        assertTrue(isCallMode(AudioManager.MODE_CALL_REDIRECT))
        assertFalse(isCallMode(AudioManager.MODE_NORMAL))
        assertFalse(isCallMode(AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test
    fun retryBackoffCapsAtFiveSeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (0..4).map(::retryDelayMs))
    }
}
