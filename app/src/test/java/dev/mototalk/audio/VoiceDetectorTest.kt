package dev.mototalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDetectorTest {

    /** Feeds [levels] (one per 20 ms frame) and returns the detector output for each. */
    private fun VoiceDetector.run(levels: List<Double>) = levels.map(::process)

    @Test
    fun steadyNoiseIsNotSpeech() {
        val out = VoiceDetector().run(List(300) { -50.0 + (it % 3) }) // ±1 dB jitter, 6 s
        assertFalse(out.any { it })
    }

    @Test
    fun speechAboveBackgroundIsDetectedWithinAFewFrames() {
        val detector = VoiceDetector(thresholdDb = 12.0)
        detector.run(List(100) { -55.0 }) // 2 s of background
        // Syllables: 4 loud frames, 1 dip, repeated.
        val speech = List(30) { if (it % 5 == 4) -50.0 else -35.0 }
        val out = detector.run(speech)
        val firstOn = out.indexOf(true)
        assertTrue("detected at frame $firstOn", firstOn in 0..12) // within 240 ms
        assertEquals(-55.0, detector.floorDb, 0.01)
    }

    @Test
    fun quietSpeechBelowThresholdIsIgnored() {
        val detector = VoiceDetector(thresholdDb = 12.0)
        detector.run(List(100) { -55.0 })
        assertFalse(detector.run(List(30) { -47.0 }).any { it }) // only +8 dB
    }

    @Test
    fun floorFollowsRisingNoiseSoItStopsBeingSpeech() {
        val detector = VoiceDetector(thresholdDb = 12.0)
        detector.run(List(100) { -60.0 })
        // Wind picks up by 20 dB and stays: may trigger at first, must settle within the 1.5 s floor window.
        val out = detector.run(List(200) { -40.0 })
        assertFalse(out.takeLast(100).any { it })
    }

    @Test
    fun nothingDuringWarmup() {
        val detector = VoiceDetector(thresholdDb = 12.0)
        detector.process(-80.0)
        assertFalse(detector.run(List(20) { -20.0 }).any { it }) // frames 2..21 < warm-up of 25
    }

    @Test
    fun resetStartsOver() {
        val detector = VoiceDetector()
        detector.run(List(50) { -30.0 })
        detector.reset()
        assertTrue(detector.floorDb.isNaN())
    }
}
