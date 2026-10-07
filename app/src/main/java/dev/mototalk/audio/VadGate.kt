package dev.mototalk.audio

/**
 * Sender-side voice gate (POC requirements FR-6), one 20 ms frame at a time on the audio I/O thread.
 *
 * - Gate mode: nothing is sent while nobody speaks. At speech onset the last [prerollFrames] frames go
 *   out first (so the first syllable is not cut), then frames while speech lasts plus [hangoverFrames];
 *   one final frame with speech = false closes the talkspurt.
 * - [alwaysTransmit]: every frame is sent; the speech flag still carries the VAD decision (for ducking).
 */
class VadGate(
    thresholdDb: Double = 12.0,
    private val hangoverFrames: Int = 25, // 500 ms
    private val prerollFrames: Int = PREROLL_FRAMES,
    sampleRate: Int = 16_000,
) {

    companion object {
        /**
         * 200 ms: the detector needs at least 8 loud frames (160 ms) to report speech, so a shorter
         * pre-roll would cut the start of every phrase. The receiver's playout buffer must absorb this burst.
         */
        const val PREROLL_FRAMES = 10
    }

    class Out(val samples: ShortArray, val speech: Boolean)

    @Volatile var alwaysTransmit = false

    var thresholdDb: Double
        get() = detector.thresholdDb
        set(value) {
            detector.thresholdDb = value
        }

    /** Speech decision including hangover; read from other threads for the UI. */
    @Volatile var active = false
        private set

    /** Last frame level and noise floor (dBFS, high-passed), for diagnostics. */
    @Volatile var lastLevelDb = Double.NaN
        private set
    val floorDb: Double get() = detector.floorDb

    private val highPass = HighPass(sampleRate, 300.0)
    private val detector = VoiceDetector(thresholdDb)
    private val preroll = ArrayDeque<ShortArray>()
    private var hang = 0

    fun reset() {
        detector.reset()
        preroll.clear()
        hang = 0
        active = false
    }

    /** Frames to transmit for this input frame, oldest first (may be empty). */
    fun process(frame: ShortArray, count: Int = frame.size): List<Out> {
        val copy = frame.copyOf(count)
        val level = highPass.levelDbfs(frame, count)
        lastLevelDb = level
        val voice = detector.process(level)
        if (voice) hang = hangoverFrames else if (hang > 0) hang--
        val nowActive = voice || hang > 0

        val out = ArrayList<Out>(prerollFrames + 1)
        if (alwaysTransmit) {
            out += Out(copy, nowActive)
        } else if (nowActive && !active) {
            for (f in preroll) out += Out(f, true)
            out += Out(copy, true)
        } else if (nowActive) {
            out += Out(copy, true)
        } else if (active) {
            out += Out(copy, false)
        }

        // Pre-roll holds only frames that were not sent, so an onset never repeats audio.
        if (nowActive) {
            preroll.clear()
        } else {
            preroll.addLast(copy)
            if (preroll.size > prerollFrames) preroll.removeFirst()
        }
        active = nowActive
        return out
    }
}
