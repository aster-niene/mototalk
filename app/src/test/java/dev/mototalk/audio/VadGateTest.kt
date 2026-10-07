package dev.mototalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VadGateTest {

    private var phase = 0.0

    /** 20 ms of a 1 kHz tone at [amplitude] (inside the speech band, passes the 300 Hz high-pass). */
    private fun tone(amplitude: Double): ShortArray = ShortArray(320) {
        val v = amplitude * sin(phase)
        phase += 2 * PI * 1000 / 16_000
        v.toInt().toShort()
    }

    private fun VadGate.feed(frames: Int, amplitude: Double) = (0 until frames).map { process(tone(amplitude)) }

    @Test
    fun silenceSendsNothingInGateMode() {
        val gate = VadGate()
        assertTrue(gate.feed(100, 30.0).all { it.isEmpty() })
    }

    @Test
    fun onsetSendsPrerollThenSpeechThenOneClosingFrame() {
        val gate = VadGate(hangoverFrames = 25, prerollFrames = 5)
        gate.feed(100, 30.0) // 2 s background
        val speech = gate.feed(30, 3_000.0)
        val onset = speech.indexOfFirst { it.isNotEmpty() }
        assertEquals("pre-roll + current frame at onset", 6, speech[onset].size)
        assertTrue(speech.drop(onset).flatten().all { it.speech })

        val tail = gate.feed(60, 30.0)
        val sentAfter = tail.flatten()
        // The detector's 15-frame window keeps "voice" a few frames after the tone stops, then the
        // 25-frame hangover runs, then exactly one closing frame.
        assertTrue("sent ${sentAfter.size}", sentAfter.size in 26..26 + 15)
        assertTrue(sentAfter.dropLast(1).all { it.speech })
        assertTrue(!sentAfter.last().speech)
    }

    @Test
    fun defaultPrerollIncludesTheFirstLoudFrame() {
        val gate = VadGate() // default pre-roll must cover the detector's onset latency
        gate.feed(100, 30.0)
        val marker: Short = 12_345
        val first = tone(3_000.0).also { it[0] = marker }
        val sent = (listOf(gate.process(first)) + gate.feed(20, 3_000.0)).flatten()
        assertTrue("the start of the phrase is not cut", sent.any { it.samples[0] == marker })
    }

    @Test
    fun alwaysTransmitSendsEveryFrameWithSpeechFlag() {
        val gate = VadGate().apply { alwaysTransmit = true }
        val quiet = gate.feed(100, 30.0)
        assertTrue(quiet.all { it.size == 1 && !it[0].speech })
        val loud = gate.feed(20, 3_000.0)
        assertTrue(loud.all { it.size == 1 })
        assertTrue(loud.last()[0].speech)
    }
}
