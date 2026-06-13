package com.example.sonya_front

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.example.sonya_front.AppLog as Log
import androidx.core.content.ContextCompat
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

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

class SonyaWatchViewModel(app: Application) : AndroidViewModel(app), SonyaWatchBleManager.Listener {
    private val _ui = mutableStateOf(
        SonyaWatchUiState(
            backendUrl = "http://188.243.119.154:18000/voice",
            lastEvent = "Ожидаю подключения к часам…",
        )
    )
    val ui: State<SonyaWatchUiState> = _ui

    private val frameParser = SonyaWatchFrameParser()
    private val pcm = ByteArrayOutputStream(256 * 1024)
    private var recording = false
    private var expectedSeq: Int? = null
    private var lastWavFile: File? = null

    private data class RecMeta(
        val recId: Int,
        val totalBytes: Int,
        val crc32: Long,
        val sampleRate: Int,
    )
    private var pendingMeta: RecMeta? = null
    private var pendingOffset: Int = 0
    private var downloading: Boolean = false
    private var pullTimeoutJob: Job? = null
    private var lastGetSentAtMs: Long = 0L
    private val pullWindowBytes: Int = 16 * 1024
    private var pendingWindowEndOffset: Int = 0
    private var lastAudioDataAtMs: Long = 0L
    private var pullStartAtMs: Long = 0L
    private var pullLastReportAtMs: Long = 0L
    private var pullBytesAtLastReport: Int = 0
    private var liveRecId: Int = -1
    private var lastBroadcastProgressPct: Int = -1
    private var pendingDoneRecId: Int = -1
    private var doneRetryJob: Job? = null
    private data class BattPoint(val atMs: Long, val mv: Int)
    private val battHistory = ArrayList<BattPoint>(16)
    private var readyVibrationPending = false

    private lateinit var ble: SonyaWatchBleClient

    init {
        ble = SonyaWatchBleManager.registerListener(app.applicationContext, this)
    }

    override fun onCleared() {
        SonyaWatchBleManager.unregisterListener(this)
        super.onCleared()
    }

    override fun onWatchLog(line: String) {
        appendLog(line, writeToAppLog = false)
    }

    override fun onWatchConnectedChanged(connected: Boolean) {
        val cur = _ui.value
        _ui.value = cur.copy(connected = connected)
        appendLog(if (connected) "BLE connected" else "BLE disconnected")
        if (connected) {
            readyVibrationPending = true
            // Distinguish "GATT connected" from "protocol is alive".
            setEvent("BLE подключено (жду PONG)…")
            // RX/TX characteristics might not be ready immediately; retry a couple of times.
            viewModelScope.launch {
                delay(600L)
                sendPing()
                delay(1200L)
                if (_ui.value.connected) {
                    sendPing()
                }
            }
        } else {
            readyVibrationPending = false
            // Reset protocol state so UI doesn't look "stuck".
            recording = false
            downloading = false
            expectedSeq = null
            pendingMeta = null
            pendingOffset = 0
            liveRecId = -1
            lastBroadcastProgressPct = -1
            pendingDoneRecId = -1
            doneRetryJob?.cancel()
            doneRetryJob = null
            pullTimeoutJob?.cancel()
            pullTimeoutJob = null
            _ui.value = _ui.value.copy(downloadTotalBytes = 0, downloadOffsetBytes = 0, bytesTotal = 0)
            setEvent("Ожидаю подключения к часам…")
        }
    }

    override fun onWatchScanningChanged(scanning: Boolean) {
        val cur = _ui.value
        _ui.value = cur.copy(scanning = scanning)
    }

    override fun onWatchNotifyBytes(bytes: ByteArray) {
        onNotify(bytes)
    }

    fun setAppVisible(visible: Boolean) {
        // Keep BLE auto-connect independent from Activity visibility:
        // watch commands should still be accepted while screen is off.
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
        // Manual scan should also enable auto-reconnect while app is visible.
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
        recording = false
        downloading = false
        expectedSeq = null
        pendingMeta = null
        pendingOffset = 0
        pendingDoneRecId = -1
        doneRetryJob?.cancel()
        doneRetryJob = null
        battHistory.clear()
        _ui.value = _ui.value.copy(downloadTotalBytes = 0, downloadOffsetBytes = 0, bytesTotal = 0)
    }

