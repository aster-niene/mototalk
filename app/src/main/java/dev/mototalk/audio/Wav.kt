package dev.mototalk.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 WAV files for the "Record 10 s" check (POC requirements M2). */
object Wav {

    private const val HEADER_BYTES = 44

    fun header(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2) // byte rate
            putShort((channels * 2).toShort()) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()

    fun write(file: File, sampleRate: Int, samples: ShortArray, count: Int = samples.size) {
        val data = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) data.putShort(samples[i])
        BufferedOutputStream(FileOutputStream(file)).use {
            it.write(header(sampleRate, 1, count * 2))
            it.write(data.array())
        }
    }
}
