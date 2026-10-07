package dev.mototalk.intercom

/**
 * Minimal playout buffer for the partner's voice (POC requirements FR-5).
 * Frames are offered from the network callback thread and polled every 20 ms by the audio I/O thread.
 *
 * - Playback starts (re-primes) only once [targetFrames] are queued: at every talkspurt and after an underrun.
 * - Late and duplicate frames (by seq) are dropped.
 * - Overflow: more than target + pre-roll + 3 frames queued → drop the oldest down to target
 *   (bounds latency after bursts and absorbs clock drift).
 * - An underrun counts only if the last played frame carried speech: gaps between phrases are not losses.
 */
class PlayoutBuffer(
    @Volatile var targetFrames: Int = 3,
    private val prerollFrames: Int = dev.mototalk.audio.VadGate.PREROLL_FRAMES,
) {

    data class Stats(
        val depth: Int,
        val played: Long,
        val underruns: Long,
        val late: Long,
        val duplicates: Long,
        val overflowDrops: Long,
        val gaps: Long,
        val talkspurts: Long,
    )

    private class Frame(val seq: Long, val samples: ShortArray, val speech: Boolean)

    private val queue = ArrayDeque<Frame>()
    private var lastQueuedSeq = -1L
    private var nextSeq = -1L
    private var playing = false
    private var lastPlayedSpeech = false
    private var played = 0L
    private var underruns = 0L
    private var late = 0L
    private var duplicates = 0L
    private var overflowDrops = 0L
    private var gaps = 0L
    private var talkspurts = 0L

    @Synchronized
    fun offer(seq: Long, samples: ShortArray, speech: Boolean) {
        if (seq == lastQueuedSeq) {
            duplicates++
            return
        }
        if (seq < lastQueuedSeq || (nextSeq >= 0 && seq < nextSeq)) {
            late++
            return
        }
        if (lastQueuedSeq >= 0 && seq > lastQueuedSeq + 1) gaps += seq - lastQueuedSeq - 1
        lastQueuedSeq = seq
        queue.addLast(Frame(seq, samples, speech))
        if (queue.size > targetFrames + prerollFrames + 3) {
            while (queue.size > targetFrames) {
                queue.removeFirst()
                overflowDrops++
            }
        }
    }

    /** Next frame to play, or null for silence. */
    @Synchronized
    fun poll(): ShortArray? {
        if (!playing) {
            if (queue.size < targetFrames.coerceAtLeast(1)) return null
            playing = true
            talkspurts++
        }
        val frame = queue.removeFirstOrNull()
        if (frame == null) {
            playing = false
            if (lastPlayedSpeech) underruns++
            lastPlayedSpeech = false
            return null
        }
        nextSeq = frame.seq + 1
        lastPlayedSpeech = frame.speech
        played++
        return frame.samples
    }

    /** New connection: the sender restarts its seq at 0 (§5). */
    @Synchronized
    fun reset() {
        queue.clear()
        lastQueuedSeq = -1L
        nextSeq = -1L
        playing = false
        lastPlayedSpeech = false
    }

    @Synchronized
    fun stats() = Stats(queue.size, played, underruns, late, duplicates, overflowDrops, gaps, talkspurts)
}
