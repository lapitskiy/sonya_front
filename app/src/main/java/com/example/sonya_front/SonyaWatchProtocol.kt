package com.example.sonya_front

import java.util.UUID

object SonyaWatchProtocol {
    const val TAG = "SonyaFrontWatch"

    const val DEVICE_NAME = "SONYA-WATCH"

    val SERVICE_UUID: UUID = UUID.fromString("f0debc9a-7856-3412-7856-341278563412")
    val RX_UUID: UUID = UUID.fromString("f0debc9a-7956-3412-7856-341278563412") // write
    val TX_UUID: UUID = UUID.fromString("f0debc9a-7a56-3412-7856-341278563412") // notify

    const val EVT_WAKE: Int = 0x01
    const val EVT_REC_START: Int = 0x02
    const val EVT_REC_END: Int = 0x03
    const val AUDIO_CHUNK: Int = 0x10
    const val EVT_ERROR: Int = 0x11
    const val AUDIO_DATA: Int = 0x12

    const val WAV_SAMPLE_RATE = 16_000
    const val WAV_CHANNELS = 1
    const val WAV_BITS_PER_SAMPLE = 16
}

data class SonyaWatchFrame(
    val type: Int,
    val seq: Int,
    val payload: ByteArray,
)

/**
 * Stream parser for frames:
 * [type:uint8][seq:uint16 LE][len:uint16 LE][payload]
 *
 * Note: BLE notifications may split/merge frames arbitrarily, so we reassemble from a byte stream.
 */
class SonyaWatchFrameParser(
    private val maxPayloadLen: Int = 16_384,
) {
    private var buf = ByteArray(0)

    fun push(bytes: ByteArray): List<SonyaWatchFrame> {
        if (bytes.isEmpty()) return emptyList()
        buf = buf + bytes

        val out = ArrayList<SonyaWatchFrame>(4)
        while (true) {
            if (buf.size < 5) break

            val type = buf[0].toInt() and 0xFF
            val seq = u16le(buf[1], buf[2])
            val len = u16le(buf[3], buf[4])

            if (len > maxPayloadLen) {
                // Corrupted stream; drop everything to avoid unbounded memory growth.
                buf = ByteArray(0)
                break
            }

            val frameSize = 5 + len
            if (buf.size < frameSize) break

            val payload = if (len == 0) ByteArray(0) else buf.copyOfRange(5, 5 + len)
            out.add(SonyaWatchFrame(type = type, seq = seq, payload = payload))

            buf = if (buf.size == frameSize) ByteArray(0) else buf.copyOfRange(frameSize, buf.size)
        }
        return out
    }

    private fun u16le(b0: Byte, b1: Byte): Int {
        return (b0.toInt() and 0xFF) or ((b1.toInt() and 0xFF) shl 8)
    }
}

