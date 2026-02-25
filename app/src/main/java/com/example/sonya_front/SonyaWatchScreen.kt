package com.example.sonya_front

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun SonyaWatchScreen(
    viewModel: SonyaWatchViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui
    val scroll = rememberScrollState()
    val ctx = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scroll)
    ) {
        Text("Часы (BLE → WAV → Backend)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "device: ${SonyaWatchProtocol.DEVICE_NAME}",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 6.dp)
        )

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.scanAndConnect() },
                enabled = !ui.connected && !ui.scanning,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (ui.scanning) "Скан…" else "Scan+Connect")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.disconnect() },
                enabled = ui.connected,
                modifier = Modifier.weight(1f)
            ) {
                Text("Disconnect")
            }
        }

        Text(
            text = "connected=${ui.connected}  bytes=${ui.bytesTotal}",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 10.dp)
        )
        if (ui.downloadTotalBytes > 0) {
            val progress =
                (ui.downloadOffsetBytes.toFloat() / ui.downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
            Text(
                text = "download: ${ui.downloadOffsetBytes}/${ui.downloadTotalBytes} bytes (${(progress * 100).toInt()}%)",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 6.dp)
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
        Text(
            text = "last: ${ui.lastEvent}",
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (ui.lastTranscript.isNotBlank()) {
            Text(
                text = "text: ${ui.lastTranscript}",
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (ui.lastBackendCommand.isNotBlank()) {
            Text(
                text = "backend: ${ui.lastBackendCommand}",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { viewModel.sendPing() }, enabled = ui.connected, modifier = Modifier.weight(1f)) {
                Text("PING")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { viewModel.sendSetRec2() }, enabled = ui.connected, modifier = Modifier.weight(1f)) {
                Text("SETREC:2")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { viewModel.sendRec() }, enabled = ui.connected, modifier = Modifier.weight(1f)) {
                Text("REC")
            }
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        OutlinedTextField(
            value = ui.backendUrl,
            onValueChange = { viewModel.setBackendUrl(it) },
            label = { Text("Backend URL (POST audio/wav)") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Button(
                onClick = { viewModel.uploadLastWav() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Upload last WAV")
            }
        }

        if (ui.lastWavPath.isNotBlank()) {
            Text(
                text = "WAV: ${ui.lastWavPath}",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 10.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        try {
                            val f = File(ui.lastWavPath)
                            if (!f.exists()) {
                                Toast.makeText(ctx, "Файл не найден", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "audio/wav")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(intent, "Открыть WAV"))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(ctx, "Нет приложения для открытия WAV", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Toast.makeText(ctx, "Ошибка: ${t.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Play WAV")
                }

                Button(
                    onClick = {
                        try {
                            val f = File(ui.lastWavPath)
                            if (!f.exists()) {
                                Toast.makeText(ctx, "Файл не найден", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/wav"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(intent, "Поделиться WAV"))
                        } catch (t: Throwable) {
                            Toast.makeText(ctx, "Ошибка: ${t.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Share WAV")
                }
            }
        }
        if (ui.lastUpload.isNotBlank()) {
            Text(
                text = ui.lastUpload,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Divider(modifier = Modifier.padding(vertical = 12.dp))

        Text("Log tail (tag: ${SonyaWatchProtocol.TAG})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        ui.logTail.takeLast(60).forEach { line ->
            Text(line, fontSize = 11.sp, color = Color(0xFFB0B0B0))
        }
        Spacer(Modifier.height(24.dp))
    }
}

