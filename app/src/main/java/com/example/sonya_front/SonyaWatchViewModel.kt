package com.example.sonya_front

import android.app.Application
import android.annotation.SuppressLint
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
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
    val logTail: List<String> = emptyList(),
)

class SonyaWatchViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = mutableStateOf(
        SonyaWatchUiState(
            backendUrl = "http://192.168.0.21:18000/voice",
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

    @Volatile private var appVisible: Boolean = false

    private val ble = SonyaWatchBleClient(
        appCtx = app.applicationContext,
        onLog = { appendLog(it) },
        onConnectedChanged = { isConn ->
            val cur = _ui.value
            _ui.value = cur.copy(connected = isConn)
            appendLog(if (isConn) "BLE connected" else "BLE disconnected")
        },
        onScanningChanged = { isScan ->
            val cur = _ui.value
            _ui.value = cur.copy(scanning = isScan)
        },
        onNotifyBytes = { bytes -> onNotify(bytes) }
    )

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        applyAutoConnect()
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        _ui.value = _ui.value.copy(autoConnect = enabled)
        applyAutoConnect()
        if (enabled && appVisible) {
            ble.kickAutoConnectNow()
        }
    }

    private fun applyAutoConnect() {
        val enabled = appVisible && _ui.value.autoConnect
        ble.setAutoConnectEnabled(enabled)
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
        _ui.value = _ui.value.copy(downloadTotalBytes = 0, downloadOffsetBytes = 0, bytesTotal = 0)
    }

    fun sendPing() = ble.writeAsciiCommand("PING")
    fun sendSetRec2() = ble.writeAsciiCommand("SETREC:2")
    fun sendRec() = ble.writeAsciiCommand("REC")

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
                setEvent("$typeName seq=${f.seq}")
            }

            SonyaWatchProtocol.EVT_REC_START -> {
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
                _ui.value = _ui.value.copy(bytesTotal = 0, downloadTotalBytes = 0, downloadOffsetBytes = 0)
                setEvent("$typeName seq=${f.seq}")
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
                val isInfo = m == "PONG" || m.startsWith("REC_SEC=")
                if (isInfo) {
                    appendLog("watch: '$m'")
                    setEvent("WATCH: $m")
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

    private fun finalizeDone(m: RecMeta) {
        ble.writeAsciiCommand("DONE:${m.recId}")
        recording = false
        downloading = false
        expectedSeq = null
        liveRecId = -1
        pullTimeoutJob?.cancel()
        pullTimeoutJob = null
        val pcmBytes = pcm.toByteArray()
        appendLog("rec done: pcmBytes=${pcmBytes.size} expected=${m.totalBytes}")
        saveWav(pcmBytes)
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
        if (total <= 0 || total > 10_000_000) return null
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
                        sendCommandToBackend(text)
                    } catch (t: Throwable) {
                        appendLog("backend command failed: ${t.javaClass.simpleName}: ${t.message}")
                        _ui.value = _ui.value.copy(lastBackendCommand = "ERR: ${t.javaClass.simpleName}")
                    }
                } else {
                    appendLog("watch transcript: <blank>")
                    _ui.value = _ui.value.copy(lastTranscript = "")
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
        val deviceId = "android-" + Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
        val deviceTime = sdf.format(Date())

        val req = CommandRequest(
            deviceId = deviceId,
            text = text,
            lat = null,
            lon = null,
            deviceTime = deviceTime,
        )

        appendLog("backend /command: send text='${text.take(120)}'")
        val resp = ApiClient.instance.sendCommand(req)
        val code = resp.code()
        _ui.value = _ui.value.copy(lastBackendCommand = "HTTP $code")
        appendLog("backend /command: http=$code")
    }

    private fun setEvent(s: String) {
        _ui.value = _ui.value.copy(lastEvent = s)
        appendLog("event: $s")
    }

    private fun appendLog(line: String) {
        Log.i(SonyaWatchProtocol.TAG, line)
        val cur = _ui.value
        val next = (cur.logTail + line).takeLast(60)
        _ui.value = cur.copy(logTail = next)
    }
}

