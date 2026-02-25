package com.sonya.companion

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    fun writePcmS16leMono16k(file: File, pcm: ByteArray) {
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()

        val dataSize = pcm.size
        val riffSize = 36 + dataSize

        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(leInt(riffSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))

            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(leInt(16)) // PCM header size
            out.write(leShort(1)) // PCM format
            out.write(leShort(channels.toShort()))
            out.write(leInt(sampleRate))
            out.write(leInt(byteRate))
            out.write(leShort(blockAlign))
            out.write(leShort(bitsPerSample.toShort()))

            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(leInt(dataSize))
            out.write(pcm)
        }
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun leShort(v: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}

