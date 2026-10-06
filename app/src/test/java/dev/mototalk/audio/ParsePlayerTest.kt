package dev.mototalk.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ParsePlayerTest {

    @Test
    fun parsesSamsungPlaybackConfigurationString() {
        val raw = "AudioPlaybackConfiguration piid:22511 deviceIds:[] type:unknown u/pid:-1/-1 state:started " +
            "attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_UNKNOWN flags=0x800(FLAG_MUTE_HAPTIC)"
        assertEquals(ParsedPlayer(22511, "started"), parsePlayer(raw))
    }

    @Test
    fun missingFieldsAreNull() {
        assertEquals(ParsedPlayer(null, null), parsePlayer("something else"))
    }
}
