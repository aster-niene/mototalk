package dev.mototalk.intercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlayoutBufferTest {

    private fun frame(v: Int) = ShortArray(320) { v.toShort() }

    @Test
    fun waitsForTargetDepthBeforePlaying() {
        val b = PlayoutBuffer(targetFrames = 3)
        b.offer(0, frame(0), true)
        b.offer(1, frame(1), true)
        assertNull(b.poll())
        b.offer(2, frame(2), true)
        assertEquals(0, b.poll()!![0].toInt())
        assertEquals(1, b.stats().talkspurts)
    }

    @Test
    fun underrunCountsOnlyInsideSpeech() {
        val b = PlayoutBuffer(targetFrames = 1)
        b.offer(0, frame(0), true)
        assertNotNull(b.poll())
        assertNull(b.poll()) // speech frame then nothing: underrun
        assertEquals(1, b.stats().underruns)

        b.offer(1, frame(1), false) // talkspurt closed by a non-speech frame
        assertNotNull(b.poll())
        assertNull(b.poll()) // pause between phrases: not an underrun
        assertEquals(1, b.stats().underruns)
    }

    @Test
    fun lateAndDuplicateFramesAreDropped() {
        val b = PlayoutBuffer(targetFrames = 1)
        b.offer(5, frame(5), true)
        b.offer(5, frame(5), true)
        b.offer(4, frame(4), true)
        val s = b.stats()
        assertEquals(1, s.duplicates)
        assertEquals(1, s.late)
        assertEquals(1, s.depth)
    }

    @Test
    fun overflowTrimsToTarget() {
        val b = PlayoutBuffer(targetFrames = 3, prerollFrames = 5)
        for (i in 0 until 12) b.offer(i.toLong(), frame(i), true) // max = 3 + 5 + 3 = 11
        val s = b.stats()
        assertEquals(3, s.depth)
        assertEquals(9, s.overflowDrops)
        assertEquals(9, b.poll()!![0].toInt()) // oldest kept frame
    }

    @Test
    fun gapsInSeqAreCountedAsLoss() {
        val b = PlayoutBuffer()
        b.offer(0, frame(0), true)
        b.offer(3, frame(3), true)
        assertEquals(2, b.stats().gaps)
    }

    @Test
    fun resetAcceptsSeqFromZeroAgain() {
        val b = PlayoutBuffer(targetFrames = 1)
        b.offer(100, frame(1), true)
        b.poll()
        b.reset()
        b.offer(0, frame(2), true)
        assertEquals(2, b.poll()!![0].toInt())
    }
}
