package dev.mototalk.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order Butterworth high-pass (RBJ biquad). Removes wind and engine rumble below the speech band
 * before measuring the level that drives voice detection.
 */
class HighPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.7071) {

    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val w0 = 2 * PI * cutoffHz / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        b0 = (1 + cosW) / 2 / a0
        b1 = -(1 + cosW) / a0
        b2 = b0
        a1 = -2 * cosW / a0
        a2 = (1 - alpha) / a0
    }

    /** Filters [count] samples (state carries over between calls) and returns the output level in dBFS. */
    fun levelDbfs(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return Dsp.SILENCE_DBFS
        var sum = 0.0
        for (i in 0 until count) {
            val x = samples[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            sum += y * y
        }
        return Dsp.amplitudeToDbfs(sqrt(sum / count))
    }
}
