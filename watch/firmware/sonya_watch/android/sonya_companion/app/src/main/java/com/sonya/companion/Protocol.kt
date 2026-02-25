package com.sonya.companion

data class Frame(
    val type: Int,
    val seq: Int,
    val len: Int,
    val payload: ByteArray
)

object Protocol {
    const val EVT_WAKE = 0x01
    const val EVT_REC_START = 0x02
    const val EVT_REC_END = 0x03
    const val AUDIO_CHUNK = 0x10
    const val EVT_ERROR = 0x11

    fun parseFrame(value: ByteArray): Frame? {
        if (value.size < 5) return null
        val type = value[0].toInt() and 0xFF
        val seq = (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        val len = (value[3].toInt() and 0xFF) or ((value[4].toInt() and 0xFF) shl 8)
        if (value.size < 5 + len) return null
        val payload = value.copyOfRange(5, 5 + len)
        return Frame(type, seq, len, payload)
    }
}

