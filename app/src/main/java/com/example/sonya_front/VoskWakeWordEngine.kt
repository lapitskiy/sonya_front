package com.example.sonya_front

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline wake phrase detection using Vosk with a narrow grammar (keyword spotting-ish).
 *
 * Flow:
 * - Copy Vosk model dir from assets to internal storage (Vosk needs a real filesystem path)
 * - Start AudioRecord(16kHz mono)
 * - Feed PCM16 bytes into Recognizer(grammar)
 * - Trigger onWake() when recognized text contains the target phrase tokens.
 */
class VoskWakeWordEngine(
    private val context: Context,
    private val modelAssetDir: String = "vosk-model-small-ru-0.22",
    private val grammarPhrases: List<String> = listOf("соня приём", "соня прием"),
    private val minIntervalMsBetweenTriggers: Long = 2000L,
    private val onWake: () -> Unit,
) {
    private val engineVersionTag: String = "vosk-kw-2026-01-28-001"

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    private var lastTriggerAtMs: Long = 0L
    private var lastHeardLogAtMs: Long = 0L

    /**
     * @return true if the engine actually started (model loaded + AudioRecord running).
     */
    fun start(): Boolean {
        if (running.getAndSet(true)) return true

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("WAKEWORD", "RECORD_AUDIO permission missing; Vosk engine not started")
            running.set(false)
            return false
        }

        try {
            val modelDir = ensureModelDir()
            val m = Model(modelDir.absolutePath)
            model = m

            val grammarJson = buildGrammarJson(grammarPhrases)
            val rec = Recognizer(m, 16000.0f, grammarJson).apply {
                // We don't need words/confidences for wake spotting.
                setWords(false)
            }
            recognizer = rec

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate / 2)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBuf * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("WAKEWORD", "AudioRecord not initialized (Vosk)")
                stop()
                return false
            }

            Log.i("WAKEWORD", "VoskWakeWordEngine $engineVersionTag started; model=$modelAssetDir grammar=${grammarPhrases.joinToString()} ")

            thread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                runLoop(minBuf)
            }.apply { name = "VoskWakeWord"; start() }

            return true
        } catch (t: Throwable) {
            Log.e("WAKEWORD", "Failed to start VoskWakeWordEngine: ${t.message}", t)
            stop()
            return false
        }
    }

    fun stop() {
        running.set(false)
        try {
            thread?.join(500)
        } catch (_: Throwable) {
            // ignore
        } finally {
            thread = null
        }

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
            // ignore
        }
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
            // ignore
        } finally {
            audioRecord = null
        }

        try {
            recognizer?.close()
        } catch (_: Throwable) {
            // ignore
        } finally {
            recognizer = null
        }

        try {
            model?.close()
        } catch (_: Throwable) {
            // ignore
        } finally {
            model = null
        }
    }

    private fun runLoop(readBufBytes: Int) {
        val ar = audioRecord ?: return
        val rec = recognizer ?: return
        ar.startRecording()

        val buf = ByteArray(readBufBytes)

        while (running.get()) {
            val n = ar.read(buf, 0, buf.size)
            if (n <= 0) continue

            val accepted = rec.acceptWaveForm(buf, n)
            val json = if (accepted) rec.result else rec.partialResult
            val text = extractText(json) ?: continue

            if (text.isBlank()) continue
            val nowLog = SystemClock.elapsedRealtime()
            if (nowLog - lastHeardLogAtMs >= 1200L) {
                lastHeardLogAtMs = nowLog
                Log.d("WAKEWORD_VOSK", "heard='$text' accepted=$accepted")
            }
            // Important: do NOT wake on partial results; wait for a final/accepted phrase.
            if (accepted && matchesWakeFinal(text)) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTriggerAtMs >= minIntervalMsBetweenTriggers) {
                    lastTriggerAtMs = now
                    Log.i("WAKEWORD", "Wake detected by Vosk: '$text'")
                    onWake()
                }
            }
        }
    }

    private fun extractText(json: String): String? {
        return try {
            val obj = JSONObject(json)
            val raw = when {
                obj.has("text") -> obj.optString("text", "")
                obj.has("partial") -> obj.optString("partial", "")
                else -> ""
            }
            raw.trim().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun matchesWakeFinal(text: String): Boolean {
        // Normalize:
        // - lowercase
        // - "ё" -> "е"
        // - keep only letters/digits/spaces
        val norm = text
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Be strict: only accept the exact wake phrase (reduces false wake-ups).
        return norm == "соня прием"
    }

    private fun buildGrammarJson(phrases: List<String>): String {
        // Vosk grammar is a JSON array of phrases/words.
        // We also add token variants to help partial results.
        val items = linkedSetOf<String>()
        for (p in phrases) {
            val n = p.lowercase().trim()
            if (n.isNotBlank()) items.add(n)
        }
        items.add("соня")
        // Some models keep "приём" with 'ё' in vocabulary; keep both variants.
        items.add("приём")
        items.add("прием")
        return items.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
    }

    private fun ensureModelDir(): File {
        val dst = File(context.filesDir, modelAssetDir)
        if (dst.exists() && dst.isDirectory && dst.list()?.isNotEmpty() == true) return dst

        // Quick existence check: if assets don't contain the dir, fail early with a clear message.
        val top = try {
            context.assets.list(modelAssetDir)
        } catch (_: Throwable) {
            null
        }
        if (top == null) {
            throw IllegalStateException("Vosk model not found in assets: '$modelAssetDir' (put the model folder into app/src/main/assets/$modelAssetDir)")
        }

        copyAssetDirRecursively(modelAssetDir, dst)
        return dst
    }

    private fun copyAssetDirRecursively(assetPath: String, dstDir: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // It's a file.
            dstDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dstDir).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        // It's a directory.
        dstDir.mkdirs()
        for (name in children) {
            val childAssetPath = "$assetPath/$name"
            val childDst = File(dstDir, name)
            copyAssetDirRecursively(childAssetPath, childDst)
        }
    }
}

