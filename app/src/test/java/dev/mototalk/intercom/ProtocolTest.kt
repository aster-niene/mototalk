package dev.mototalk.intercom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProtocolTest {

    @Test
    fun audioPacketRoundTrip() {
        val pcm = ShortArray(320) { (it * 37 - 5000).toShort() }
        val bytes = Protocol.encode(MessageType.AUDIO, 0xFFFF_FFFEL, 123_456L, Protocol.FLAG_SPEECH, Protocol.pcmToBytes(pcm))
        assertEquals(Protocol.HEADER_BYTES + 640, bytes.size)
        val p = Protocol.decode(bytes)!!
        assertEquals(MessageType.AUDIO, p.type)
        assertEquals(0xFFFF_FFFEL, p.seq)
        assertEquals(123_456L, p.timestampMs)
        assertTrue(p.speech)
        assertArrayEquals(pcm, Protocol.bytesToPcm(p.payload))
    }

    @Test
    fun headerIsBigEndianAsSpecified() {
        val bytes = Protocol.encode(MessageType.PING, seq = 1, timestampMs = 2)
        assertArrayEquals(byteArrayOf(1, 2, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0), bytes)
    }

    @Test
    fun malformedPacketsAreRejected() {
        assertNull(Protocol.decode(ByteArray(5)))
        val ok = Protocol.encode(MessageType.BYE, 0, 0)
        assertNull(Protocol.decode(ok.copyOf().also { it[0] = 9 })) // other version
        assertNull(Protocol.decode(ok.copyOf().also { it[1] = 99 })) // unknown type
        assertNull(Protocol.decode(ok + byteArrayOf(0))) // length mismatch
    }

    @Test
    fun rttWrapsAround32Bits() {
        assertEquals(30L, Protocol.elapsedU32(now = 10L, then = 0xFFFF_FFECL))
    }

    @Test
    fun helloRoundTrip() {
        val hello = Hello(UUID.randomUUID(), "Ivan's S24", LocalAudio.READY)
        assertEquals(hello, Protocol.decodeHello(Protocol.encodeHello(hello)))
    }

    @Test
    fun stateRoundTrip() {
        for (s in LocalAudio.entries) assertEquals(s, Protocol.decodeState(Protocol.encodeState(s)))
    }

    @Test
    fun endpointInfoFitsAndTruncatesWithoutBreakingCharacters() {
        val id = UUID.randomUUID()
        val info = Protocol.encodeEndpointInfo(id, "Мотоциклист") // Cyrillic: 2 bytes per letter
        assertTrue(info.size <= 32)
        val (decodedId, name) = Protocol.decodeEndpointInfo(info)!!
        assertEquals(id, decodedId)
        assertEquals("Мотоцик", name) // 7 whole letters = 14 bytes ≤ 15
    }

    @Test
    fun foreignEndpointInfoIsIgnored() {
        assertNull(Protocol.decodeEndpointInfo(null))
        assertNull(Protocol.decodeEndpointInfo(byteArrayOf(1, 2, 3)))
    }
}
