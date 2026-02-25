package com.example.sonya_front

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

data class SonyaWatchUploadResult(
    val httpCode: Int,
    val bodyPrefix: String,
)

object SonyaWatchUploader {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun uploadWav(url: String, wavFile: File): SonyaWatchUploadResult {
        val mediaType = "audio/wav".toMediaType()
        val body = wavFile.asRequestBody(mediaType)
        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val prefix = if (raw.length <= 300) raw else raw.substring(0, 300)
            return SonyaWatchUploadResult(httpCode = resp.code, bodyPrefix = prefix)
        }
    }
}

