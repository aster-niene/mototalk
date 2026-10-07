package dev.mototalk.audio

/**
 * Speech detection on a stream of 20 ms frame levels (dBFS, already high-passed).
 *
 * The noise floor is the minimum level over the last [floorFrames] frames (minimum statistics):
 * speech always has short dips between syllables, steady noise (wind, engine, music leaking into the
 * mic) does not, so the floor tracks the noise and speech stands out above it.
 * A frame is loud when it is [thresholdDb] above the floor; speech is on when at least [minLoudFrames]
 * of the last [windowFrames] frames are loud. Nothing is reported during the first [warmupFrames].
 *
 * Not thread-safe: feed it from one thread. [thresholdDb] may be changed from another thread.
 */
class VoiceDetector(
    @Volatile var thresholdDb: Double = 12.0,
    private val windowFrames: Int = 15, // 300 ms
    private val minLoudFrames: Int = 8, // 160 ms of it
    private val floorFrames: Int = 75, // 1.5 s
    private val warmupFrames: Int = 25, // 0.5 s
) {

    private val history = DoubleArray(floorFrames) { Double.NaN }
    private var historyPos = 0
    private val loud = BooleanArray(windowFrames)
    private var loudPos = 0
    private var loudCount = 0
    private var frames = 0

    /** Current noise floor in dBFS (NaN before the first frame). */
    var floorDb = Double.NaN
        private set

    fun reset() {
        history.fill(Double.NaN)
        loud.fill(false)
        historyPos = 0
        loudPos = 0
        loudCount = 0
        frames = 0
        floorDb = Double.NaN
    }

    /** Feeds one frame level; returns true while speech is detected. */
    fun process(levelDb: Double): Boolean {
        history[historyPos] = levelDb
        historyPos = (historyPos + 1) % floorFrames
        var min = Double.MAX_VALUE
        for (v in history) if (!v.isNaN() && v < min) min = v
        floorDb = min

        val isLoud = levelDb > floorDb + thresholdDb
        if (loud[loudPos]) loudCount--
        loud[loudPos] = isLoud
        if (isLoud) loudCount++
        loudPos = (loudPos + 1) % windowFrames

        frames++
        return frames > warmupFrames && loudCount >= minLoudFrames
    }
}
