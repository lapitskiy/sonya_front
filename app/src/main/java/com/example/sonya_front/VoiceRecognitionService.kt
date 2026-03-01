package com.example.sonya_front

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.media.AudioManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class VoiceRecognitionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeWordEngine: VoskWakeWordEngine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionListener: RecognitionListener? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Keep a recent location cached so background wake/commands can attach coords reliably.
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLon: Double? = null
    @Volatile private var lastLocAtMs: Long = 0L
    private var locationCallback: LocationCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // --- Новая логика "сшивания" фраз ---
    private val combinedTextBuilder = StringBuilder()
    private var isContinuousListening = false
    // Fallback: sometimes final onResults contains only "спасибо", while the command text was only in partials.
    private var lastPartialForSend: String = ""
    // If we finalize specifically because user said a finish phrase ("спасибо"/"конец связи"),
    // but end up with an empty finalText, show a hint instead of silently doing nothing.
    private var finalizeRequestedByFinishPhrase: String? = null

    // Управляем "паузами подумать": финализируем только после длительной тишины, а не по первому TIMEOUT.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var finalizeRunnable: Runnable? = null
    private val finalizeAfterSilenceMs = 5500L
    private var lastActivityAtMs = 0L

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady: Boolean = false

    private var watchConnReceiver: BroadcastReceiver? = null

    // Wake reaction phrase should be spoken BEFORE recording; we enter command mode only after TTS completes.
    @Volatile private var wakeTtsUtteranceId: String? = null
    @Volatile private var wakeAfterTts: Runnable? = null
    private var wakeAfterTtsFallback: Runnable? = null

    // Optional guard (kept for future): after wake triggers, you can quickly verify the phrase via SpeechRecognizer.
    // For Vosk keyword spotting we usually don't need it, but the helpers stay useful.
    private var isWakeVerificationMode: Boolean = false
    private val wakeVerifyTimeoutMs: Long = 1200L
    private val wakeVerifyPhrases: List<String> = listOf(
        "соня прием",
        "соня приём",
    )

    // Фразы управления во время диктовки команды:
    // - "конец связи"/"спасибо" -> завершить и отправить
    // - "отбой"/"стоп" -> отменить и НЕ отправлять
    private val finishPhrases: List<String> = listOf(
        "конец связи",
        "спасибо",
    )
    private val cancelPhrases: List<String> = listOf(
        "отбой",
        "стоп",
    )

    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeWordRunning: Boolean = false
    private val wakeWordWatchdogIntervalMs = 60_000L
    private val wakeWordWatchdog = object : Runnable {
        override fun run() {
            if (!UserSettingsStore.getWakeListeningEnabled(applicationContext)) {
                // Wake listening disabled by user — do not restart.
                mainHandler.postDelayed(this, wakeWordWatchdogIntervalMs)
                return
            }
            if (!isContinuousListening && !isWakeWordRunning) {
                Log.w("WAKEWORD", "Watchdog: wake word engine is not running, restarting")
                startWakeWordDelayed(0L)
            }
            mainHandler.postDelayed(this, wakeWordWatchdogIntervalMs)
        }
    }

    companion object {
        val RECOGNITION_RESULT_ACTION = "com.example.sonya_front.RECOGNITION_RESULT"
        val RECOGNITION_RESULT_TEXT = "RECOGNITION_RESULT_TEXT"
        val PARTIAL_RECOGNITION_RESULT_ACTION = "com.example.sonya_front.PARTIAL_RECOGNITION_RESULT"
        val PARTIAL_RECOGNITION_RESULT_TEXT = "PARTIAL_RECOGNITION_RESULT_TEXT"
        val STATUS_UPDATE_ACTION = "com.example.sonya_front.STATUS_UPDATE"
        val STATUS_UPDATE_TEXT = "STATUS_UPDATE_TEXT"

        // Всплывающие подсказки (для Snackbar), чтобы не перетирать главный статус на экране.
        val HINT_UPDATE_ACTION = "com.example.sonya_front.HINT_UPDATE"
        val HINT_UPDATE_TEXT = "HINT_UPDATE_TEXT"

        // Действия для паузы/возобновления фоновой прослушки вейк-фразы
        val ACTION_PAUSE_WAKE = "com.example.sonya_front.PAUSE_WAKE_LISTENING"
        val ACTION_RESUME_WAKE = "com.example.sonya_front.RESUME_WAKE_LISTENING"

        // Озвучить произвольный текст через TTS сервиса (из других компонентов)
        val ACTION_SPEAK = "com.example.sonya_front.SPEAK_TEXT"
        val EXTRA_SPEAK_TEXT = "speak_text"

        // Единый вход для распознанного текста (из любых источников: телефон/часы/кнопка)
        val ACTION_PROCESS_RECOGNIZED_TEXT = "com.example.sonya_front.PROCESS_RECOGNIZED_TEXT"
        val EXTRA_RECOGNIZED_TEXT = "recognized_text"
        val EXTRA_RECOGNIZED_SOURCE = "recognized_source"
    }

    // If TTS init is not ready yet, queue a few phrases (avoid "lost" confirms).
    private val pendingTtsQueue: ArrayDeque<Pair<String, Int>> = ArrayDeque()
    private val pendingTtsMax: Int = 6

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Voice confirmations ("Услышала", "Всё ОК").
        try {
            tts = TextToSpeech(this) { status ->
                ttsReady = (status == TextToSpeech.SUCCESS)
                Log.i("TTS", "init status=$status ready=$ttsReady")
                if (!ttsReady) return@TextToSpeech
                try {
                    tts?.language = Locale("ru", "RU")
                } catch (t: Throwable) {
                    Log.w("TTS", "setLanguage failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                flushPendingTts()
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == null) return
                    val expected = wakeTtsUtteranceId ?: return
                    if (utteranceId != expected) return
                    val next = wakeAfterTts
                    wakeTtsUtteranceId = null
                    wakeAfterTts = null
                    wakeAfterTtsFallback?.let { mainHandler.removeCallbacks(it) }
                    wakeAfterTtsFallback = null
                    mainHandler.post { next?.run() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == null) return
                    val expected = wakeTtsUtteranceId ?: return
                    if (utteranceId != expected) return
                    val next = wakeAfterTts
                    wakeTtsUtteranceId = null
                    wakeAfterTts = null
                    wakeAfterTtsFallback?.let { mainHandler.removeCallbacks(it) }
                    wakeAfterTtsFallback = null
                    mainHandler.post { next?.run() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onError(utteranceId)
                }
            })
        } catch (_: Throwable) {
            ttsReady = false
            tts = null
        }

        createNotificationChannel()
        startForeground(1, createNotification())
        registerWatchConnectionReceiver()
        startLocationUpdates()
        startNetworkWatcher()
        setupRecognizers()
        mainHandler.removeCallbacks(wakeWordWatchdog)
        mainHandler.postDelayed(wakeWordWatchdog, wakeWordWatchdogIntervalMs)

        // Initial pull to schedule any pending actions (e.g., after app restart).
        val deviceId = "android-" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        serviceScope.launch {
            PendingActionsSync.syncNow(applicationContext, deviceId, reason = "service_start")
        }
    }

    private fun setupRecognizers() {
        try {
            val listener = object : RecognitionListener {
                 override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("SPEECH_RECOGNIZER", "onReadyForSpeech for a chunk")
                }

                override fun onBeginningOfSpeech() {
                    Log.d("SPEECH_RECOGNIZER", "onBeginningOfSpeech for a chunk")
                    markActivity()
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d("SPEECH_RECOGNIZER", "onEndOfSpeech (system event for a chunk)")
                }

                override fun onError(error: Int) {
                    if (!isContinuousListening) return

                    if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        Log.e("SPEECH_RECOGNIZER", "Recognizer error=$error, recreating SpeechRecognizer")
                        recreateSpeechRecognizer()
                        startListeningChunk(delayMs = 250L)
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Log.d("SPEECH_RECOGNIZER", "No match/timeout: treat as pause, keep listening. error=$error")

                        // Если мы ещё ничего не распознали — просто продолжаем слушать.
                        if (combinedTextBuilder.isBlank()) {
                            startListeningChunk(delayMs = 150L)
                            return
                        }

                        // Финализируем только после "реальной" тишины от последней активности (partial/begin/results).
                        val now = SystemClock.elapsedRealtime()
                        val last = lastActivityAtMs
                        val silenceMs = if (last <= 0L) Long.MAX_VALUE else (now - last)
                        val remainingMs = finalizeAfterSilenceMs - silenceMs

                        if (remainingMs <= 0L) {
                            finalizeNow()
                            return
                        } else {
                            scheduleFinalize(remainingMs)
                        }

                        startListeningChunk(delayMs = 150L)
                        return
                    }

                    Log.e("SPEECH_RECOGNIZER", "Continuous listening error: $error")
                    broadcastStatusUpdate("Ошибка распознавания ($error)")
                    isContinuousListening = false
                    cancelFinalize()
                    stopSpeechRecognizerSafely()
                    destroySpeechRecognizerSafely()
                    startWakeWord()
                }

                override fun onResults(results: Bundle?) {
                    if (!isContinuousListening) return

                    val chunk = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!chunk.isNullOrBlank()) {
                        markActivity()

                        // 1) Отмена команды: "отбой"/"стоп" -> ничего не отправляем, возвращаемся в режим ожидания.
                        val cancel = cancelPhrases
                            .map { phrase -> phrase to lastIndexOfPhraseAsWords(chunk, phrase) }
                            .filter { it.second != -1 }
                            .maxByOrNull { it.second }
                        if (cancel != null) {
                            val phrase = cancel.first
                            Log.i("SPEECH_RECOGNIZER", "Cancel phrase '$phrase' detected in chunk: '$chunk'")
                            abortCommandMode("Отмена: $phrase", voice = "Окей, отбой")
                            return
                        }

                        // 2) Завершение команды: "конец связи" или "спасибо" -> отправляем накопленный текст.
                        val finish = finishPhrases
                            .map { phrase -> phrase to lastIndexOfPhraseAsWords(chunk, phrase) }
                            .filter { it.second != -1 }
                            .maxByOrNull { it.second }
                        if (finish != null) {
                            val phrase = finish.first
                            val idx = finish.second
                            Log.d("SPEECH_RECOGNIZER", "Finish phrase '$phrase' detected in chunk: '$chunk'")
                            val contentBeforeStop = chunk.substring(0, idx).trim()
                            if (contentBeforeStop.isNotEmpty()) {
                                combinedTextBuilder.append(contentBeforeStop).append(" ")
                            } else if (combinedTextBuilder.isBlank() && lastPartialForSend.isNotBlank()) {
                                // Fallback: take command text from the last partial UI string.
                                val idx2 = lastIndexOfPhraseAsWords(lastPartialForSend, phrase)
                                val fallback = if (idx2 != -1) lastPartialForSend.substring(0, idx2).trim() else lastPartialForSend.trim()
                                if (fallback.isNotBlank()) combinedTextBuilder.append(fallback).append(" ")
                            }
                            finalizeRequestedByFinishPhrase = phrase
                            finalizeNow()
                            return
                        }

                        combinedTextBuilder.append(chunk).append(" ")
                        lastPartialForSend = combinedTextBuilder.toString()
                        Log.d("SPEECH_RECOGNIZER", "Got chunk: '$chunk'. Combined so far: '${combinedTextBuilder.toString()}'")
                    }
                    startListeningChunk()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                     if (!isContinuousListening) return
                    val partialChunk = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partialChunk.isNullOrBlank()) {
                        markActivity()
                        // ВАЖНО: не перетирать lastPartialForSend до проверки cancel/finish,
                        // иначе partial="спасибо" может затереть ранее распознанный текст команды.

                        // Быстрая отмена по partial-распознаванию ("отбой"/"стоп"), чтобы не ждать финального чанка.
                        val cancel = cancelPhrases
                            .map { phrase -> phrase to lastIndexOfPhraseAsWords(partialChunk, phrase) }
                            .filter { it.second != -1 }
                            .maxByOrNull { it.second }
                        if (cancel != null) {
                            val phrase = cancel.first
                            Log.i("SPEECH_RECOGNIZER", "Cancel phrase '$phrase' detected in partial: '$partialChunk'")
                            abortCommandMode("Отмена: $phrase", voice = "Окей, отбой")
                            return
                        }

                        // Завершение команды по partial-распознаванию: "конец связи" или "спасибо"
                        // (На некоторых девайсах финальный onResults может не прийти, и тогда команда теряется.)
                        val finish = finishPhrases
                            .map { phrase -> phrase to lastIndexOfPhraseAsWords(partialChunk, phrase) }
                            .filter { it.second != -1 }
                            .maxByOrNull { it.second }
                        if (finish != null) {
                            val phrase = finish.first
                            val idx = finish.second
                            Log.d("SPEECH_RECOGNIZER", "Finish phrase '$phrase' detected in partial: '$partialChunk'")
                            val contentBeforeStop = partialChunk.substring(0, idx).trim()
                            if (contentBeforeStop.isNotEmpty()) {
                                combinedTextBuilder.append(contentBeforeStop).append(" ")
                            } else if (combinedTextBuilder.isBlank() && lastPartialForSend.isNotBlank()) {
                                val idx2 = lastIndexOfPhraseAsWords(lastPartialForSend, phrase)
                                val fallback = if (idx2 != -1) lastPartialForSend.substring(0, idx2).trim() else lastPartialForSend.trim()
                                if (fallback.isNotBlank()) combinedTextBuilder.append(fallback).append(" ")
                            }
                            finalizeRequestedByFinishPhrase = phrase
                            finalizeNow()
                            return
                        }

                        val textForUi = combinedTextBuilder.toString() + partialChunk
                        lastPartialForSend = textForUi
                        broadcastPartialRecognitionResult(textForUi)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
            recognitionListener = listener
            ensureSpeechRecognizer()
            speechRecognizer?.setRecognitionListener(listener)

            if (UserSettingsStore.getWakeListeningEnabled(applicationContext)) {
                startWakeWord()
            } else {
                Log.i("WAKEWORD", "Wake listening disabled on startup, skipping wake word")
                broadcastStatusUpdate("Прослушка вейк-фразы отключена")
            }

        } catch (t: Throwable) {
            Log.e("WAKEWORD", "Unexpected error while initializing recognizers: ${t.message}", t)
            broadcastStatusUpdate("Ошибка инициализации: ${t.message}")
        }
    }

    private fun createSpeechRecognizerIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Хинты движку распознавания: разрешаем более длинные паузы.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        return intent
    }

    private fun createWakeVerifyIntent(): Intent {
        // Use a short, partial-enabled recognizer pass to confirm the wake phrase.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // Russian locale tends to recognize "Джарвис" better on RU devices.
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, wakeVerifyTimeoutMs)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, wakeVerifyTimeoutMs)
        return intent
    }

    private fun matchesWakePhrase(text: String): Boolean {
        val norm = text.lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{Nd}\\s]+"), " ").replace(Regex("\\s+"), " ").trim()
        return wakeVerifyPhrases.any { phrase ->
            val p = phrase.lowercase(Locale.getDefault())
            norm.contains(p)
        }
    }

    private fun startWakeVerificationThenCommand() {
        // Called right after wakeword triggers. We'll listen briefly and verify "Jarvis" exists in recognition.
        isWakeVerificationMode = true
        isContinuousListening = true
        cancelFinalize()
        lastActivityAtMs = SystemClock.elapsedRealtime()
        combinedTextBuilder.clear()

        // Force a fresh recognizer with a specialized listener to avoid mixing with command mode.
        destroySpeechRecognizerSafely()
        ensureSpeechRecognizer()

        fun fallbackToCommandMode(reason: String) {
            // Vosk already detected wake; verification is only a best-effort guard (e.g. when music is playing).
            // On some devices SpeechRecognizer returns NO_MATCH (7) too often, causing missed wake-ups.
            Log.w("WAKEWORD", "Wake verification fallback -> command mode. reason=$reason")
            broadcastHint("Не удалось подтвердить «соня приём» ($reason). Слушаю команду… Если сработало случайно — скажи «отбой».")
            isWakeVerificationMode = false
            isContinuousListening = false
            stopSpeechRecognizerSafely()
            destroySpeechRecognizerSafely()
            speakWakeThenEnterCommandMode()
        }

        val verifyListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val chunk = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (matchesWakePhrase(chunk)) {
                    Log.i("WAKEWORD", "Wake verification OK (partial): '$chunk'")
                    isWakeVerificationMode = false

                    // Stop verification recognizer first, then voice-ack, then enter command mode.
                    isContinuousListening = false
                    stopSpeechRecognizerSafely()
                    destroySpeechRecognizerSafely()

                    // Speak wake phrase, then start recording (prevents TTS from being captured as user speech).
                    speakWakeThenEnterCommandMode()
                }
            }

            override fun onResults(results: Bundle?) {
                val chunk = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (matchesWakePhrase(chunk)) {
                    Log.i("WAKEWORD", "Wake verification OK (final): '$chunk'")
                    isWakeVerificationMode = false

                    isContinuousListening = false
                    stopSpeechRecognizerSafely()
                    destroySpeechRecognizerSafely()

                    speakWakeThenEnterCommandMode()
                } else {
                    Log.i("WAKEWORD", "Wake verification FAILED: '$chunk' -> back to wake")
                    fallbackToCommandMode("no_match")
                }
            }

            override fun onError(error: Int) {
                // Treat errors/timeouts as a failed verification to reduce false wake-ups.
                Log.i("WAKEWORD", "Wake verification error=$error -> back to wake")
                fallbackToCommandMode("error=$error")
            }
        }

        recognitionListener = verifyListener
        speechRecognizer?.setRecognitionListener(verifyListener)
        mainHandler.postDelayed({
            if (isWakeVerificationMode) {
                Log.i("WAKEWORD", "Wake verification timeout -> back to wake")
                fallbackToCommandMode("timeout")
            }
        }, wakeVerifyTimeoutMs + 300L)

        mainHandler.postDelayed({
            try {
                speechRecognizer?.startListening(createWakeVerifyIntent())
            } catch (t: Throwable) {
                Log.w("WAKEWORD", "Failed to start wake verification: ${t.message}")
                fallbackToCommandMode("start_failed")
            }
        }, 50L)
    }

    /**
     * Returns true if the phone is currently producing audio that could cause
     * a false wake-word trigger (music, video, ringtone, active call, etc.).
     */
    private fun isPhoneAudioActive(): Boolean {
        return try {
            val am = getSystemService(AudioManager::class.java) ?: return false
            // Music / video / any media stream playing
            if (am.isMusicActive) return true
            // Ringtone, in-call, or communication mode (VoIP)
            when (am.mode) {
                AudioManager.MODE_RINGTONE,
                AudioManager.MODE_IN_CALL,
                AudioManager.MODE_IN_COMMUNICATION -> return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun startWakeWord() {
        startWakeWordDelayed(400L)
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("LOCATION", "Location permission missing; location cache disabled")
            return
        }

        // Balanced power is enough for "attach coords to command", and is more stable/cheaper in background.
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L)
            .setMinUpdateIntervalMillis(10_000L)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return

                // If mock locations enabled — do not trust coordinates.
                if (loc.isFromMockProvider) {
                    lastLat = null
                    lastLon = null
                    lastLocAtMs = 0L
                    broadcastHint("Обнаружена фиктивная геолокация — отправляю без координат.")
                    return
                }

                if (isProbablyEmulator() && isDefaultEmulatorLocation(loc.latitude, loc.longitude)) {
                    broadcastHint("Вы на эмуляторе: задайте Location в Emulator Controls, иначе будет Mountain View.")
                }

                lastLat = loc.latitude
                lastLon = loc.longitude
                lastLocAtMs = SystemClock.elapsedRealtime()
            }
        }

        locationCallback = cb
        try {
            fusedLocationClient.requestLocationUpdates(req, cb, Looper.getMainLooper())
            Log.i("LOCATION", "Location updates started (FGS)")
        } catch (t: Throwable) {
            Log.w("LOCATION", "Failed to start location updates: ${t.message}", t)
            locationCallback = null
        }
    }

    private fun stopLocationUpdates() {
        val cb = locationCallback ?: return
        try {
            fusedLocationClient.removeLocationUpdates(cb)
        } catch (_: Throwable) {
            // ignore
        } finally {
            locationCallback = null
        }
    }

    private fun startNetworkWatcher() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    if (!hasInternet) return

                    val deviceId = "android-" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    serviceScope.launch {
                        PendingActionsSync.syncNow(applicationContext, deviceId, reason = "network_available")
                    }
                }
            }
            networkCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (t: Throwable) {
            Log.w("SYNC", "Failed to start network watcher: ${t.message}")
            networkCallback = null
        }
    }

    private fun stopNetworkWatcher() {
        val cb = networkCallback ?: return
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
            // ignore
        } finally {
            networkCallback = null
        }
    }

    @SuppressLint("HardwareIds")
    private fun sendToServer(text: String) {
        // Проверяем разрешение на геолокацию
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("API_CALL", "Location permission not granted. Sending command without location.")
            // Отправляем без координат, если нет разрешения
            sendRequest(text, null, null)
            return
        }

        // Prefer a recent cached location (works reliably when app UI is closed).
        run {
            val now = SystemClock.elapsedRealtime()
            val lat = lastLat
            val lon = lastLon
            val ageMs = now - lastLocAtMs
            if (lat != null && lon != null && ageMs in 0..(3 * 60_000L)) {
                Log.d("API_CALL", "Using cached location (ageMs=$ageMs): Lat $lat, Lon $lon")
                sendRequest(text, lat, lon)
                return
            }
        }

        // Получаем последнюю известную геолокацию
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    // Если включены mock-locations — не доверяем координатам.
                    if (location.isFromMockProvider) {
                        Log.w("API_CALL", "Mock location detected. Sending command without location.")
                        broadcastHint("Обнаружена фиктивная геолокация — отправляю без координат.")
                        sendRequest(text, null, null)
                        return@addOnSuccessListener
                    }

                    // Эмулятор часто отдаёт дефолтную точку Mountain View — предупредим, что нужно выставить Location.
                    if (isProbablyEmulator() && isDefaultEmulatorLocation(location.latitude, location.longitude)) {
                        broadcastHint("Вы на эмуляторе: задайте Location в Emulator Controls, иначе будет Mountain View.")
                    }

                    Log.d("API_CALL", "Location acquired: Lat ${location.latitude}, Lon ${location.longitude}")
                    sendRequest(text, location.latitude, location.longitude)
                } else {
                    Log.w("API_CALL", "Location is null. Sending command without location.")
                    sendRequest(text, null, null)
                }
            }
            .addOnFailureListener { e ->
                Log.e("API_CALL", "Failed to get location", e)
                sendRequest(text, null, null)
            }
    }

    @SuppressLint("HardwareIds")
    private fun sendRequest(text: String, lat: Double?, lon: Double?) {
        val deviceId = "android-" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        // Форматируем время в ISO 8601 с таймзоной
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        val deviceTime = sdf.format(Date())

        val request = CommandRequest(
            deviceId = deviceId,
            text = text,
            lat = lat,
            lon = lon,
            deviceTime = deviceTime
        )

        serviceScope.launch {
            try {
                val resp = ApiClient.instance.sendCommand(request)
                Log.d("API_CALL", "Command sent successfully: $request")

                if (resp.isSuccessful) {
                    // Voice feedback: backend accepted the command ("поставилась в работу").
                    vibrateVoiceAck()
                    speakOnMain("Всё ОК", queueMode = TextToSpeech.QUEUE_ADD)
                }

                // Some backends may return a direct action payload from /command (e.g. {"type":"text-timer",...}).
                tryScheduleDirectActionFromCommandResponse(resp, deviceId)

                // Pull pending actions right after the command is accepted by backend.
                PendingActionsSync.syncNow(applicationContext, deviceId, reason = "after_command")
            } catch (e: HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Throwable) { null }
                Log.e("API_CALL_ERROR", "HTTP ${e.code()} while sending command. errorBody=${body ?: "<empty>"} request=$request", e)
            } catch (e: IOException) {
                Log.e("API_CALL_ERROR", "Network error while sending command. request=$request", e)
            } catch (e: Exception) {
                Log.e("API_CALL_ERROR", "Unexpected error while sending command. request=$request", e)
            }
        }
    }

    private fun tryScheduleDirectActionFromCommandResponse(
        resp: retrofit2.Response<okhttp3.ResponseBody>,
        deviceId: String,
    ) {
        try {
            if (!resp.isSuccessful) {
                Log.d("API_CALL", "tryScheduleDirectAction: response not successful (${resp.code()}), skip")
                return
            }
            val raw = try { resp.body()?.string() } catch (_: Throwable) { null } ?: return
            val trimmed = raw.trim()
            Log.i("API_CALL", "tryScheduleDirectAction raw response: '$trimmed'")
            if (trimmed.isBlank() || trimmed == "null") {
                Log.d("API_CALL", "tryScheduleDirectAction: blank/null body, skip")
                return
            }

            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val adapter = moshi.adapter(CommandResponseAction::class.java)
            val parsed = try { adapter.fromJson(trimmed) } catch (e: Throwable) {
                Log.w("API_CALL", "tryScheduleDirectAction: parse failed: ${e.message}")
                null
            } ?: return

            val type = parsed.type?.trim().orEmpty()
            val time = parsed.time?.trim().orEmpty()
            Log.i("API_CALL", "tryScheduleDirectAction parsed: id=${parsed.id} type='$type' time='$time' text='${parsed.text}'")
            if (type.isBlank() || time.isBlank()) {
                Log.d("API_CALL", "tryScheduleDirectAction: type or time blank, skip")
                return
            }

            val actionId = parsed.id ?: 0
            if (actionId <= 0) {
                Log.e("API_CALL", "Backend returned direct action without valid id (type=$type time=$time). Skipping schedule.")
                broadcastHint("Ошибка: сервер прислал задачу без id (type=$type). Задача отменена.")
                // Report to backend as cancellation with reason (actionId=0 is an error-report convention).
                if (deviceId.isNotBlank()) {
                    serviceScope.launch {
                        try {
                            ApiClient.instance.ack(
                                AckRequest(
                                    deviceId = deviceId,
                                    actionId = 0,
                                    status = "cancelled",
                                    ack = mapOf(
                                        "reason" to "invalid_action_id_from_backend_command_response",
                                        "error" to "direct /command action id must be > 0",
                                        "received_type" to type,
                                        "received_time" to time,
                                        "received_text" to (parsed.text ?: ""),
                                    )
                                )
                            )
                        } catch (t: Throwable) {
                            Log.w("ACK", "Failed to report invalid /command action id: ${t.message}")
                        }
                    }
                }
                return
            }

            val pa = PendingAction(
                id = actionId,
                type = type,
                time = time,
                text = parsed.text,
            )
            val scheduledIds = PendingActionsScheduler.scheduleAll(applicationContext, deviceId, listOf(pa))
            // ACK each action scheduled via direct command response.
            // Without this, backend status stays "pending" forever since syncNow won't see it in /pending-actions.
            if (scheduledIds.isNotEmpty() && deviceId.isNotBlank()) {
                serviceScope.launch {
                    for (id in scheduledIds) {
                        try {
                            ApiClient.instance.ack(
                                AckRequest(
                                    deviceId = deviceId,
                                    actionId = id,
                                    status = "scheduled",
                                    ack = mapOf(
                                        "reason" to "scheduled",
                                        "source" to "direct_command_response",
                                        "scheduled_at" to java.time.Instant.now().toString(),
                                    )
                                )
                            )
                        } catch (t: Throwable) {
                            Log.w("ACK", "Failed to ack direct-action id=$id: ${t.message}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("API_CALL", "Direct-action parse/schedule failed: ${t.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_WAKE -> {
                Log.i("WAKEWORD", "Pause wake listening requested")
                UserSettingsStore.setWakeListeningEnabled(applicationContext, false)
                mainHandler.removeCallbacks(wakeWordWatchdog)
                stopWakeWordSafely()
                broadcastStatusUpdate("Прослушка вейк-фразы отключена")
                updateForegroundNotification()
            }
            ACTION_RESUME_WAKE -> {
                Log.i("WAKEWORD", "Resume wake listening requested")
                UserSettingsStore.setWakeListeningEnabled(applicationContext, true)
                mainHandler.removeCallbacks(wakeWordWatchdog)
                mainHandler.postDelayed(wakeWordWatchdog, wakeWordWatchdogIntervalMs)
                if (!isContinuousListening) {
                    startWakeWord()
                }
                updateForegroundNotification()
            }
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_SPEAK_TEXT) ?: return START_STICKY
                Log.i("SVC_SPEAK", "speak requested: $text")
                speakOnMain(text, TextToSpeech.QUEUE_ADD)
            }
            ACTION_PROCESS_RECOGNIZED_TEXT -> {
                val text = intent.getStringExtra(EXTRA_RECOGNIZED_TEXT) ?: return START_STICKY
                val source = intent.getStringExtra(EXTRA_RECOGNIZED_SOURCE) ?: "unknown"
                processRecognizedText(text = text, source = source)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        cancelFinalize()
        mainHandler.removeCallbacks(wakeWordWatchdog)
        releaseWakeLock()
        stopLocationUpdates()
        stopNetworkWatcher()
        stopWakeWordSafely()
        destroySpeechRecognizerSafely()
        unregisterWatchConnectionReceiver()
        try {
            tts?.stop()
        } catch (_: Throwable) {
            // ignore
        }
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
            // ignore
        } finally {
            tts = null
            ttsReady = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "VOICE_REC_CHANNEL", "Voice Recognition Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val wakeEnabled = UserSettingsStore.getWakeListeningEnabled(applicationContext)
        val watchConnected = WatchConnectionStore.isConnected(applicationContext) || isWatchConnectedBySystem()
        val text = when {
            wakeEnabled && watchConnected -> "Активна вейк фраза и часы"
            wakeEnabled && !watchConnected -> "Активна вейк фраза"
            !wakeEnabled && watchConnected -> "Часы активны"
            else -> "Прослушка ключевой фразы выключена"
        }
        return NotificationCompat.Builder(this, "VOICE_REC_CHANNEL")
            .setContentTitle("Соня активна")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            // Avoid adaptive mipmap launcher (XML) here: some OEM SystemUI fails to decode it.
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ico_64))
            .build()
    }

    private fun isWatchConnectedBySystem(): Boolean {
        // If user connected watch at system Bluetooth level (paired/connected),
        // but our BLE client isn't running, we still want "Часы активны".
        return try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
            val connected = bm.getConnectedDevices(BluetoothProfile.GATT)
            if (connected.isEmpty()) return false

            val lastAddr = try {
                getSharedPreferences("sonya_watch_ble", Context.MODE_PRIVATE)
                    .getString("last_addr", "")
                    ?.trim()
                    .orEmpty()
            } catch (_: Throwable) {
                ""
            }

            connected.any { dev ->
                val addrOk = try { lastAddr.isNotBlank() && dev.address == lastAddr } catch (_: Throwable) { false }
                val nameOk = try { dev.name == SonyaWatchProtocol.DEVICE_NAME } catch (_: Throwable) { false }
                addrOk || nameOk
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateForegroundNotification() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(1, createNotification())
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun registerWatchConnectionReceiver() {
        if (watchConnReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WatchConnectionStore.ACTION_WATCH_CONNECTION_CHANGED) return
                updateForegroundNotification()
            }
        }
        watchConnReceiver = r
        val f = IntentFilter(WatchConnectionStore.ACTION_WATCH_CONNECTION_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(r, f)
            }
        } catch (_: Throwable) {
            watchConnReceiver = null
        }
    }

    private fun unregisterWatchConnectionReceiver() {
        val r = watchConnReceiver ?: return
        watchConnReceiver = null
        try {
            unregisterReceiver(r)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun enterCommandMode() {
        isWakeVerificationMode = false
        isContinuousListening = true
        cancelFinalize()
        lastActivityAtMs = SystemClock.elapsedRealtime()
        combinedTextBuilder.clear()
        lastPartialForSend = ""
        // Cancel any pending "wake response -> enter command" fallback to avoid double entry.
        wakeTtsUtteranceId = null
        wakeAfterTts = null
        wakeAfterTtsFallback?.let { mainHandler.removeCallbacks(it) }
        wakeAfterTtsFallback = null

        // Fresh recognizer instance helps avoid "busy" edge cases after switching audio users.
        destroySpeechRecognizerSafely()
        ensureSpeechRecognizer()
        recognitionListener?.let { speechRecognizer?.setRecognitionListener(it) }

        broadcastStatusUpdate("Слушаю вашу команду...")
        // Start ASAP to avoid missing the first word after wake.
        startListeningChunk(delayMs = 0L)
    }

    private fun broadcastRecognitionResult(text: String) {
        sendBroadcast(Intent(RECOGNITION_RESULT_ACTION).putExtra(RECOGNITION_RESULT_TEXT, text))
    }

    private fun broadcastPartialRecognitionResult(text: String) {
        sendBroadcast(Intent(PARTIAL_RECOGNITION_RESULT_ACTION).putExtra(PARTIAL_RECOGNITION_RESULT_TEXT, text))
    }

    private fun broadcastStatusUpdate(status: String) {
        sendBroadcast(Intent(STATUS_UPDATE_ACTION).putExtra(STATUS_UPDATE_TEXT, status))
    }

    private fun broadcastHint(message: String) {
        sendBroadcast(Intent(HINT_UPDATE_ACTION).putExtra(HINT_UPDATE_TEXT, message))
    }

    private fun markActivity() {
        lastActivityAtMs = SystemClock.elapsedRealtime()
        cancelFinalize()
    }

    private fun cancelFinalize() {
        finalizeRunnable?.let { mainHandler.removeCallbacks(it) }
        finalizeRunnable = null
    }

    private fun speakWakeThenEnterCommandMode() {
        // If TTS isn't ready, fall back to immediate recording.
        val t = tts
        if (!ttsReady || t == null) {
            enterCommandMode()
            return
        }
        val phrase = VoiceResponsesConfig.pickWakeResponse(applicationContext)
        val utterId = "sonya_wake_" + SystemClock.elapsedRealtime().toString()
        wakeTtsUtteranceId = utterId
        val next = Runnable { enterCommandMode() }
        wakeAfterTts = next
        // Fallback: some devices don't reliably call onDone(). Start recording anyway after a short timeout.
        wakeAfterTtsFallback?.let { mainHandler.removeCallbacks(it) }
        wakeAfterTtsFallback = Runnable {
            if (wakeTtsUtteranceId == utterId) {
                Log.w("WAKEWORD", "TTS onDone timeout; entering command mode by fallback")
                wakeTtsUtteranceId = null
                wakeAfterTts = null
                wakeAfterTtsFallback = null
                next.run()
            }
        }
        mainHandler.postDelayed(wakeAfterTtsFallback!!, 1500L)
        mainHandler.post {
            try {
                // Flush everything and speak wake phrase first; we'll enter command mode on TTS onDone.
                t.stop()
                t.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, utterId)
            } catch (_: Throwable) {
                // If TTS fails, continue anyway.
                wakeTtsUtteranceId = null
                wakeAfterTts = null
                wakeAfterTtsFallback?.let { mainHandler.removeCallbacks(it) }
                wakeAfterTtsFallback = null
                next.run()
            }
        }
    }

    private fun abortCommandMode(hint: String, voice: String? = null) {
        combinedTextBuilder.clear()
        lastPartialForSend = ""
        broadcastHint(hint)
        // Optional voice feedback (e.g. when user said "отбой").
        if (!voice.isNullOrBlank()) {
            vibrateVoiceAck()
            speakOnMain(voice)
            // Give TTS a moment; also reduces chance of wake-word engine catching our own speech.
            startWakeWordDelayed(1200L)
        } else {
            // Возвращаемся в режим ожидания (wake-word). startWakeWordDelayed сам остановит/уничтожит SpeechRecognizer.
            startWakeWordDelayed(0L)
        }
    }

    /**
     * Ищет последнюю позицию [phrase] в [text] (ignoreCase), но только если совпадение ограничено
     * не-буквенно-цифровыми символами (или границами строки). Это нужно, чтобы "стоп" не матчился
     * внутри других слов.
     *
     * Возвращает индекс начала совпадения в исходной строке или -1.
     */
    private fun lastIndexOfPhraseAsWords(text: String, phrase: String): Int {
        var idx = text.lastIndexOf(phrase, ignoreCase = true)
        while (idx != -1) {
            val beforeOk = idx == 0 || !text[idx - 1].isLetterOrDigit()
            val afterIdx = idx + phrase.length
            val afterOk = afterIdx >= text.length || !text[afterIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return idx
            idx = text.lastIndexOf(phrase, startIndex = idx - 1, ignoreCase = true)
        }
        return -1
    }

    private fun scheduleFinalize(delayMs: Long) {
        cancelFinalize()
        finalizeRunnable = Runnable {
            if (!isContinuousListening) return@Runnable
            finalizeNow()
        }
        mainHandler.postDelayed(finalizeRunnable!!, delayMs)
    }

    private fun finalizeNow() {
        val finishPhrase = finalizeRequestedByFinishPhrase
        finalizeRequestedByFinishPhrase = null

        val finalText = combinedTextBuilder.toString().trim()
        if (finalText.isNotBlank()) {
            Log.d("SPEECH_RECOGNIZER", "Final stitched result: '$finalText'")
            // Unified path for any recognized text.
            processRecognizedText(text = finalText, source = "phone_speechrecognizer")
        } else if (finishPhrase != null) {
            Log.w(
                "SPEECH_RECOGNIZER",
                "Finish phrase '$finishPhrase' requested finalize, but finalText is blank. " +
                    "combined='${combinedTextBuilder}' lastPartialForSend='$lastPartialForSend'"
            )
            broadcastHint("Не расслышала команду до «$finishPhrase». Повтори ещё раз.")
        }
        lastPartialForSend = ""
        isContinuousListening = false
        cancelFinalize()
        stopSpeechRecognizerSafely()
        // Важно: на некоторых девайсах SpeechRecognizer держит микрофон даже после cancel/stop.
        // Поэтому destroy() перед возвратом к wake word.
        destroySpeechRecognizerSafely()
        // Give TTS a moment; also reduces chance of wake-word engine catching our own speech.
        startWakeWordDelayed(1200L)
    }

    private fun processRecognizedText(text: String, source: String) {
        val t = text.trim()
        if (t.isBlank()) return
        Log.i("ORCH", "processRecognizedText source=$source text='${t.take(180)}'")

        // UX: immediate "heard you" confirm.
        vibrateVoiceAck()
        speakOnMain("Услышала", queueMode = TextToSpeech.QUEUE_FLUSH)
        broadcastRecognitionResult(t)

        // Backend + "Всё ОК" confirm + sync happen inside sendRequest()/PendingActionsSync.
        sendToServer(t)
    }

    private fun enqueuePendingTts(text: String, queueMode: Int) {
        synchronized(pendingTtsQueue) {
            if (pendingTtsQueue.size >= pendingTtsMax) {
                pendingTtsQueue.removeFirst()
            }
            pendingTtsQueue.addLast(text to queueMode)
        }
        Log.i("TTS", "queued '${text.take(60)}' mode=$queueMode")
    }

    private fun flushPendingTts() {
        val items: List<Pair<String, Int>> = synchronized(pendingTtsQueue) {
            val out = pendingTtsQueue.toList()
            pendingTtsQueue.clear()
            out
        }
        if (items.isEmpty()) return
        Log.i("TTS", "flushPendingTts count=${items.size}")
        for ((text, mode) in items) {
            speakOnMain(text, queueMode = mode)
        }
    }

    private fun speakOnMain(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!ttsReady || tts == null) {
            Log.i("TTS", "not ready -> queue '${text.take(60)}'")
            enqueuePendingTts(text, queueMode)
            return
        }
        val t = tts ?: return
        mainHandler.post {
            try {
                if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                    t.stop()
                }
                val utterId = "sonya_voice_ack_" + SystemClock.elapsedRealtime().toString()
                Log.i("TTS", "speak id=$utterId mode=$queueMode text='${text.take(80)}'")
                t.speak(text, queueMode, null, utterId)
            } catch (e: Throwable) {
                Log.w("TTS", "speak failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun vibrateVoiceAck() {
        try {
            if (!UserSettingsStore.getVibrateOnConfirm(applicationContext)) return
        } catch (_: Throwable) {
            return
        }

        // Keep it subtle.
        val durationMs = 35L
        mainHandler.post {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(VibratorManager::class.java) ?: return@post
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    (getSystemService(VIBRATOR_SERVICE) as? Vibrator) ?: return@post
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun startListeningChunk(delayMs: Long = 0L) {
        val task = Runnable {
            if (!isContinuousListening) return@Runnable
            try {
                ensureSpeechRecognizer()
                speechRecognizer?.startListening(createSpeechRecognizerIntent())
            } catch (t: Throwable) {
                Log.e("SPEECH_RECOGNIZER", "Failed to startListening: ${t.message}", t)
            }
        }
        if (delayMs <= 0L) mainHandler.post(task) else mainHandler.postDelayed(task, delayMs)
    }

    private fun stopSpeechRecognizerSafely() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Throwable) {
            // ignore
        }
        try {
            speechRecognizer?.cancel()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun recreateSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Throwable) {
            // ignore
        }
        speechRecognizer = null
        ensureSpeechRecognizer()
    }

    private fun ensureSpeechRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognitionListener?.let { speechRecognizer?.setRecognitionListener(it) }
    }

    private fun destroySpeechRecognizerSafely() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Throwable) {
            // ignore
        } finally {
            speechRecognizer = null
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            if (wakeLock?.isHeld == true) return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonya_front:VoiceRecognitionService").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w("WAKELOCK", "Failed to acquire wakelock: ${t.message}", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
            // ignore
        } finally {
            wakeLock = null
        }
    }

    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val brand = Build.BRAND
        val device = Build.DEVICE
        val product = Build.PRODUCT

        return fingerprint.startsWith("generic") ||
            fingerprint.lowercase().contains("emulator") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product.contains("sdk_gphone") ||
            product.contains("sdk")
    }

    private fun isDefaultEmulatorLocation(lat: Double, lon: Double): Boolean {
        // Googleplex / Mountain View: 37.4219983, -122.084 (в эмуляторе часто стоит именно так)
        return kotlin.math.abs(lat - 37.4219983) < 0.01 && kotlin.math.abs(lon - (-122.084)) < 0.01
    }

    private fun startWakeWordDelayed(delayMs: Long) {
        isContinuousListening = false
        cancelFinalize()
        stopSpeechRecognizerSafely()
        destroySpeechRecognizerSafely()

        // If wake listening is disabled by user, don't start wake word engine.
        if (!UserSettingsStore.getWakeListeningEnabled(applicationContext)) {
            Log.i("WAKEWORD", "Wake listening disabled by user, skipping wake word start")
            broadcastStatusUpdate("Прослушка вейк-фразы отключена")
            return
        }

        val task = Runnable {
            try {
                stopWakeWordSafely()
                wakeWordEngine = VoskWakeWordEngine(
                    context = applicationContext,
                    onWake = {
                        if (isContinuousListening) return@VoskWakeWordEngine
                        mainHandler.post {
                            // If the phone is producing audio (music, ringtone, call, etc.),
                            // ignore the trigger entirely — it's almost certainly a false positive.
                            if (isPhoneAudioActive()) {
                                Log.i("WAKEWORD", "Wake trigger ignored: phone audio is active (music/ringtone/call)")
                                return@post  // wake-word engine keeps running, will fire again if needed
                            }

                            stopWakeWordSafely()

                            // Speak wake phrase first, then start recording.
                            speakWakeThenEnterCommandMode()
                        }
                    }
                ).also {
                    val started = it.start()
                    if (!started) throw IllegalStateException("Wake word engine failed to start (models/audio)")
                }
                isWakeWordRunning = true
                Log.i("WAKEWORD", "Wake word engine started")
                broadcastStatusUpdate("Готов к работе. Скажите 'соня приём'...")
            } catch (t: Throwable) {
                isWakeWordRunning = false
                Log.e("WAKEWORD", "Failed to start wake word engine: ${t.message}", t)
                val msg = t.message ?: ""
                broadcastStatusUpdate("Ошибка запуска wake word: $msg")
                if (msg.contains("Vosk model not found in assets", ignoreCase = true)) {
                    broadcastHint("Положи Vosk-модель в assets (например: vosk-model-small-ru-0.22), затем пересобери APK.")
                }
            }
        }

        if (delayMs <= 0L) mainHandler.post(task) else mainHandler.postDelayed(task, delayMs)
    }

    private fun stopWakeWordSafely() {
        try {
            wakeWordEngine?.stop()
        } catch (t: Throwable) {
            Log.e("WAKEWORD", "Failed to stop wake word engine: ${t.message}", t)
        } finally {
            wakeWordEngine = null
            isWakeWordRunning = false
        }
    }
}
