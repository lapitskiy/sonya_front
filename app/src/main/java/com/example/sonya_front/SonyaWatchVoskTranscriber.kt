package com.example.sonya_front

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

object SonyaWatchVoskTranscriber {
    private const val TAG = "SonyaWatchVosk"
    private const val MODEL_ASSET_DIR = "vosk-model-small-ru-0.22"
    private const val SAMPLE_RATE = 16000.0f

    @Volatile private var model: Model? = null
    @Volatile private var modelDir: File? = null

    /**
     * Transcribe PCM16LE mono 16kHz audio bytes into text (offline, Vosk).
     *
     * @return recognized text (may be blank).
     */
    fun transcribePcm16leMono16k(context: Context, pcmBytes: ByteArray): String {
        if (pcmBytes.isEmpty()) return ""

        val m = ensureModelLoaded(context.applicationContext)
        Recognizer(m, SAMPLE_RATE).use { rec ->
            rec.setWords(false)
            val chunk = 4000
            val buf = ByteArray(chunk)
            var off = 0
            while (off < pcmBytes.size) {
                val n = (pcmBytes.size - off).coerceAtMost(chunk)
                System.arraycopy(pcmBytes, off, buf, 0, n)
                rec.acceptWaveForm(buf, n)
                off += n
            }
            val json = rec.finalResult
            return extractText(json).orEmpty()
        }
    }

    @Synchronized
    private fun ensureModelLoaded(context: Context): Model {
        model?.let { return it }
        val dir = ensureModelDir(context)
        val m = Model(dir.absolutePath)
        model = m
        return m
    }

    @Synchronized
    private fun ensureModelDir(context: Context): File {
        modelDir?.let { if (it.exists() && it.isDirectory) return it }

        val dst = File(context.filesDir, MODEL_ASSET_DIR)
        if (dst.exists() && dst.isDirectory && dst.list()?.isNotEmpty() == true) {
            modelDir = dst
            return dst
        }

        // Ensure model exists in assets (clear error otherwise).
        val top = try { context.assets.list(MODEL_ASSET_DIR) } catch (_: Throwable) { null }
        require(top != null) { "Vosk model not found in assets: '$MODEL_ASSET_DIR'" }

        Log.i(TAG, "Copying Vosk model assets to ${dst.absolutePath} ...")
        copyAssetDirRecursively(context, MODEL_ASSET_DIR, dst)
        modelDir = dst
        return dst
    }

    private fun copyAssetDirRecursively(context: Context, assetPath: String, dst: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // file
            dst.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dst).use { output -> input.copyTo(output) }
            }
            return
        }

        dst.mkdirs()
        for (name in children) {
            val childAssetPath = "$assetPath/$name"
            val childDst = File(dst, name)
            copyAssetDirRecursively(context, childAssetPath, childDst)
        }
    }

    private fun extractText(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(json)
            obj.optString("text", "").trim()
        } catch (_: Throwable) {
            null
        }
    }
}

