package com.sonya.companion

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var logTv: TextView
    private lateinit var backendEt: EditText

    private lateinit var ble: BleClient

    private val pcm = ByteArrayOutputStream()
    private var lastWavFile: File? = null

    private val tag = "SonyaCompanion"

    private val reqPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        log("Permissions: $res")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTv = findViewById(R.id.logTv)
        backendEt = findViewById(R.id.backendEt)

        ble = BleClient(
            ctx = this,
            onLog = { log(it) },
            onFrame = { onFrame(it) },
        )

        reqPerms.launch(BleClient.REQUIRED_PERMS)

        findViewById<Button>(R.id.scanBtn).setOnClickListener {
            ble.scanByName("SONYA-WATCH")
        }
        findViewById<Button>(R.id.pingBtn).setOnClickListener { ble.writeRxAscii("PING") }
        findViewById<Button>(R.id.recBtn).setOnClickListener { ble.writeRxAscii("REC") }
        findViewById<Button>(R.id.set2Btn).setOnClickListener { ble.writeRxAscii("SETREC:2") }
        findViewById<Button>(R.id.uploadBtn).setOnClickListener { uploadLast() }
    }

    private fun onFrame(f: Frame) {
        runOnUiThread {
            when (f.type) {
                Protocol.EVT_WAKE -> log("<< EVT_WAKE seq=${f.seq}")
                Protocol.EVT_REC_START -> {
                    log("<< EVT_REC_START seq=${f.seq}")
                    pcm.reset()
                    lastWavFile = null
                }
                Protocol.AUDIO_CHUNK -> {
                    pcm.write(f.payload)
                    log("<< AUDIO_CHUNK seq=${f.seq} bytes=${f.len} total=${pcm.size()}")
                }
                Protocol.EVT_REC_END -> {
                    log("<< EVT_REC_END seq=${f.seq} total_pcm=${pcm.size()}")
                    val wav = File(cacheDir, "sonya_${System.currentTimeMillis()}.wav")
                    WavWriter.writePcmS16leMono16k(wav, pcm.toByteArray())
                    lastWavFile = wav
                    log("WAV saved: ${wav.absolutePath} (${wav.length()} bytes)")
                }
                Protocol.EVT_ERROR -> {
                    val txt = f.payload.toString(Charsets.UTF_8)
                    log("<< EVT_ERROR seq=${f.seq} \"$txt\"")
                }
                else -> log("<< type=0x${f.type.toString(16)} seq=${f.seq} len=${f.len}")
            }
        }
    }

    private fun uploadLast() {
        val wav = lastWavFile
        if (wav == null) {
            log("Upload: no WAV yet (record first)")
            return
        }
        val url = backendEt.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            log("Upload: set Backend URL first")
            return
        }
        val bytes = wav.readBytes()
        log("Upload: POST $url bytes=${bytes.size}")
        thread {
            try {
                val (code, body) = BackendUploader.postWav(url, bytes)
                runOnUiThread { log("Upload result: HTTP $code body=${body.take(300)}") }
            } catch (e: Exception) {
                runOnUiThread { log("Upload error: ${e.message}") }
            }
        }
    }

    private fun log(s: String) {
        Log.i(tag, s)
        logTv.append(s + "\n")
    }
}

