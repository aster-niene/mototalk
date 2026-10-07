package dev.mototalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HighPassTest {

    private fun sine(hz: Double, amplitude: Double = 10_000.0, samples: Int = 16_000) =
        ShortArray(samples) { (amplitude * sin(2 * PI * hz * it / 16_000)).toInt().toShort() }

    /** Level of the second half, after the filter has settled. */
    private fun filteredLevel(hz: Double): Double {
        val filter = HighPass(16_000, 300.0)
        val signal = sine(hz)
        filter.levelDbfs(signal, 8_000)
        return filter.levelDbfs(signal.copyOfRange(8_000, 16_000))
    }

    @Test
    fun speechBandPassesUnchanged() {
        assertEquals(Dsp.rmsDbfs(sine(1_000.0)), filteredLevel(1_000.0), 0.5)
    }

    @Test
    fun rumbleIsStronglyAttenuated() {
        // Wind / engine rumble at 50 Hz: a 2nd-order filter at 300 Hz gives about -31 dB.
        assertTrue(Dsp.rmsDbfs(sine(50.0)) - filteredLevel(50.0) > 25.0)
    }
}
