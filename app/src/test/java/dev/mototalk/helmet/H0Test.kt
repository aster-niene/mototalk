package dev.mototalk.helmet

import dev.mototalk.intercom.MessageType
import dev.mototalk.intercom.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameReaderTest {

    // The test stream carries zero payloads, like H0Test sends: payload bytes then cannot pass for a header.
    private fun audio(seq: Long, size: Int) = Protocol.encode(MessageType.AUDIO, seq, 1000 + seq, 0, ByteArray(size))

    @Test
    fun framesSplitAcrossReadsComeOutWhole() {
        val stream = audio(1, 160) + audio(2, 160) + audio(3, 160)
        val reader = FrameReader()
        val out = listOf(stream.copyOfRange(0, 5), stream.copyOfRange(5, 200), stream.copyOfRange(200, stream.size))
            .flatMap { reader.feed(it) }
        assertEquals(listOf(1L, 2L, 3L), out.map { it.seq })
        assertTrue(out.all { it.payload.size == 160 })
        assertEquals(0, reader.badBytes)
    }

    @Test
    fun fallsBackIntoStepAfterALostChunk() {
        val reader = FrameReader()
        val broken = audio(1, 160).copyOfRange(5, 173) // a frame whose first bytes were lost
        val out = reader.feed(broken + audio(2, 160))
        assertEquals(listOf(2L), out.map { it.seq })
        assertTrue(reader.badBytes > 0)
    }

    @Test
    fun espStatsReadsTheFlatJson() {
        val json = """{"up":1234,"play":1,"fill_ms":-1,"under":3}""".toByteArray()
        assertEquals(mapOf("up" to 1234L, "play" to 1L, "fill_ms" to -1L, "under" to 3L), EspStats.parse(json))
    }
}

class StepCollectorTest {

    private fun esp(under: Long, rx: Long, play: Long = 1, gap: Long = 25, fill: Long = 90) =
        mapOf("under" to under, "drop" to 0L, "rx" to rx, "rx_gap" to 0L, "tx_drop" to 0L, "play" to play, "gap_ms" to gap, "fill_ms" to fill)

    @Test
    fun countsOnlyWhatHappenedDuringTheRung() {
        val c = StepCollector(kbps = 64, startSeq = 0, baseline = esp(under = 2, rx = 100))
        repeat(100) { c.onSent() }
        repeat(99) { c.onEcho(30L + it % 10) }
        c.onEsp(esp(under = 2, rx = 150, gap = 40, fill = 80))
        c.onEsp(esp(under = 3, rx = 199, gap = 70, fill = 20))
        val r = c.result()
        assertEquals(1, r.underflows)
        assertEquals(99, r.espRx)
        assertEquals(1.0, r.lossPct, 1e-9)
        assertEquals(70L, r.maxGapMs)
        assertEquals(20L, r.minFillMs)
        assertEquals(2, r.playSeconds)
        assertFalse("a stutter fails the rung", r.passed)
    }

    @Test
    fun cleanRungPassesAndIsTheBest() {
        fun rung(kbps: Int, under: Long): StepResult {
            val c = StepCollector(kbps, 0, esp(under = 0, rx = 0))
            repeat(1500) { c.onSent(); c.onEcho(40) }
            c.onEsp(esp(under = under, rx = 1500))
            return c.result()
        }
        val results = listOf(rung(0, 0), rung(64, 0), rung(128, 0), rung(256, 4))
        assertTrue(results[2].passed)
        assertFalse(results[3].passed)
        assertEquals(128, bestKbps(results))
    }

    @Test
    fun noMusicMeansNothingPasses() {
        val c = StepCollector(64, 0, null)
        repeat(10) { c.onSent(); c.onEcho(40) }
        c.onEsp(esp(under = 0, rx = 10, play = 0))
        assertFalse(c.result().passed)
        assertNull(bestKbps(listOf(c.result())))
    }

    @Test
    fun percentileIsNearestRank() {
        val sorted = (1L..100L).toList()
        assertEquals(50L, percentile(sorted, 50))
        assertEquals(95L, percentile(sorted, 95))
        assertEquals(7L, percentile(listOf(7L), 95))
        assertNull(percentile(emptyList(), 50))
    }
}
