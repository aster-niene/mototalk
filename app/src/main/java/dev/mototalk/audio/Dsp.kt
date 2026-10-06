package dev.mototalk.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

object Dsp {

    const val SILENCE_DBFS = -120.0

    /** RMS level of PCM16 samples in dBFS (a full-scale square wave is 0 dBFS). */
    fun rmsDbfs(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return SILENCE_DBFS
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return toDbfs(sqrt(sum / count))
    }

    /** Peak level of PCM16 samples in dBFS. */
    fun peakDbfs(samples: ShortArray, count: Int = samples.size): Double {
        var peak = 0
        for (i in 0 until count) peak = maxOf(peak, abs(samples[i].toInt()))
        return toDbfs(peak.toDouble())
    }

    fun hasSignal(samples: ShortArray, count: Int = samples.size): Boolean {
        for (i in 0 until count) if (samples[i].toInt() != 0) return true
        return false
    }

    private fun toDbfs(amplitude: Double): Double =
        if (amplitude <= 0.0) SILENCE_DBFS else (20 * log10(amplitude / 32768.0)).coerceAtLeast(SILENCE_DBFS)
}
