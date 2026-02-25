package com.example.sonya_front

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SonyaWatchWav {
    fun writePcm16leMono16kHzWav(
        dir: File,
        pcmS16Le: ByteArray,
        filenamePrefix: String = "sonya_watch",
    ): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "${filenamePrefix}_${ts}.wav")

        val sampleRate = SonyaWatchProtocol.WAV_SAMPLE_RATE
        val channels = SonyaWatchProtocol.WAV_CHANNELS
        val bitsPerSample = SonyaWatchProtocol.WAV_BITS_PER_SAMPLE

        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val dataSize = pcmS16Le.size
        val riffSize = 36 + dataSize

        FileOutputStream(outFile).use { fos ->
            // RIFF header
            fos.writeAscii("RIFF")
            fos.writeLe32(riffSize)
            fos.writeAscii("WAVE")

            // fmt chunk
            fos.writeAscii("fmt ")
            fos.writeLe32(16) // PCM chunk size
            fos.writeLe16(1) // audio format = PCM
            fos.writeLe16(channels)
            fos.writeLe32(sampleRate)
            fos.writeLe32(byteRate)
            fos.writeLe16(blockAlign)
            fos.writeLe16(bitsPerSample)

            // data chunk
            fos.writeAscii("data")
            fos.writeLe32(dataSize)
            fos.write(pcmS16Le)
        }

        return outFile
    }
}

private fun FileOutputStream.writeAscii(s: String) {
    write(s.toByteArray(Charsets.US_ASCII))
}

private fun FileOutputStream.writeLe16(v: Int) {
    write(byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte()))
}

private fun FileOutputStream.writeLe32(v: Int) {
    write(
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )
    )
}

