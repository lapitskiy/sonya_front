package com.example.sonya_front

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonya_front.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class SonyaWatchUiState(
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val autoConnect: Boolean = true,
    val backendUrl: String = "",
    val lastEvent: String = "",
    val bytesTotal: Long = 0,
    val downloadTotalBytes: Long = 0,
    val downloadOffsetBytes: Long = 0,
    val lastWavPath: String = "",
    val lastTranscript: String = "",
    val lastBackendCommand: String = "",
    val lastUpload: String = "",
    val batteryPercent: Int? = null,
    val batteryMv: Int? = null,
    val vbusMv: Int? = null,
    val charging: Boolean? = null,
    val vbusIn: Boolean? = null,
    val batteryPresent: Boolean? = null,
    val batteryNote: String = "",
    val batteryEtaMinutes: Int? = null,
    val batteryDrainMvPerMin: Int? = null,
    val logTail: List<String> = emptyList(),
)

/**
 * Тонкая UI-обёртка вкладки «Часы».
 *
 * ВНИМАНИЕ: обработка BLE-аудио (фреймы, PCM, WAV, Vosk, отправка команды) теперь живёт в
 * [VoiceRecognitionService] через [SonyaWatchAudioHandler] — это позволяет часам работать,
 * когда экран выключен и эта ViewModel/Activity убиты.
 *
 * Здесь остаётся только:
 *  - управление подключением/сканированием через общий [SonyaWatchBleClient];
 *  - ручные команды (PING/BATT/REC) и загрузка WAV;
 *  - обновление UI-состояния из broadcasts сервиса.
 */
class SonyaWatchViewModel(app: Application) : AndroidViewModel(app), SonyaWatchBleManager.Listener {
    private val _ui = mutableStateOf(
        SonyaWatchUiState(
            backendUrl = "http://188.243.119.154:18000/voice",
            lastEvent = "Ожидаю подключения к часам…",
        )
    )
    val ui: State<SonyaWatchUiState> = _ui

    private lateinit var ble: SonyaWatchBleClient
    private var watchBroadcastReceiver: BroadcastReceiver? = null

    init {
        ble = SonyaWatchBleManager.registerListener(app.applicationContext, this)
        registerWatchBroadcasts()
    }

    override fun onCleared() {
        unregisterWatchBroadcasts()
        SonyaWatchBleManager.unregisterListener(this)
        super.onCleared()
    }

    // ---- SonyaWatchBleManager.Listener: только connected/scanning/log ----

    override fun onWatchLog(line: String) {
        appendLog(line, writeToAppLog = false)
    }

    override fun onWatchConnectedChanged(connected: Boolean) {
        _ui.value = _ui.value.copy(connected = connected)
        appendLog(if (connected) "BLE connected" else "BLE disconnected")
        if (!connected) {
            // Сбрасываем «прилипшие» индикаторы, чтобы UI не выглядел зависшим.
            _ui.value = _ui.value.copy(
                downloadTotalBytes = 0,
                downloadOffsetBytes = 0,
                bytesTotal = 0,
            )
            setEvent("Ожидаю подключения к часам…")
        }
    }

    override fun onWatchGattReady() {
        setEvent("BLE готово (жду PONG)…")
    }

    override fun onWatchScanningChanged(scanning: Boolean) {
        _ui.value = _ui.value.copy(scanning = scanning)
    }

    override fun onWatchNotifyBytes(bytes: ByteArray) {
        // Аудио обрабатывается в сервисе (SonyaWatchAudioHandler). Здесь не трогаем.
    }

    // ---- Подписка на broadcasts сервиса (батарея/прогресс/лог/событие/расшифровка) ----

