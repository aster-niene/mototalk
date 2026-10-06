package dev.mototalk.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {

    @Test
    fun headerDescribes16kMonoPcm16() {
        val h = ByteBuffer.wrap(Wav.header(16_000, 1, 640)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(h.array(), 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 640, h.getInt(4))
        assertEquals("WAVE", String(h.array(), 8, 4, Charsets.US_ASCII))
        assertEquals(1, h.getShort(20).toInt()) // PCM
        assertEquals(1, h.getShort(22).toInt()) // mono
        assertEquals(16_000, h.getInt(24))
        assertEquals(32_000, h.getInt(28)) // byte rate
        assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", String(h.array(), 36, 4, Charsets.US_ASCII))
        assertEquals(640, h.getInt(40))
    }

    @Test
    fun writesSamplesLittleEndian() {
        val file = File.createTempFile("wav", ".wav").apply { deleteOnExit() }
        Wav.write(file, 16_000, shortArrayOf(1, -2, 0x1234))
        val bytes = file.readBytes()
        assertEquals(44 + 6, bytes.size)
        val data = ByteBuffer.wrap(bytes, 44, 6).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, data.short.toInt())
        assertEquals(-2, data.short.toInt())
        assertEquals(0x1234, data.short.toInt())
    }
}
