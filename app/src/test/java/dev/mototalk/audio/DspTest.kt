package dev.mototalk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DspTest {

    @Test
    fun silenceIsFloor() {
        val zeros = ShortArray(320)
        assertEquals(Dsp.SILENCE_DBFS, Dsp.rmsDbfs(zeros), 0.0)
        assertEquals(Dsp.SILENCE_DBFS, Dsp.peakDbfs(zeros), 0.0)
        assertFalse(Dsp.hasSignal(zeros))
    }

    @Test
    fun fullScaleSquareIsZeroDbfs() {
        val square = ShortArray(320) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertEquals(0.0, Dsp.rmsDbfs(square), 0.01)
        assertEquals(0.0, Dsp.peakDbfs(square), 0.01)
    }

    @Test
    fun fullScaleSineIsMinus3Dbfs() {
        val sine = ShortArray(16_000) { (32767 * sin(2 * PI * 1000 * it / 16_000)).toInt().toShort() }
        assertEquals(-3.01, Dsp.rmsDbfs(sine), 0.05)
    }

    @Test
    fun countLimitsTheWindow() {
        val samples = ShortArray(320).also { it[300] = 1000 }
        assertFalse(Dsp.hasSignal(samples, 100))
        assertTrue(Dsp.hasSignal(samples, 320))
    }
}
