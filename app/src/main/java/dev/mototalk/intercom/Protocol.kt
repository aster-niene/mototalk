package dev.mototalk.intercom

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class MessageType(val code: Int) {
    AUDIO(1), PING(2), PONG(3), HELLO(4), STATE(5), BYE(6);

    companion object {
        fun of(code: Int): MessageType? = entries.firstOrNull { it.code == code }
    }
}

/** One message on the wire (POC requirements §5). [seq] and [timestampMs] are unsigned 32-bit. */
class Packet(
    val type: MessageType,
    val seq: Long,
    val timestampMs: Long,
    val flags: Int,
    val payload: ByteArray,
) {
    val speech: Boolean get() = flags and Protocol.FLAG_SPEECH != 0
}

data class Hello(val deviceId: UUID, val name: String, val localAudio: LocalAudio)

/**
 * Wire format (§5): 13-byte big-endian header `version u8 · type u8 · seq u32 · timestampMs u32 ·
 * flags u8 · payloadLen u16`, then the payload. Everything travels as Nearby BYTES payloads.
 */
object Protocol {

    const val VERSION = 1
    const val HEADER_BYTES = 13
    const val FLAG_SPEECH = 0x01
    private const val U32 = 0xFFFF_FFFFL
    private const val NAME_MAX_BYTES = 15

    fun encode(type: MessageType, seq: Long, timestampMs: Long, flags: Int = 0, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(VERSION.toByte())
            put(type.code.toByte())
            putInt((seq and U32).toInt())
            putInt((timestampMs and U32).toInt())
            put(flags.toByte())
            putShort(payload.size.toShort())
            put(payload)
        }.array()

    /** Null for anything that is not a well-formed packet of our version. */
    fun decode(bytes: ByteArray): Packet? {
        if (bytes.size < HEADER_BYTES) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (b.get().toInt() != VERSION) return null
        val type = MessageType.of(b.get().toInt() and 0xFF) ?: return null
        val seq = b.int.toLong() and U32
        val ts = b.int.toLong() and U32
        val flags = b.get().toInt() and 0xFF
        val len = b.short.toInt() and 0xFFFF
        if (bytes.size != HEADER_BYTES + len) return null
        return Packet(type, seq, ts, flags, bytes.copyOfRange(HEADER_BYTES, bytes.size))
    }

    /** Difference of two unsigned 32-bit millisecond clocks, e.g. RTT = now − echoed timestamp. */
    fun elapsedU32(now: Long, then: Long): Long = (now - then) and U32

    fun pcmToBytes(samples: ShortArray, count: Int = samples.size): ByteArray =
        ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until count) putShort(samples[i])
        }.array()

    fun bytesToPcm(bytes: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { b.short }
    }

    // HELLO payload: deviceId (16) · localAudio (u8) · name (UTF-8, rest of the payload)
    fun encodeHello(hello: Hello): ByteArray {
        val name = hello.name.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(17 + name.size).apply {
            putLong(hello.deviceId.mostSignificantBits)
            putLong(hello.deviceId.leastSignificantBits)
            put(hello.localAudio.ordinal.toByte())
            put(name)
        }.array()
    }

    fun decodeHello(payload: ByteArray): Hello? {
        if (payload.size < 17) return null
        val b = ByteBuffer.wrap(payload)
        val id = UUID(b.long, b.long)
        val audio = LocalAudio.entries.getOrNull(b.get().toInt()) ?: return null
        return Hello(id, String(payload, 17, payload.size - 17, Charsets.UTF_8), audio)
    }

    fun encodeState(localAudio: LocalAudio): ByteArray = byteArrayOf(localAudio.ordinal.toByte())

    fun decodeState(payload: ByteArray): LocalAudio? = payload.firstOrNull()?.let { LocalAudio.entries.getOrNull(it.toInt()) }

    // Nearby endpointInfo (FR-3): version (u8) · deviceId (16) · name (UTF-8, ≤ 15 bytes) — ≤ 32 bytes total.
    fun encodeEndpointInfo(deviceId: UUID, name: String): ByteArray {
        val nameBytes = truncateUtf8(name, NAME_MAX_BYTES)
        return ByteBuffer.allocate(17 + nameBytes.size).apply {
            put(VERSION.toByte())
            putLong(deviceId.mostSignificantBits)
            putLong(deviceId.leastSignificantBits)
            put(nameBytes)
        }.array()
    }

    fun decodeEndpointInfo(info: ByteArray?): Pair<UUID, String>? {
        if (info == null || info.size < 17 || info[0].toInt() != VERSION) return null
        val b = ByteBuffer.wrap(info, 1, 16)
        return UUID(b.long, b.long) to String(info, 17, info.size - 17, Charsets.UTF_8)
    }

    /** Cuts a string to at most [maxBytes] of UTF-8 without splitting a character. */
    fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
        var end = s.length
        while (s.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return s.substring(0, end).toByteArray(Charsets.UTF_8)
    }
}