    fun sendPing() {
        ble.writeAsciiCommand("PING")
        sendTimeSync()
    }

    private fun sendTimeSync() {
        val nowMs = System.currentTimeMillis()
        val epochSec = nowMs / 1000L
        val tzOffsetMin = TimeZone.getDefault().getOffset(nowMs) / 60_000
        ble.writeAsciiCommand("TIME:$epochSec:$tzOffsetMin")
    }

    fun sendSetRec2() {
        ble.writeAsciiCommand("SETREC:2")
    }

    fun sendRec() {
        ble.writeAsciiCommand("REC")
    }

    fun sendBatt() {
        ble.writeAsciiCommand("BATT")
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

    private fun onNotify(bytes: ByteArray) {
        val frames = frameParser.push(bytes)
        if (frames.isEmpty()) return
        frames.forEach { handleFrame(it) }
    }

    private fun handleFrame(f: SonyaWatchFrame) {
        val typeName = when (f.type) {
            SonyaWatchProtocol.EVT_WAKE -> "EVT_WAKE"
            SonyaWatchProtocol.EVT_REC_START -> "EVT_REC_START"
            SonyaWatchProtocol.EVT_REC_END -> "EVT_REC_END"
            SonyaWatchProtocol.AUDIO_CHUNK -> "AUDIO_CHUNK"
            SonyaWatchProtocol.AUDIO_DATA -> "AUDIO_DATA"
            SonyaWatchProtocol.EVT_ERROR -> "EVT_ERROR"
            else -> "0x" + f.type.toString(16)
        }

        // For pull-based AUDIO_DATA we validate by (recId, offset) and should not fail
        // due to unrelated frame ordering; keep seq checking for legacy streaming only.
        if (expectedSeq != null && f.type != SonyaWatchProtocol.AUDIO_DATA) {
            val exp = expectedSeq ?: 0
            if (f.seq != exp) {
                appendLog("seq mismatch: got=${f.seq} expected=$exp type=$typeName")
            }
            expectedSeq = (f.seq + 1) and 0xFFFF
        }

        when (f.type) {
            SonyaWatchProtocol.EVT_WAKE -> {
                appendLog("wake: rec=$recording dl=$downloading pendingMeta=${pendingMeta?.recId ?: -1} off=$pendingOffset")
                setEvent("$typeName seq=${f.seq}")
            }

            SonyaWatchProtocol.EVT_REC_START -> {
                appendLog("rec_start: before rec=$recording dl=$downloading pendingMeta=${pendingMeta?.recId ?: -1} off=$pendingOffset liveRecId=$liveRecId")
                recording = true
                downloading = false
                expectedSeq = (f.seq + 1) and 0xFFFF
                pcm.reset()
                pendingMeta = null
                pendingOffset = 0
                liveRecId = -1
                pullTimeoutJob?.cancel()
                pullTimeoutJob = null
                pullStartAtMs = System.currentTimeMillis()
                pullLastReportAtMs = 0L
                pullBytesAtLastReport = 0
                lastBroadcastProgressPct = -1
                _ui.value = _ui.value.copy(bytesTotal = 0, downloadTotalBytes = 0, downloadOffsetBytes = 0)
                setEvent("$typeName seq=${f.seq}")
                broadcastStatus("Часы: запись началась")
            }

            SonyaWatchProtocol.AUDIO_CHUNK -> {
                if (!recording) {
                    appendLog("AUDIO_CHUNK while not recording: ${f.payload.size} bytes")
                    return
                }
                pcm.write(f.payload)
                val total = pcm.size().toLong()
                _ui.value = _ui.value.copy(bytesTotal = total)
                if (total % (64 * 1024) < f.payload.size) {
                    appendLog("audio bytes total=$total")
                }
            }

            SonyaWatchProtocol.AUDIO_DATA -> {
                if (f.payload.size < 6) {
                    appendLog("AUDIO_DATA too short: ${f.payload.size}")
                    return
                }
                val recId = u16le(f.payload, 0)
                val off = u32le(f.payload, 2)
                val data = f.payload.copyOfRange(6, f.payload.size)

                if (recording && pendingMeta == null) {
                    // Live mode: data arriving while still recording
                    if (liveRecId == -1) {
                        liveRecId = recId
                        appendLog("live: first frame recId=$recId len=${data.size}")
                    }
                    if (recId != liveRecId) return
                    if (off != pendingOffset) {
                        appendLog("live: offset gap expected=$pendingOffset got=$off, padding ${off - pendingOffset}B")
                        val gap = off - pendingOffset
                        if (gap in 1..4096) {
                            pcm.write(ByteArray(gap))
                            pendingOffset += gap
                        } else {
                            return
                        }
                    }
                    pcm.write(data)
                    pendingOffset += data.size
                    _ui.value = _ui.value.copy(bytesTotal = pcm.size().toLong())
                    reportThroughput(data.size)
                    return
                }

                // Pull mode: downloading after REC_END
                val m = pendingMeta
                if (m == null) {
                    appendLog("AUDIO_DATA but no context: ${f.payload.size} bytes")
                    return
                }
                pullTimeoutJob?.cancel()
                pullTimeoutJob = null
                lastAudioDataAtMs = System.currentTimeMillis()
                if (recId != m.recId) return
                if (off != pendingOffset) {
                    appendLog("pull: offset mismatch got=$off expected=$pendingOffset")
                    requestWindow(fromOffset = pendingOffset)
                    return
                }
                pcm.write(data)
                pendingOffset += data.size
                _ui.value = _ui.value.copy(
                    bytesTotal = pcm.size().toLong(),
                    downloadTotalBytes = m.totalBytes.toLong(),
                    downloadOffsetBytes = pendingOffset.toLong()
                )
                reportThroughput(data.size)
                maybeBroadcastTransferProgress(total = m.totalBytes, offset = pendingOffset)

                if (pendingOffset < m.totalBytes) {
                    if (pendingOffset >= pendingWindowEndOffset) {
                        requestWindow(fromOffset = pendingOffset)
                    } else {
                        schedulePullTimeout()
                    }
                } else {
                    finalizeDone(m)
                }
            }

            SonyaWatchProtocol.EVT_REC_END -> {
                setEvent("$typeName seq=${f.seq}")
                broadcastStatus("Часы: запись завершена, принимаю данные")
                val meta = parseRecEndMeta(f.payload)
                if (meta == null) {
                    recording = false
                    downloading = false
                    expectedSeq = null
                    val pcmBytes = pcm.toByteArray()
                    appendLog("rec end (legacy): pcmBytes=${pcmBytes.size}")
                    saveWav(pcmBytes)
                } else {
                    recording = false
                    appendLog("rec meta: recId=${meta.recId} totalBytes=${meta.totalBytes} crc32=0x${meta.crc32.toString(16)} sr=${meta.sampleRate} liveGot=$pendingOffset")

                    if (pendingOffset >= meta.totalBytes) {
                        appendLog("live: all data received, finalizing immediately")
                        finalizeDone(meta)
                    } else {
                        pendingMeta = meta
                        pendingWindowEndOffset = 0
                        downloading = true
                        lastAudioDataAtMs = 0L
                        _ui.value = _ui.value.copy(
                            downloadTotalBytes = meta.totalBytes.toLong(),
                            downloadOffsetBytes = pendingOffset.toLong()
                        )
                        val remaining = meta.totalBytes - pendingOffset
                        appendLog("live: missing ${remaining}B from off=$pendingOffset, pulling remainder")
                        requestWindow(fromOffset = pendingOffset)
                    }
                }
            }

            SonyaWatchProtocol.EVT_ERROR -> {
                val msg = try {
                    f.payload.toString(Charsets.US_ASCII)
                } catch (_: Throwable) {
                    "<decode error>"
                }
                val m = msg.trim()
                val isBatt = m.startsWith("BATT:")
                if (isBatt) {
                    handleBatteryInfo(m)
                    return
                }
                val isInfo = m == "PONG" || m == "TIME_OK" || m == "TIME_REQ" || m.startsWith("REC_SEC=")
                if (m.startsWith("DONE_OK:")) {
                    val id = m.removePrefix("DONE_OK:").toIntOrNull()
                    if (id != null && id == pendingDoneRecId) {
                        appendLog("done ack: recId=$id")
                        pendingDoneRecId = -1
                        doneRetryJob?.cancel()
                        doneRetryJob = null
                    }
                    return
                }
                if (m == "REC_BUSY:WAIT_DONE") {
                    appendLog("watch busy: waiting DONE, retrying last DONE if needed")
                    if (pendingDoneRecId > 0) {
                        ble.writeAsciiCommand("DONE:$pendingDoneRecId")
                    }
                    setEvent("WATCH: $m")
                    return
                }
                if (isInfo) {
                    appendLog("watch: '$m'")
                    setEvent("WATCH: $m")
                    if (m == "PONG" || m == "TIME_REQ") {
                        sendTimeSync()
                    }
                    if (m == "PONG" && readyVibrationPending) {
                        readyVibrationPending = false
                        vibrateReadyPulse()
                        speakWatchReadyPhrase()
                    }
                } else {
                    appendLog("watch error: '$m'")
                    setEvent("$typeName: $m")
                }
            }

            else -> {
                appendLog("frame $typeName seq=${f.seq} len=${f.payload.size}")
            }
        }
    }

    private fun handleBatteryInfo(msg: String) {
        if (msg.startsWith("BATT:err=")) {
            appendLog("watch battery error: $msg")
            setEvent("WATCH: $msg")
            return
        }
        val body = msg.removePrefix("BATT:")
        val pairs = body.split(",")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0 || idx >= part.length - 1) null else {
                    part.substring(0, idx).trim() to part.substring(idx + 1).trim()
                }
            }
            .toMap()
        val pct = pairs["pct"]?.toIntOrNull()
        val bmv = pairs["bmv"]?.toIntOrNull()
        val vbus = pairs["vbus"]?.toIntOrNull()
        val chg = when (pairs["chg"]) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val vin = when (pairs["in"]) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val bat = when (pairs["bat"]) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val nowMs = System.currentTimeMillis()
        val chargingNow = (vin == true) || (chg == true) || ((vbus ?: 0) >= 3900)
        val etaMin: Int?
        val drainRateInt: Int?
        if (chargingNow || bmv == null) {
            etaMin = null
            drainRateInt = null
            battHistory.clear()
        } else {
            // Keep a short history window and estimate discharge by trend (not only by "down" steps).
            battHistory.add(BattPoint(nowMs, bmv))
            // 8 minutes window with 30s polling ~= up to 16 points.
            val minTs = nowMs - 8 * 60_000L
            while (battHistory.isNotEmpty() && battHistory.first().atMs < minTs) {
                battHistory.removeAt(0)
            }
            while (battHistory.size > 16) {
                battHistory.removeAt(0)
            }

            val rate = estimateDrainRateMvPerMin(battHistory)
            drainRateInt = rate?.toInt()
            val vMin = 3500
            etaMin = if (rate != null && rate >= 1.0) {
                val remainingMv = (bmv - vMin).coerceAtLeast(0)
                if (remainingMv == 0) 0 else ceil(remainingMv.toDouble() / rate).toInt().coerceIn(1, 24 * 60)
            } else {
                null
            }
        }
        val note = when {
            bat == false -> "АКБ не обнаружен"
            chargingNow -> "Заряжается"
            pct == 0 && (bmv ?: 0) >= 3600 -> "Процент PMU недостоверен, смотрите mV"
            else -> ""
        }
        _ui.value = _ui.value.copy(
            batteryPercent = pct,
            batteryMv = bmv,
            vbusMv = vbus,
            charging = chg,
            vbusIn = vin,
            batteryPresent = bat,
            batteryNote = note,
            batteryEtaMinutes = etaMin,
            batteryDrainMvPerMin = drainRateInt
        )
        appendLog("watch battery: pct=$pct bmv=$bmv vbus=$vbus chg=$chg in=$vin bat=$bat")
        setEvent("WATCH: battery ${pct ?: "?"}%")
    }

    private fun finalizeDone(m: RecMeta) {
        appendLog("finalize: recId=${m.recId} expected=${m.totalBytes} got=${pcm.size()} off=$pendingOffset")
        sendDoneWithRetry(m.recId)
        recording = false
        downloading = false
        expectedSeq = null
        liveRecId = -1
        pullTimeoutJob?.cancel()
        pullTimeoutJob = null
        val pcmBytes = pcm.toByteArray()
        appendLog("rec done: pcmBytes=${pcmBytes.size} expected=${m.totalBytes}")
        broadcastStatus("Часы: данные получены, распознаю речь")
        saveWav(pcmBytes)
    }

    private fun sendDoneWithRetry(recId: Int) {
        pendingDoneRecId = recId
        doneRetryJob?.cancel()
        ble.writeAsciiCommand("DONE:$recId")
        doneRetryJob = viewModelScope.launch(Dispatchers.Main) {
            repeat(5) { idx ->
                delay(700L)
                if (pendingDoneRecId != recId) return@launch
                appendLog("done retry #${idx + 1}: recId=$recId")
                ble.writeAsciiCommand("DONE:$recId")
            }
            if (pendingDoneRecId == recId) {
                appendLog("done ack timeout: recId=$recId")
            }
        }
    }

    private fun reportThroughput(chunkSize: Int) {
        val now = System.currentTimeMillis()
        if (pullStartAtMs == 0L) pullStartAtMs = now
        if (pullLastReportAtMs == 0L) {
            pullLastReportAtMs = now
            pullBytesAtLastReport = pendingOffset
        }
        val dt = now - pullLastReportAtMs
        if (dt >= 1000L) {
            val dBytes = pendingOffset - pullBytesAtLastReport
            val bps = if (dt > 0) (dBytes.toDouble() * 1000.0 / dt.toDouble()) else 0.0
            val kbps = bps / 1024.0
            val totalDt = now - pullStartAtMs
            appendLog("stream: off=$pendingOffset chunk=${chunkSize}B rate=${"%.1f".format(kbps)}KiB/s elapsed=${(totalDt / 1000.0).toInt()}s")
            pullLastReportAtMs = now
            pullBytesAtLastReport = pendingOffset
        }
    }

    private fun requestWindow(fromOffset: Int) {
        val m = pendingMeta ?: return
        val remaining = m.totalBytes - fromOffset
        if (remaining <= 0) return
        val want = minOf(pullWindowBytes, remaining)
        pendingWindowEndOffset = fromOffset + want
        lastGetSentAtMs = System.currentTimeMillis()
        appendLog("GET -> recId=${m.recId} off=$fromOffset len=$want winEnd=$pendingWindowEndOffset total=${m.totalBytes}")
        ble.writeAsciiCommand("GET:${m.recId}:$fromOffset:$want")
        schedulePullTimeout()
    }

    private fun schedulePullTimeout() {
        // If streaming stalls mid-window, re-request from current offset for remaining in window.
        pullTimeoutJob?.cancel()
        pullTimeoutJob = viewModelScope.launch(Dispatchers.Main) {
            delay(1200L)
            val m2 = pendingMeta ?: return@launch
            if (!downloading) return@launch
            if (pendingOffset >= m2.totalBytes) return@launch
            val now = System.currentTimeMillis()
            val lastData = lastAudioDataAtMs
            val stalled = if (lastData != 0L) (now - lastData) >= 1100L else (now - lastGetSentAtMs) >= 1100L
            if (!stalled) return@launch
            val winEnd = pendingWindowEndOffset
            if (pendingOffset < winEnd) {
                val retryLen = (winEnd - pendingOffset).coerceAtLeast(1)
                appendLog("pull: stall -> retry window off=$pendingOffset len=$retryLen")
                requestWindow(fromOffset = pendingOffset)
            }
        }
    }

    private fun parseRecEndMeta(payload: ByteArray): RecMeta? {
        // Firmware meta: [recId:u16][totalBytes:u32][crc32:u32][sr:u16]
        if (payload.size != 12) return null
        val recId = u16le(payload, 0)
        val total = u32le(payload, 2)
        val crc = u32le(payload, 6).toLong() and 0xFFFF_FFFFL
        val sr = u16le(payload, 10)
        if (total <= 0) {
            appendLog("rec meta invalid: recId=$recId total=$total crc=0x${crc.toString(16)} sr=$sr")
            return null
        }
        if (total > 10_000_000) {
            appendLog("rec meta too large: recId=$recId total=$total")
            return null
        }
        return RecMeta(recId = recId, totalBytes = total, crc32 = crc, sampleRate = sr)
    }

    private fun u16le(b: ByteArray, off: Int): Int {
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        return b0 or (b1 shl 8)
    }

    private fun u32le(b: ByteArray, off: Int): Int {
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        val b2 = b[off + 2].toInt() and 0xFF
        val b3 = b[off + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun saveWav(pcmBytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = getApplication<Application>().cacheDir
                val f = SonyaWatchWav.writePcm16leMono16kHzWav(dir = dir, pcmS16Le = pcmBytes)
                lastWavFile = f
                appendLog("WAV saved: ${f.absolutePath} (${f.length()} bytes)")

                // Quick signal check: if max/rms are near zero, it's likely silence / mic path issue.
                val stats = pcmLevelStats(pcmBytes)
                appendLog("pcm level: samples=${stats.samples} maxAbs=${stats.maxAbs} rms=${"%.4f".format(stats.rms)}")
                if (stats.maxAbs < 80) {
                    appendLog("pcm looks like silence (maxAbs<80)")
                } else if (stats.maxAbs < 2000) {
                    appendLog("pcm very quiet (maxAbs<2000), recognition can be blank")
                }

                // Transcribe (offline) and send as /command to backend (same flow as phone voice).
                val text = runCatching {
                    SonyaWatchVoskTranscriber.transcribePcm16leMono16k(getApplication(), pcmBytes)
                }.getOrElse { t ->
                    appendLog("transcribe failed: ${t.javaClass.simpleName}: ${t.message}")
                    ""
                }.trim()

                if (text.isNotBlank()) {
                    appendLog("watch transcript: '$text'")
                    _ui.value = _ui.value.copy(lastTranscript = text)
                    try {
                        // Use the unified orchestrator in VoiceRecognitionService so watch and phone share
                        // the same "Услышала" + "Всё ОК" flow.
                        val ctx = getApplication<Application>().applicationContext
                        val intent = Intent(ctx, VoiceRecognitionService::class.java).apply {
                            action = VoiceRecognitionService.ACTION_PROCESS_RECOGNIZED_TEXT
                            putExtra(VoiceRecognitionService.EXTRA_RECOGNIZED_TEXT, text)
                            putExtra(VoiceRecognitionService.EXTRA_RECOGNIZED_SOURCE, "watch_vosk")
                        }
                        broadcastStatus("Часы: отправляю команду на сервер")
                        ContextCompat.startForegroundService(ctx, intent)
                        _ui.value = _ui.value.copy(lastBackendCommand = "SENT (via service)")
                    } catch (t: Throwable) {
                        appendLog("backend command failed: ${t.javaClass.simpleName}: ${t.message}")
                        broadcastStatus("Часы: ошибка отправки команды")
                        _ui.value = _ui.value.copy(lastBackendCommand = "ERR: ${t.javaClass.simpleName}")
                    }
                } else {
                    appendLog("watch transcript: <blank>")
                    broadcastStatus("Часы: речь не распознана, команда не отправлена")
                    _ui.value = _ui.value.copy(lastTranscript = "")
                    _ui.value = _ui.value.copy(lastBackendCommand = "ERR: blank transcript")
                }

                viewModelScope.launch(Dispatchers.Main) {
                    _ui.value = _ui.value.copy(lastWavPath = f.absolutePath)
                }
            } catch (t: Throwable) {
                Log.w(SonyaWatchProtocol.TAG, "saveWav failed: ${t.javaClass.simpleName}: ${t.message}", t)
                appendLog("saveWav failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private data class PcmStats(val samples: Int, val maxAbs: Int, val rms: Double)

    private fun pcmLevelStats(pcmBytes: ByteArray): PcmStats {
        val n = pcmBytes.size / 2
        if (n <= 0) return PcmStats(samples = 0, maxAbs = 0, rms = 0.0)
        var maxAbs = 0
        var sumSq = 0.0
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val s = (hi shl 8) or lo
            val v = s.toShort().toInt()
            val a = abs(v)
            if (a > maxAbs) maxAbs = a
            sumSq += (v.toDouble() * v.toDouble())
            i += 2
        }
        val rms = sqrt(sumSq / n.toDouble()) / 32768.0
        return PcmStats(samples = n, maxAbs = maxAbs, rms = rms)
    }

    @SuppressLint("HardwareIds")
    private suspend fun sendCommandToBackend(text: String) {
        // Deprecated: watch flow is now handled by VoiceRecognitionService orchestrator.
        // Kept only to avoid breaking older call sites if any remain.
        throw IllegalStateException("sendCommandToBackend() is no longer used; use VoiceRecognitionService.ACTION_PROCESS_RECOGNIZED_TEXT")
    }

    private fun setEvent(s: String) {
        _ui.value = _ui.value.copy(lastEvent = s)
        appendLog("event: $s")
    }

    private fun broadcastStatus(status: String) {
        val ctx = getApplication<Application>().applicationContext
        ctx.sendBroadcast(
            Intent(VoiceRecognitionService.STATUS_UPDATE_ACTION).putExtra(
                VoiceRecognitionService.STATUS_UPDATE_TEXT,
                status
            )
        )
    }

    private fun maybeBroadcastTransferProgress(total: Int, offset: Int) {
        if (total <= 0) return
        val pct = ((offset * 100L) / total.toLong()).toInt().coerceIn(0, 100)
        val shouldSend = when {
            pct >= 100 && lastBroadcastProgressPct < 100 -> true
            lastBroadcastProgressPct < 0 -> true
            pct - lastBroadcastProgressPct >= 10 -> true
            else -> false
        }
        if (!shouldSend) return
        lastBroadcastProgressPct = pct
        broadcastStatus("Часы: передача аудио $pct%")
    }

    private fun appendLog(line: String, writeToAppLog: Boolean = true) {
        if (writeToAppLog) Log.i(SonyaWatchProtocol.TAG, line)
        val cur = _ui.value
        val next = (cur.logTail + line).takeLast(60)
        _ui.value = cur.copy(logTail = next)
    }

    private fun vibrateReadyPulse() {
        try {
            val ctx = getApplication<Application>().applicationContext
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(120)
            }
            appendLog("haptic: ready pulse")
        } catch (t: Throwable) {
            appendLog("haptic failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun speakWatchReadyPhrase() {
        try {
            val ctx = getApplication<Application>().applicationContext
            val phrase = VoiceResponsesConfig.pickWakeResponse(ctx)
            val intent = Intent(ctx, VoiceRecognitionService::class.java).apply {
                action = VoiceRecognitionService.ACTION_SPEAK
                putExtra(VoiceRecognitionService.EXTRA_SPEAK_TEXT, phrase)
            }
            ContextCompat.startForegroundService(ctx, intent)
            appendLog("tts: ready phrase '$phrase'")
        } catch (t: Throwable) {
            appendLog("tts ready failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun estimateDrainRateMvPerMin(points: List<BattPoint>): Double? {
        if (points.size < 4) return null
        val t0 = points.first().atMs
        val xs = points.map { (it.atMs - t0).toDouble() / 60_000.0 } // minutes
        val ys = points.map { it.mv.toDouble() }

        val n = xs.size.toDouble()
        val sumX = xs.sum()
        val sumY = ys.sum()
        val sumXY = xs.zip(ys).sumOf { (x, y) -> x * y }
        val sumX2 = xs.sumOf { it * it }
        val denom = n * sumX2 - sumX * sumX
        if (denom <= 1e-9) return null

        // slope of voltage vs time; discharge rate is -slope.
        val slopeMvPerMin = (n * sumXY - sumX * sumY) / denom
        val drain = (-slopeMvPerMin).coerceAtLeast(0.0)

        // Clamp unrealistic UI spikes.
        if (drain > 300.0) return null
        return drain
    }
}

