package dev.mototalk.helmet

import dev.mototalk.intercom.MessageType
import dev.mototalk.intercom.Packet
import dev.mototalk.intercom.Protocol

/**
 * Cuts the Bluetooth serial byte stream from the helmet unit into MotoTalk frames: the §5 header
 * carries the payload length. Bytes that cannot start a frame are skipped one at a time, so the
 * reader falls back into step after a lost chunk.
 */
class FrameReader(private val maxPayload: Int = 2048) {

    private var buf = ByteArray(8192)
    private var have = 0

    /** Bytes skipped to find the next frame. */
    var badBytes = 0L
        private set

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<Packet> {
        if (have + count > buf.size) buf = buf.copyOf(maxOf(have + count, buf.size * 2))
        System.arraycopy(bytes, 0, buf, have, count)
        have += count

        val packets = mutableListOf<Packet>()
        var pos = 0
        while (have - pos >= Protocol.HEADER_BYTES) {
            val version = buf[pos].toInt() and 0xFF
            val type = buf[pos + 1].toInt() and 0xFF
            val len = (buf[pos + 11].toInt() and 0xFF shl 8) or (buf[pos + 12].toInt() and 0xFF)
            if (version != Protocol.VERSION || MessageType.of(type) == null || len > maxPayload) {
                badBytes++
                pos++
                continue
            }
            val total = Protocol.HEADER_BYTES + len
            if (have - pos < total) break
            Protocol.decode(buf.copyOfRange(pos, pos + total))?.let(packets::add)
            pos += total
        }
        System.arraycopy(buf, pos, buf, 0, have - pos)
        have -= pos
        return packets
    }
}

/** The helmet unit's once-a-second counters (firmware/h0_radio_test): a flat JSON object of integers. */
object EspStats {
    private val FIELD = Regex("\"(\\w+)\":(-?\\d+)")

    fun parse(payload: ByteArray): Map<String, Long> =
        FIELD.findAll(String(payload, Charsets.US_ASCII)).associate { it.groupValues[1] to it.groupValues[2].toLong() }
}
