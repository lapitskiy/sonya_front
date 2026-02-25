package com.example.sonya_front

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal openWakeWord ONNX pipeline:
 * audio -> melspectrogram.onnx -> embedding_model.onnx -> wakeword.onnx -> score.
 */
class OpenWakeWordEngine(
    private val context: Context,
    private val wakeModelAssetPath: String = "openwakeword/hey_jarvis_v0.1.onnx",
    // NOTE: On-device scores for openWakeWord ONNX can be very small (e.g. 1e-5 .. 1e-3+).
    // We'll start with a conservative threshold and require multiple consecutive hits.
    private val scoreThreshold: Float = 0.0015f,
    private val minIntervalMsBetweenTriggers: Long = 2000L,
    // "Hits" are not always consecutive; we trigger if we see enough hits within a short window.
    // On real devices we often see a single strong peak per phrase, so default to 1 hit.
    private val consecutiveHitsToTrigger: Int = 1,
    private val onWake: () -> Unit,
) {
    private val engineVersionTag: String = "oww-onnx-v8-2026-01-28-006"
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    private var ortEnv: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embedSession: OrtSession? = null
    private var wakeSession: OrtSession? = null
    private var melInputShape: LongArray? = null
    private var melInputCount: Int = 0

    // Wake model expects [1, 16, 96] - a window of the last 16 embeddings.
    private val wakeFrames: Int = 16
    private val embedDim: Int = 96
    private val embedHistory: FloatArray = FloatArray(wakeFrames * embedDim)
    private var embedHistoryFilled: Int = 0

    private var lastTriggerAtMs: Long = 0L
    private var consecutiveHits: Int = 0 // used as "hits in window"
    private var lastScoreLogAtMs: Long = 0L
    private var lastDiagAtMs: Long = 0L
    private var lastAudioRms: Float = 0f
    private var lastWindowRms: Float = 0f
    // Very light gate: blocks obvious silence but doesn't require loud speech.
    // (We saw false-ish high scores around rms ~0.003 on phone.)
    private val minRmsToCountAsSpeech: Float = 0.005f

    private val hitWindowMs: Long = 1500L
    // Immediate trigger should be noticeably higher than regular threshold to avoid "any speech" triggers.
    private val immediateTriggerScore: Float = 0.0060f
    private var firstHitAtMs: Long = 0L

    private val isEmulator: Boolean by lazy { isProbablyEmulator() }
    private val effectiveHitsToTrigger: Int by lazy { if (isEmulator) 2 else consecutiveHitsToTrigger }
    private val effectiveScoreThreshold: Float by lazy { if (isEmulator) 0.00060f else scoreThreshold }
    private val effectiveImmediateTriggerScore: Float by lazy { if (isEmulator) 0.00120f else immediateTriggerScore }

    /**
     * @return true if the engine actually started (models loaded + AudioRecord running).
     */
    fun start(): Boolean {
        if (running.getAndSet(true)) return true

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("WAKEWORD", "RECORD_AUDIO permission missing; wake word engine not started")
            running.set(false)
            return false
        }

        try {
            // ONNX Runtime avoids the TFLite allocateTensors() crash we were hitting on some devices.
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            melSession = createSession(env, "openwakeword/melspectrogram.onnx", tag = "melspectrogram")
            embedSession = createSession(env, "openwakeword/embedding_model.onnx", tag = "embedding")
            wakeSession = createSession(env, wakeModelAssetPath, tag = "wake")

            val (shape, count) = resolveInputShapeAndCount(melSession!!, tag = "melspectrogram")
            melInputShape = shape
            melInputCount = count
        } catch (t: Throwable) {
            Log.e("WAKEWORD", "Failed to initialize ONNX models: ${t.message}", t)
            stop()
            return false
        }

        Log.i(
            "WAKEWORD",
            "OpenWakeWordEngine $engineVersionTag cfg: thr=$effectiveScoreThreshold (base=$scoreThreshold) gateRms=$minRmsToCountAsSpeech hitsToTrigger=$effectiveHitsToTrigger (base=$consecutiveHitsToTrigger) windowMs=$hitWindowMs immediate=$effectiveImmediateTriggerScore (base=$immediateTriggerScore) emulator=$isEmulator"
        )

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate / 2)

        // Some emulators return near-silence (all zeros) when using VOICE_RECOGNITION.
        // Prefer MIC on emulators.
        val audioSource = if (isEmulator) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION
        Log.i("WAKEWORD", "AudioRecord source=${if (audioSource == MediaRecorder.AudioSource.MIC) "MIC" else "VOICE_RECOGNITION"} emulator=$isEmulator")

        audioRecord = AudioRecord(
            audioSource,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuf * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("WAKEWORD", "AudioRecord not initialized")
            stop()
            return false
        }

        thread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            runLoop(sampleRate, minBuf)
        }.apply { name = "OpenWakeWord"; start() }

        return true
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

        try { melSession?.close() } catch (_: Throwable) {}
        try { embedSession?.close() } catch (_: Throwable) {}
        try { wakeSession?.close() } catch (_: Throwable) {}
        melSession = null
        embedSession = null
        wakeSession = null
        // OrtEnvironment is a singleton; do not close it.
        ortEnv = null
        melInputShape = null
        melInputCount = 0
        embedHistoryFilled = 0

        consecutiveHits = 0
    }

    private fun runLoop(sampleRate: Int, readBufSamples: Int) {
        val ar = audioRecord ?: return
        val env = ortEnv ?: return
        val melS = melSession ?: return
        val embedS = embedSession ?: return
        val wakeS = wakeSession ?: return
        val melCount = melInputCount
        val melShape = melInputShape ?: return

        if (melCount <= 0) {
            Log.e("WAKEWORD", "Invalid mel input count: $melCount")
            return
        }

        val ring = FloatArray(melCount)
        var ringPos = 0
        var filled = 0

        val shortBuf = ShortArray(readBufSamples)
        ar.startRecording()
        Log.i("WAKEWORD", "Wake word engine started (sr=$sampleRate, melInput=$melCount samples, ort)")

        while (running.get()) {
            val n = ar.read(shortBuf, 0, shortBuf.size)
            if (n <= 0) continue

            var sumSq = 0.0
            for (i in 0 until n) {
                val v = shortBuf[i].toDouble() / 32768.0
                sumSq += v * v
                ring[ringPos] = v.toFloat()
                ringPos = (ringPos + 1) % ring.size
                if (filled < ring.size) filled++
            }

            if (filled < ring.size) continue

            // Build a contiguous window from ring buffer.
            val window = FloatArray(ring.size)
            val tail = ring.size - ringPos
            System.arraycopy(ring, ringPos, window, 0, tail)
            if (ringPos > 0) System.arraycopy(ring, 0, window, tail, ringPos)

            // RMS of the just-read chunk (cheap) and RMS of the inference window (aligned with score).
            lastAudioRms = kotlin.math.sqrt((sumSq / n).coerceAtLeast(0.0)).toFloat()
            run {
                var s = 0.0
                for (v in window) {
                    val d = v.toDouble()
                    s += d * d
                }
                lastWindowRms = kotlin.math.sqrt((s / window.size).coerceAtLeast(0.0)).toFloat()
            }
            val nowDiag = SystemClock.elapsedRealtime()
            if (nowDiag - lastDiagAtMs >= 1000L) {
                lastDiagAtMs = nowDiag
                Log.d(
                    "WAKEWORD_DIAG",
                    "audio rmsChunk=${"%.5f".format(lastAudioRms)} rmsWin=${"%.5f".format(lastWindowRms)} n=$n ring=${ring.size}"
                )
            }

            val score = runInference(env, window, melShape, melS, embedS, wakeS)
            if (score != null) {
                handleScore(score)
            }

            // With 1s window, use 100ms hop for responsiveness.
            val hop = (sampleRate / 10).coerceAtMost(ring.size).coerceAtLeast(1)
            filled = (filled - hop).coerceAtLeast(0)
        }
    }

    private fun handleScore(score: Float) {
        val now = SystemClock.elapsedRealtime()
        val rmsForDecision = if (lastWindowRms > 0f) lastWindowRms else lastAudioRms

        // Big peak -> trigger immediately (still respects minInterval).
        if (score >= effectiveImmediateTriggerScore) {
            // Even for immediate peaks, require *some* energy in the inference window.
            if (rmsForDecision < (minRmsToCountAsSpeech / 5f).coerceAtLeast(0.001f)) {
                Log.d(
                    "WAKEWORD_SCORE",
                    "PeakBlockedByRms score=$score thrImm=$effectiveImmediateTriggerScore rms=$rmsForDecision gateImm=${(minRmsToCountAsSpeech / 5f).coerceAtLeast(0.001f)}"
                )
                consecutiveHits = 0
                firstHitAtMs = 0L
                return
            }
            if (now - lastTriggerAtMs >= minIntervalMsBetweenTriggers) {
                lastTriggerAtMs = now
                consecutiveHits = 0
                firstHitAtMs = 0L
                Log.i("WAKEWORD", "Wake detected! score=$score (immediate) rms=$rmsForDecision")
                onWake()
            } else {
                Log.d("WAKEWORD_SCORE", "PeakIgnoredByInterval score=$score rms=$rmsForDecision")
            }
            return
        }

        // Gate on audio energy to reduce false positives in silence/background.
        if (rmsForDecision < minRmsToCountAsSpeech) {
            if (score >= effectiveScoreThreshold) {
                Log.d(
                    "WAKEWORD_SCORE",
                    "BlockedByRms score=$score thr=$effectiveScoreThreshold rms=$rmsForDecision gate=$minRmsToCountAsSpeech"
                )
            }
            consecutiveHits = 0
            firstHitAtMs = 0L
            return
        }

        // Regular hit accumulation within a time window (not necessarily consecutive).
        if (score >= effectiveScoreThreshold) {
            if (firstHitAtMs == 0L || now - firstHitAtMs > hitWindowMs) {
                firstHitAtMs = now
                consecutiveHits = 1
            } else {
                consecutiveHits++
            }
            Log.d(
                "WAKEWORD_SCORE",
                "Hit score=$score thr=$effectiveScoreThreshold rms=$rmsForDecision hits=$consecutiveHits/$effectiveHitsToTrigger windowMs=$hitWindowMs"
            )
        }

        if (consecutiveHits < effectiveHitsToTrigger) return
        if (now - lastTriggerAtMs < minIntervalMsBetweenTriggers) return

        lastTriggerAtMs = now
        consecutiveHits = 0
        firstHitAtMs = 0L
        Log.i("WAKEWORD", "Wake detected! score=$score rms=$rmsForDecision")
        onWake()
    }

    private fun isProbablyEmulator(): Boolean {
        val fp = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val brand = Build.BRAND ?: ""
        val device = Build.DEVICE ?: ""
        val product = Build.PRODUCT ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        return fp.contains("generic", ignoreCase = true) ||
            fp.contains("unknown", ignoreCase = true) ||
            model.contains("google_sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            manufacturer.contains("Genymotion", ignoreCase = true) ||
            (brand.startsWith("generic", ignoreCase = true) && device.startsWith("generic", ignoreCase = true)) ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true) ||
            product.contains("simulator", ignoreCase = true)
    }

    private fun runInference(
        env: OrtEnvironment,
        audioWindow: FloatArray,
        melInShape: LongArray,
        melSession: OrtSession,
        embedSession: OrtSession,
        wakeSession: OrtSession,
    ): Float? {
        try {
            // 1) melspectrogram
            val melInName = melSession.inputNames.first()
            val melInFlat = padOrTruncate(audioWindow, melInShape.fold(1L) { acc, d -> acc * d }.toInt())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(melInFlat), melInShape).use { melInTensor ->
                melSession.run(mapOf(melInName to melInTensor)).use { melRes ->
                    val melTensor = melRes[0] as OnnxTensor
                    val melOutShape = melTensor.info.shape
                    val melFlat = melTensor.floatBuffer.toFloatArray()

                    // Expected by embedding_model: [1, 76, 32, 1] (float)
                    // melspectrogram outputs [batch, 1, time, 32]. We take the last 76 frames.
                    val targetTime = 76
                    val melBins = 32
                    val embedInShape = longArrayOf(1L, targetTime.toLong(), melBins.toLong(), 1L)
                    val embedInFlat = FloatArray(targetTime * melBins)

                    val timeDim = melOutShape.getOrNull(2)?.toInt()?.coerceAtLeast(1) ?: 1
                    val binsDim = melOutShape.getOrNull(3)?.toInt()?.coerceAtLeast(1) ?: melBins
                    val framesToCopy = minOf(timeDim, targetTime)
                    val binsToCopy = minOf(binsDim, melBins)

                    // melFlat layout is [time, bins] (since batch=1, channel=1)
                    // Copy the last framesToCopy frames into the tail of embedInFlat.
                    val srcFrameStart = (timeDim - framesToCopy).coerceAtLeast(0)
                    val dstFrameStart = (targetTime - framesToCopy).coerceAtLeast(0)
                    for (f in 0 until framesToCopy) {
                        val srcOff = (srcFrameStart + f) * binsDim
                        val dstOff = (dstFrameStart + f) * melBins
                        if (srcOff + binsToCopy <= melFlat.size && dstOff + binsToCopy <= embedInFlat.size) {
                            System.arraycopy(melFlat, srcOff, embedInFlat, dstOff, binsToCopy)
                        }
                    }

                    // 2) embedding
                    val embedInName = embedSession.inputNames.first()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(embedInFlat), embedInShape).use { embedInTensor ->
                        embedSession.run(mapOf(embedInName to embedInTensor)).use { embedRes ->
                            val embedTensor = embedRes[0] as OnnxTensor
                            val embedOutShape = embedTensor.info.shape
                            val embedOutFlat = embedTensor.floatBuffer.toFloatArray()

                            // embedding output is typically [1, 1, 1, 96]; we need a 96-dim vector.
                            val embedVec = if (embedOutFlat.size == embedDim) {
                                embedOutFlat
                            } else if (embedOutFlat.size > embedDim) {
                                embedOutFlat.copyOfRange(embedOutFlat.size - embedDim, embedOutFlat.size)
                            } else {
                                padOrTruncate(embedOutFlat, embedDim)
                            }

                            // Maintain a sliding window of last 16 embeddings for wake model input [1, 16, 96].
                            if (embedHistoryFilled < wakeFrames) {
                                System.arraycopy(embedVec, 0, embedHistory, embedHistoryFilled * embedDim, embedDim)
                                embedHistoryFilled++
                            } else {
                                // shift left by 1 frame
                                System.arraycopy(embedHistory, embedDim, embedHistory, 0, (wakeFrames - 1) * embedDim)
                                System.arraycopy(embedVec, 0, embedHistory, (wakeFrames - 1) * embedDim, embedDim)
                            }

                            // Allow early scoring: if we don't have 16 frames yet, pad the remainder with the latest embedding.
                            val wakeInputFlat: FloatArray = if (embedHistoryFilled >= wakeFrames) {
                                embedHistory
                            } else {
                                val tmp = FloatArray(wakeFrames * embedDim)
                                // copy what we have
                                System.arraycopy(embedHistory, 0, tmp, 0, embedHistoryFilled * embedDim)
                                // pad remaining frames with current embedding
                                var at = embedHistoryFilled
                                while (at < wakeFrames) {
                                    System.arraycopy(embedVec, 0, tmp, at * embedDim, embedDim)
                                    at++
                                }
                                tmp
                            }

                            // 3) wakeword
                            val wakeInName = wakeSession.inputNames.first()
                            val wakeInShape = longArrayOf(1L, wakeFrames.toLong(), embedDim.toLong())
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(wakeInputFlat), wakeInShape).use { wakeInTensor ->
                                wakeSession.run(mapOf(wakeInName to wakeInTensor)).use { wakeRes ->
                                    val wakeTensor = wakeRes[0] as OnnxTensor
                                    val outFlat = wakeTensor.floatBuffer.toFloatArray()
                                    val score = outFlat.maxOrNull()
                                    if (score != null) {
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastScoreLogAtMs >= 500L) {
                                            lastScoreLogAtMs = now
                                            Log.d(
                                                "WAKEWORD_SCORE",
                                                "score=$score thr=$scoreThreshold frames=$embedHistoryFilled rms=$lastAudioRms melOut=${melOutShape.contentToString()} embedOut=${embedOutShape.contentToString()} melTime=$timeDim melBins=$binsDim"
                                            )
                                        }
                                    }
                                    return score
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("WAKEWORD", "Inference error: ${t.message}", t)
            return null
        }
    }

    private fun createSession(env: OrtEnvironment, assetPath: String, tag: String): OrtSession {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        Log.i("WAKEWORD_DIAG", "asset=$assetPath size=${bytes.size}")
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        val session = env.createSession(bytes, opts)
        logSessionIO(tag, session)
        return session
    }

    private fun logSessionIO(tag: String, session: OrtSession) {
        try {
            val inName = session.inputNames.firstOrNull()
            val outName = session.outputNames.firstOrNull()
            val inInfo = inName?.let { session.inputInfo[it] }
            val outInfo = outName?.let { session.outputInfo[outName] }
            Log.i("WAKEWORD_DIAG", "$tag inName=$inName inInfo=$inInfo")
            Log.i("WAKEWORD_DIAG", "$tag outName=$outName outInfo=$outInfo")
        } catch (t: Throwable) {
            Log.w("WAKEWORD_DIAG", "Failed to log session IO for $tag: ${t.message}")
        }
    }

    private fun resolveInputShapeAndCount(session: OrtSession, tag: String): Pair<LongArray, Int> {
        val inName = session.inputNames.first()
        val info = session.inputInfo[inName] ?: throw IllegalStateException("No inputInfo for $tag/$inName")
        val tensorInfo = info.info as ai.onnxruntime.TensorInfo
        val shape = tensorInfo.shape

        // IMPORTANT:
        // melspectrogram.onnx has input shape [-1, -1] which represents [batch, samples].
        // If we naively replace both dynamic dims with 16000 we allocate ~1GB and crash.
        //
        // openWakeWord defaults to 640 samples per frame at 16kHz (40ms).
        val fixed: LongArray = when {
            tag == "melspectrogram" && shape.size == 2 && shape[0] <= 0L && shape[1] <= 0L ->
                // Use 1s window so that melspectrogram produces multiple time frames (needed for embedding input 76x32).
                longArrayOf(1L, 16000L)
            tag == "melspectrogram" && shape.size == 2 && shape[0] <= 0L && shape[1] > 0L ->
                longArrayOf(1L, shape[1])
            tag == "melspectrogram" && shape.size == 1 && shape[0] <= 0L ->
                longArrayOf(16000L)
            else -> {
                // For other models: batch=1 if dynamic; other dynamic dims -> 1 to avoid OOM.
                shape.mapIndexed { idx, dim ->
                    when {
                        dim > 0L -> dim
                        idx == 0 -> 1L
                        else -> 1L
                    }
                }.toLongArray()
            }
        }

        var count = 1L
        for (d in fixed) count *= d
        val cntInt = count.toInt()
        Log.i("WAKEWORD_DIAG", "$tag inputShape=${shape.contentToString()} fixed=${fixed.contentToString()} count=$cntInt")
        return fixed to cntInt
    }

    private fun resolveTensorShape(session: OrtSession, tag: String, inputName: String, fallbackCount: Int): LongArray {
        val info = session.inputInfo[inputName] ?: return longArrayOf(1L, fallbackCount.toLong())
        val tensorInfo = info.info as ai.onnxruntime.TensorInfo
        val shape = tensorInfo.shape

        // Replace dynamic dims safely:
        // - batch dim (0) -> 1
        // - any other dynamic dim -> 1 (we will pad/truncate the flat array to match)
        return shape.mapIndexed { idx, dim ->
            when {
                dim > 0L -> dim
                idx == 0 -> 1L
                else -> 1L
            }
        }.toLongArray()
    }

    private fun FloatBuffer.toFloatArray(): FloatArray {
        val dup = duplicate()
        val out = FloatArray(dup.remaining())
        dup.get(out)
        return out
    }

    private fun padOrTruncate(flat: FloatArray, target: Int): FloatArray {
        if (flat.size == target) return flat
        val out = FloatArray(target)
        val n = minOf(flat.size, target)
        System.arraycopy(flat, 0, out, 0, n)
        return out
    }
}