    private fun registerWatchBroadcasts() {
        val ctx = getApplication<Application>().applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    VoiceRecognitionService.WATCH_BATTERY_ACTION -> handleBatteryBroadcast(intent)
                    VoiceRecognitionService.WATCH_TRANSFER_ACTION -> {
                        val total = intent.getIntExtra(VoiceRecognitionService.EXTRA_TRANSFER_TOTAL, 0)
                        val off = intent.getIntExtra(VoiceRecognitionService.EXTRA_TRANSFER_OFFSET, 0)
                        val bytes = intent.getLongExtra(VoiceRecognitionService.EXTRA_TRANSFER_BYTES, 0L)
                        _ui.value = _ui.value.copy(
                            bytesTotal = bytes,
                            downloadTotalBytes = total.toLong(),
                            downloadOffsetBytes = off.toLong(),
                        )
                    }
                    VoiceRecognitionService.WATCH_EVENT_ACTION -> {
                        val e = intent.getStringExtra(VoiceRecognitionService.EXTRA_WATCH_EVENT) ?: return
                        setEvent(e)
                    }
                    VoiceRecognitionService.WATCH_LOG_ACTION -> {
                        val line = intent.getStringExtra(VoiceRecognitionService.EXTRA_WATCH_LOG) ?: return
                        appendLog(line, writeToAppLog = false)
                    }
                    VoiceRecognitionService.WATCH_TRANSCRIPT_ACTION -> {
                        val t = intent.getStringExtra(VoiceRecognitionService.EXTRA_WATCH_TRANSCRIPT) ?: ""
                        _ui.value = _ui.value.copy(lastTranscript = t)
                    }
                    VoiceRecognitionService.WATCH_WAV_PATH_ACTION -> {
                        val p = intent.getStringExtra(VoiceRecognitionService.EXTRA_WATCH_WAV_PATH) ?: ""
                        setLastWavPath(p)
                    }
                    VoiceRecognitionService.STATUS_UPDATE_ACTION -> {
                        // Статусная строка из сервиса («Часы: запись началась» и т.п.).
                        val s = intent.getStringExtra(VoiceRecognitionService.STATUS_UPDATE_TEXT) ?: return
                        if (s.startsWith("Часы:")) {
                            // backend command status отражаем в отдельном поле, как раньше.
                            _ui.value = _ui.value.copy(
                                lastBackendCommand = when {
                                    s.contains("отправляю команду", ignoreCase = true) -> "SENT (via service)"
                                    s.contains("не распознана", ignoreCase = true) -> "ERR: blank transcript"
                                    s.contains("ошибка", ignoreCase = true) -> "ERR: $s"
                                    else -> _ui.value.lastBackendCommand
                                }
                            )
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(VoiceRecognitionService.WATCH_BATTERY_ACTION)
            addAction(VoiceRecognitionService.WATCH_TRANSFER_ACTION)
            addAction(VoiceRecognitionService.WATCH_EVENT_ACTION)
            addAction(VoiceRecognitionService.WATCH_LOG_ACTION)
            addAction(VoiceRecognitionService.WATCH_TRANSCRIPT_ACTION)
            addAction(VoiceRecognitionService.WATCH_WAV_PATH_ACTION)
            addAction(VoiceRecognitionService.STATUS_UPDATE_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
        } catch (t: Throwable) {
            Log.w(SonyaWatchProtocol.TAG, "registerWatchBroadcasts failed: ${t.message}", t)
        }
        watchBroadcastReceiver = r
    }

    private fun unregisterWatchBroadcasts() {
        val r = watchBroadcastReceiver ?: return
        watchBroadcastReceiver = null
        try {
            getApplication<Application>().unregisterReceiver(r)
        } catch (_: Throwable) {
        }
    }

    private fun handleBatteryBroadcast(intent: Intent) {
        fun has(name: String) = intent.hasExtra(name)
        _ui.value = _ui.value.copy(
            batteryPercent = if (has(VoiceRecognitionService.EXTRA_BATT_PCT))
                intent.getIntExtra(VoiceRecognitionService.EXTRA_BATT_PCT, 0) else null,
            batteryMv = if (has(VoiceRecognitionService.EXTRA_BATT_MV))
                intent.getIntExtra(VoiceRecognitionService.EXTRA_BATT_MV, 0) else null,
            vbusMv = if (has(VoiceRecognitionService.EXTRA_BATT_VBUS))
                intent.getIntExtra(VoiceRecognitionService.EXTRA_BATT_VBUS, 0) else null,
            charging = if (has(VoiceRecognitionService.EXTRA_BATT_CHG))
                intent.getBooleanExtra(VoiceRecognitionService.EXTRA_BATT_CHG, false) else null,
            vbusIn = if (has(VoiceRecognitionService.EXTRA_BATT_IN))
                intent.getBooleanExtra(VoiceRecognitionService.EXTRA_BATT_IN, false) else null,
            batteryPresent = if (has(VoiceRecognitionService.EXTRA_BATT_PRESENT))
                intent.getBooleanExtra(VoiceRecognitionService.EXTRA_BATT_PRESENT, false) else null,
            batteryEtaMinutes = if (has(VoiceRecognitionService.EXTRA_BATT_ETA_MIN))
                intent.getIntExtra(VoiceRecognitionService.EXTRA_BATT_ETA_MIN, 0) else null,
            batteryDrainMvPerMin = if (has(VoiceRecognitionService.EXTRA_BATT_DRAIN))
                intent.getIntExtra(VoiceRecognitionService.EXTRA_BATT_DRAIN, 0) else null,
            batteryNote = intent.getStringExtra(VoiceRecognitionService.EXTRA_BATT_NOTE) ?: "",
        )
    }

    // ---- Управление подключением / ручные команды ----

    fun setAppVisible(visible: Boolean) {
        // Реальное авто-подключение теперь контролирует сервис; здесь только подстраховочно
        // дёргаем политику, чтобы UI-чекбокс работал предсказуемо.
        applyAutoConnect()
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        _ui.value = _ui.value.copy(autoConnect = enabled)
        applyAutoConnect()
        if (enabled) {
            ble.kickAutoConnectNow()
        }
    }

    private fun applyAutoConnect() {
        ble.setAutoConnectEnabled(_ui.value.autoConnect)
    }

    fun setBackendUrl(url: String) {
        _ui.value = _ui.value.copy(backendUrl = url)
    }

    fun scanAndConnect() {
        appendLog("scanAndConnect()")
        if (!_ui.value.autoConnect) {
            _ui.value = _ui.value.copy(autoConnect = true)
            applyAutoConnect()
        }
        ble.scanAndConnect(force = true)
    }

    fun disconnect() {
        appendLog("disconnect()")
        ble.disconnect()
        _ui.value = _ui.value.copy(scanning = false, connected = false, autoConnect = false)
    }

    fun sendPing() {
        ble.writeAsciiCommand("PING")
    }

    fun sendBatt() {
        ble.writeAsciiCommand("BATT")
    }

    fun sendSetRec2() {
        ble.writeAsciiCommand("SETREC:2")
    }

    fun sendRec() {
        ble.writeAsciiCommand("REC")
    }

    fun uploadLastWav() {
        val url = _ui.value.backendUrl.trim()
        val f = lastWavFile
        if (url.isBlank()) {
            appendLog("upload: URL is blank")
            _ui.value = _ui.value.copy(lastUpload = "URL пустой")
            return
        }
        if (f == null || !f.exists()) {
            appendLog("upload: no wav file yet")
            _ui.value = _ui.value.copy(lastUpload = "WAV ещё не создан")
            return
        }

        appendLog("upload: start url=$url file=${f.absolutePath} size=${f.length()}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = SonyaWatchUploader.uploadWav(url, f)
                Log.i(SonyaWatchProtocol.TAG, "upload: http=${res.httpCode} bodyPrefix='${res.bodyPrefix}'")
                viewModelScope.launch(Dispatchers.Main) {
                    _ui.value = _ui.value.copy(lastUpload = "HTTP ${res.httpCode}: ${res.bodyPrefix}")
                }
            } catch (t: Throwable) {
                Log.w(SonyaWatchProtocol.TAG, "upload failed: ${t.javaClass.simpleName}: ${t.message}", t)
                viewModelScope.launch(Dispatchers.Main) {
                    _ui.value = _ui.value.copy(lastUpload = "Ошибка: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private var lastWavFile: java.io.File? = null
    fun setLastWavPath(path: String) {
        lastWavFile = if (path.isBlank()) null else java.io.File(path)
        _ui.value = _ui.value.copy(lastWavPath = path)
    }

    private fun setEvent(s: String) {
        _ui.value = _ui.value.copy(lastEvent = s)
        appendLog("event: $s")
    }

    private fun appendLog(line: String, writeToAppLog: Boolean = true) {
        if (writeToAppLog) Log.i(SonyaWatchProtocol.TAG, line)
        val cur = _ui.value
        val next = (cur.logTail + line).takeLast(60)
        _ui.value = cur.copy(logTail = next)
    }
}
