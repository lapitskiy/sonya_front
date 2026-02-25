package com.sonya.companion

import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

object BackendUploader {
    /**
     * Upload WAV as raw body (Content-Type: audio/wav).
     * Backend URL is configurable in UI. Server should accept raw WAV bytes.
     */
    fun postWav(url: String, wavBytes: ByteArray, timeoutMs: Int = 15_000): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "audio/wav")
            setRequestProperty("Content-Length", wavBytes.size.toString())
        }
        BufferedOutputStream(conn.outputStream).use { it.write(wavBytes) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to body
    }
}

