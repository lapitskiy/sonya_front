package com.example.sonya_front

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.app.AlarmManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.sonya_front.ui.theme.Sonya_frontTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val watchViewModel: SonyaWatchViewModel by viewModels()

    private val requestNotificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.showSnackbar("Разреши уведомления — иначе таймеры/будильники могут быть не видны в шторке.")
            }
        }

    // Запрос разрешений (аудио + геолокация)
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val audioGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val fineGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarseGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val bleScanGranted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                else true
            val bleConnectGranted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                else true
            val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true

            if (!audioGranted) {
                Log.d("MainActivity", "Audio permission denied by user.")
                viewModel.showSnackbar("Разрешение на запись аудио не предоставлено.")
                return@registerForActivityResult
            }

            Log.d("MainActivity", "Audio permission granted by user.")
            if (fineGranted || coarseGranted) {
                ensureLocationEnabled()
            } else {
                viewModel.showSnackbar("Геолокация не разрешена — команды будут отправляться без координат.")
            }

            if (!bleScanGranted || !bleConnectGranted) {
                viewModel.showSnackbar("Нужны разрешения Bluetooth (SCAN/CONNECT) для часов.")
            }

            ensureExactAlarmsAllowed()
            requestIgnoreBatteryOptimizationsIfNeeded()
            startVoiceService()

            if (!notificationsGranted) requestNotificationsPermissionIfNeeded()
        }

    // Приемник сообщений от сервиса
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VoiceRecognitionService.RECOGNITION_RESULT_ACTION -> {
                    val text = intent.getStringExtra(VoiceRecognitionService.RECOGNITION_RESULT_TEXT)
                    viewModel.onNewRecognitionResult("Распознано: '$text'")
                }
                VoiceRecognitionService.STATUS_UPDATE_ACTION -> {
                    val status = intent.getStringExtra(VoiceRecognitionService.STATUS_UPDATE_TEXT)
                    viewModel.updateStatus(status ?: "Статус обновлен")
                }
                VoiceRecognitionService.HINT_UPDATE_ACTION -> {
                    val hint = intent.getStringExtra(VoiceRecognitionService.HINT_UPDATE_TEXT)
                    if (!hint.isNullOrBlank()) viewModel.showSnackbar(hint)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Регистрируем приемник
        val intentFilter = IntentFilter().apply {
            addAction(VoiceRecognitionService.RECOGNITION_RESULT_ACTION)
            addAction(VoiceRecognitionService.STATUS_UPDATE_ACTION)
            addAction(VoiceRecognitionService.HINT_UPDATE_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }

        // Проверяем и запрашиваем разрешение
        checkAndRequestPermission()

        setContent {
            Sonya_frontTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                var tab by remember { mutableStateOf(AppTab.Status) }
                val ctx = LocalContext.current
                val deviceIdStr = remember {
                    "android-" + Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                }

                LaunchedEffect(viewModel.snackbarMessage.value) {
                    val msg = viewModel.snackbarMessage.value
                    if (!msg.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearSnackbar()
                    }
                }

                // Simple UI refresh loop for countdown display.
                LaunchedEffect(Unit) {
                    var lastCountdownNotifUpdateAt = 0L
                    while (true) {
                        viewModel.refreshActiveActions(this@MainActivity)
                        // Keep ongoing "timer countdown" notifications in sync while UI is visible.
                        // Some OEM SystemUI builds are fragile when notifications are updated too frequently,
                        // so we throttle notification updates (UI can still update every second).
                        val now = System.currentTimeMillis()
                        if (now - lastCountdownNotifUpdateAt >= 15000L) {
                            PendingActionsScheduler.refreshCountdownNotifications(this@MainActivity)
                            lastCountdownNotifUpdateAt = now
                        }
                        delay(1000L)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                    ,
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == AppTab.Status,
                                onClick = { tab = AppTab.Status },
                                icon = { androidx.compose.material3.Icon(Icons.Filled.Home, contentDescription = "Статус") },
                                label = { Text("Статус") }
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.Day,
                                onClick = { tab = AppTab.Day },
                                icon = { androidx.compose.material3.Icon(Icons.Filled.Today, contentDescription = "День") },
                                label = { Text("День") }
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.Tasks,
                                onClick = {
                                    tab = AppTab.Tasks
                                },
                                icon = { androidx.compose.material3.Icon(Icons.Filled.CheckCircle, contentDescription = "Задания") },
                                label = { Text("Задания") }
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.Profile,
                                onClick = { tab = AppTab.Profile },
                                icon = { androidx.compose.material3.Icon(Icons.Filled.Person, contentDescription = "Профиль") },
                                label = { Text("Профиль") }
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.Watch,
                                onClick = { tab = AppTab.Watch },
                                icon = { androidx.compose.material3.Icon(Icons.Filled.Archive, contentDescription = "Часы") },
                                label = { Text("Часы") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (tab) {
                        AppTab.Status -> {
                            var wakeEnabled by remember { mutableStateOf(UserSettingsStore.getWakeListeningEnabled(ctx)) }
                            StatusScreen(
                                statusText = viewModel.recognizedText.value,
                                activeActions = viewModel.activeActions.value,
                                isWakeListeningEnabled = wakeEnabled,
                                onToggleWakeListening = { enable ->
                                    wakeEnabled = enable
                                    val action = if (enable)
                                        VoiceRecognitionService.ACTION_RESUME_WAKE
                                    else
                                        VoiceRecognitionService.ACTION_PAUSE_WAKE
                                    val i = Intent(ctx, VoiceRecognitionService::class.java).apply {
                                        this.action = action
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
                                },
                                onCancel = { actionId ->
                                    PendingActionsScheduler.cancelWithAck(ctx, deviceIdStr, actionId)
                                },
                                onDone = { actionId, type, label ->
                                    val i = Intent(ctx, PendingActionForegroundService::class.java).apply {
                                        action = "com.example.sonya_front.PENDING_ACTION_DONE"
                                        putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceIdStr)
                                        putExtra(PendingActionReceiver.EXTRA_ACTION_ID, actionId)
                                        putExtra(PendingActionReceiver.EXTRA_TYPE, type)
                                        putExtra(PendingActionReceiver.EXTRA_TTS, label)
                                    }
                                    // Use startService (NOT startForegroundService) for quick
                                    // done/snooze actions — they call stopSelf() immediately
                                    // without startForeground(), which would crash on Android 8+.
                                    ctx.startService(i)
                                },
                                onSnooze = { actionId, type, label ->
                                    val i = Intent(ctx, PendingActionForegroundService::class.java).apply {
                                        action = PendingActionForegroundService.ACTION_SNOOZE_15
                                        putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceIdStr)
                                        putExtra(PendingActionReceiver.EXTRA_ACTION_ID, actionId)
                                        putExtra(PendingActionReceiver.EXTRA_TYPE, type)
                                        putExtra(PendingActionReceiver.EXTRA_TTS, label)
                                    }
                                    ctx.startService(i)
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        AppTab.Day -> {
                            DayScreen(
                                deviceId = deviceIdStr,
                                viewModel = viewModel,
                                onMessage = { msg -> viewModel.showSnackbar(msg) },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        AppTab.Tasks -> {
                            TasksScreen(
                                deviceId = deviceIdStr,
                                viewModel = viewModel,
                                onMessage = { msg -> viewModel.showSnackbar(msg) },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        AppTab.Profile -> {
                            ProfileScreen(
                                deviceId = deviceIdStr,
                                viewModel = viewModel,
                                onCancelRequest = { actionId ->
                                    PendingActionsScheduler.cancelWithAck(ctx, deviceIdStr, actionId)
                                    viewModel.loadRequestsAsync(deviceIdStr)
                                },
                                onOpenExactAlarms = { ensureExactAlarmsAllowed() },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        AppTab.Watch -> {
                            SonyaWatchScreen(
                                viewModel = watchViewModel,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermission() {
        Log.d("MainActivity", "Checking permissions.")
        val audioGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val fineGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bleScanGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            else true
        val bleConnectGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            else true
        val notifGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true

        // Proceed with app startup (voice service, alarms) even if BLE permissions are missing;
        // BLE is needed specifically for the Watch tab.
        if (audioGranted) {
            Log.d("MainActivity", "Audio permission is already granted.")
            if (fineGranted || coarseGranted) {
                ensureLocationEnabled()
            } else {
                // Можем работать и без локации, но попросим её сразу "по уму".
                viewModel.showSnackbar("Нужно разрешение на геолокацию для привязки команд к месту.")
            }
            ensureExactAlarmsAllowed()
            requestIgnoreBatteryOptimizationsIfNeeded()
            startVoiceService()
            if (!notifGranted) requestNotificationsPermissionIfNeeded()
        }

        val permsToRequest = mutableListOf<String>()
        if (!audioGranted) permsToRequest.add(Manifest.permission.RECORD_AUDIO)
        if (!audioGranted) {
            if (!fineGranted) permsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!coarseGranted) permsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!bleScanGranted) permsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!bleConnectGranted) permsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (permsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Requesting permissions: $permsToRequest")
            requestPermissionsLauncher.launch(permsToRequest.toTypedArray())
        }
    }

    private fun requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        requestNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }


    private fun startVoiceService() {
        Log.d("MainActivity", "Starting VoiceRecognitionService.")
        val serviceIntent = Intent(this, VoiceRecognitionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w("MainActivity", "Failed to request ignore battery optimizations: ${t.message}", t)
        }
    }

    private fun ensureLocationEnabled() {
        try {
            val lm = getSystemService(LocationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (!lm.isLocationEnabled) {
                    viewModel.showSnackbar("Включите геолокацию (GPS) — иначе координаты будут неверными.")
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
        } catch (t: Throwable) {
            Log.w("MainActivity", "Failed to ensure location enabled: ${t.message}", t)
        }
    }

    private fun ensureExactAlarmsAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val am = getSystemService(AlarmManager::class.java) ?: return
            if (am.canScheduleExactAlarms()) return

            viewModel.showSnackbar("Разреши точные будильники, иначе таймеры/будильники могут срабатывать с задержкой.")
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w("MainActivity", "Failed to request exact alarm permission: ${t.message}", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Не забываем отписаться от приемника
        unregisterReceiver(broadcastReceiver)
        // Можно остановить сервис, если это нужно при закрытии приложения
        // stopService(Intent(this, VoiceRecognitionService::class.java))
    }
}

private enum class AppTab { Status, Day, Tasks, Profile, Watch }

private enum class SettingsPage { Root, Interaction, System }

private enum class RequestFilter(val label: String) {
    PENDING("Ожидание"),
    DONE("Выполнено"),
    CANCELLED("Отменено"),
    ALL("Все")
}

@Composable
private fun ProfileScreen(
    deviceId: String,
    viewModel: MainViewModel,
    onCancelRequest: (Int) -> Unit,
    onOpenExactAlarms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showRequests by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            deviceId = deviceId,
            onBack = { showSettings = false },
            onOpenExactAlarms = onOpenExactAlarms,
            modifier = modifier
        )
        return
    }

    if (showRequests) {
        Column(modifier = modifier.fillMaxSize()) {
            IconButton(onClick = { showRequests = false }, modifier = Modifier.padding(start = 4.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            RequestsScreen(
                deviceId = deviceId,
                viewModel = viewModel,
                onCancel = onCancelRequest,
                modifier = Modifier.weight(1f)
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Профиль", fontSize = 22.sp)
        Text("device_id: $deviceId", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Запросы", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(onClick = { showRequests = true }) {
                Text("Открыть", fontSize = 12.sp)
            }
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Настройки", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(onClick = { showSettings = true }) {
                Text("Открыть", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    deviceId: String,
    onBack: () -> Unit,
    onOpenExactAlarms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appCtx = LocalContext.current.applicationContext
    var page by remember { mutableStateOf(SettingsPage.Root) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page == SettingsPage.Root) onBack() else page = SettingsPage.Root }) {
                androidx.compose.material3.Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(
                text = when (page) {
                    SettingsPage.Root -> "Настройки"
                    SettingsPage.Interaction -> "Взаимодействие"
                    SettingsPage.System -> "Система"
                },
                fontSize = 22.sp
            )
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        when (page) {
            SettingsPage.Root -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Взаимодействие", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Button(onClick = { page = SettingsPage.Interaction }) { Text("Открыть", fontSize = 12.sp) }
                }
                Divider(modifier = Modifier.padding(vertical = 12.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Система", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Button(onClick = { page = SettingsPage.System }) { Text("Открыть", fontSize = 12.sp) }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                Text("Инфо", fontSize = 14.sp, color = Color.Gray)
                Text("device_id: $deviceId", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "wake-фразы редактируются в assets/sonya_voice_responses.json",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            SettingsPage.Interaction -> {
                var vibrateOnConfirm by remember { mutableStateOf(UserSettingsStore.getVibrateOnConfirm(appCtx)) }
                Text("Подтверждения", fontSize = 14.sp, color = Color.Gray)
                Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Вибрация при подтверждении задания", modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Checkbox(
                        checked = vibrateOnConfirm,
                        onCheckedChange = { checked ->
                            vibrateOnConfirm = checked
                            UserSettingsStore.setVibrateOnConfirm(appCtx, checked)
                        }
                    )
                }
            }

            SettingsPage.System -> {
                Text("Сигналы", fontSize = 14.sp, color = Color.Gray)
                Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Точные сигналы", modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Button(onClick = onOpenExactAlarms) { Text("Открыть", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun TasksScreen(
    deviceId: String,
    viewModel: MainViewModel,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading = viewModel.tasksLoading.value
    val err = viewModel.tasksError.value

    var tasksTab by remember { mutableStateOf(0) } // 0 = Задачи, 1 = Гео, 2 = Архив
    val isArchive = tasksTab == 2

    var text by remember { mutableStateOf("") }
    var urgent by remember { mutableStateOf(false) }
    var important by remember { mutableStateOf(false) }

    var editingId by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }

    val items = when (tasksTab) {
        1 -> viewModel.tasksActive.value.filter { it.type?.lowercase() == "geo" }
        2 -> viewModel.tasksDone.value
        else -> viewModel.tasksActive.value.filter { it.type?.lowercase() != "geo" }
    }

    LaunchedEffect(deviceId, tasksTab) {
        if (deviceId.isBlank()) return@LaunchedEffect
        val status = if (tasksTab == 2) "done" else "active"
        viewModel.loadTasks(deviceId, status)
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Задания", fontSize = 22.sp)
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val status = if (tasksTab == 2) "done" else "active"
                viewModel.loadTasksAsync(deviceId, status)
            }) {
                androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        }

        TabRow(
            selectedTabIndex = tasksTab,
            modifier = Modifier.padding(top = 8.dp),
            containerColor = Color.Transparent
        ) {
            Tab(
                selected = tasksTab == 0,
                onClick = { tasksTab = 0; editingId = null; editingText = "" },
                text = { Text("Задачи", fontSize = 13.sp) }
            )
            Tab(
                selected = tasksTab == 1,
                onClick = { tasksTab = 1; editingId = null; editingText = "" },
                text = { Text("Гео", fontSize = 13.sp) }
            )
            Tab(
                selected = tasksTab == 2,
                onClick = { tasksTab = 2; editingId = null; editingText = "" },
                text = { Text("Архив", fontSize = 13.sp) }
            )
        }

        if (!isArchive) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Текст задачи") },
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth()
            )

            Text("Приоритет:", modifier = Modifier.padding(top = 10.dp))
            Row(modifier = Modifier.padding(top = 6.dp)) {
                FilterChip(
                    selected = urgent,
                    onClick = { urgent = !urgent },
                    label = { Text("Срочно") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = important,
                    onClick = { important = !important },
                    label = { Text("Важно") },
                )
            }

            Button(
                onClick = {
                    val t = text.trim()
                    if (t.isBlank()) {
                        onMessage("Введите текст задачи.")
                        return@Button
                    }
                    val taskType = if (tasksTab == 1) "geo" else null
                    viewModel.createTaskAndRefreshActiveAsync(deviceId, t, urgent = urgent, important = important, type = taskType)
                    text = ""
                    urgent = false
                    important = false
                },
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth()
            ) {
                Text("Добавить")
            }
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        if (loading) {
            Text("Загрузка...", color = Color.Gray)
        }
        if (!err.isNullOrBlank()) {
            Text("Ошибка: $err", color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 6.dp))
        }
        if (!loading && err.isNullOrBlank() && items.isEmpty()) {
            Text("Пока пусто.", color = Color.Gray)
            return
        }

        androidx.compose.foundation.lazy.LazyColumn {
            items(items.size) { idx ->
                val t = items[idx]
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (editingId == t.id && !isArchive) {
                                OutlinedTextField(
                                    value = editingText,
                                    onValueChange = { editingText = it },
                                    label = { Text("Текст") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(modifier = Modifier.padding(top = 6.dp)) {
                                    Button(
                                        onClick = {
                                            val newText = editingText.trim()
                                            if (newText.isBlank()) {
                                                onMessage("Текст не может быть пустым.")
                                                return@Button
                                            }
                                            viewModel.updateTaskAndRefreshActiveAsync(
                                                deviceId = deviceId,
                                                taskId = t.id,
                                                text = newText,
                                                urgent = null,
                                                important = null,
                                                status = null
                                            )
                                            editingId = null
                                            editingText = ""
                                        }
                                    ) { Text("Сохранить", fontSize = 12.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            editingId = null
                                            editingText = ""
                                        }
                                    ) { Text("Отмена", fontSize = 12.sp) }
                                }
                            } else {
                                Text(
                                    text = t.text ?: "<без текста>",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            val flags = buildString {
                                val u = t.urgent == true
                                val imp = t.important == true
                                if (u) append("Срочно  ")
                                if (imp) append("Важно  ")
                                if (t.status?.isNotBlank() == true) append("status: ${t.status}")
                            }.trim()
                            if (flags.isNotBlank()) {
                                Text(flags, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                            }

                            if (!isArchive && editingId != t.id) {
                                Row(modifier = Modifier.padding(top = 6.dp)) {
                                    FilterChip(
                                        selected = t.urgent == true,
                                        onClick = {
                                            viewModel.updateTaskAndRefreshActiveAsync(
                                                deviceId = deviceId,
                                                taskId = t.id,
                                                text = null,
                                                urgent = !(t.urgent == true),
                                                important = null,
                                                status = null
                                            )
                                        },
                                        label = { Text("Срочно") },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    FilterChip(
                                        selected = t.important == true,
                                        onClick = {
                                            viewModel.updateTaskAndRefreshActiveAsync(
                                                deviceId = deviceId,
                                                taskId = t.id,
                                                text = null,
                                                urgent = null,
                                                important = !(t.important == true),
                                                status = null
                                            )
                                        },
                                        label = { Text("Важно") },
                                    )
                                }
                            }
                        }
                        if (!isArchive) {
                            Column(horizontalAlignment = Alignment.End) {
                                IconButton(
                                    onClick = {
                                        if (editingId == t.id) {
                                            editingId = null
                                            editingText = ""
                                        } else {
                                            editingId = t.id
                                            editingText = (t.text ?: "").trim()
                                        }
                                    }
                                ) {
                                    androidx.compose.material3.Icon(Icons.Filled.Edit, contentDescription = "Править")
                                }
                                Button(
                                    onClick = {
                                        viewModel.archiveTaskAndRefreshAsync(deviceId, t.id)
                                    }
                                ) {
                                    Text("В архив", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Divider(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun DayScreen(
    deviceId: String,
    viewModel: MainViewModel,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading = viewModel.tasksLoading.value
    val err = viewModel.tasksError.value

    var dayTab by remember { mutableStateOf(1) } // 0 yesterday, 1 today, 2 tomorrow, 3 future
    var editingId by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }

    // Load active tasks; we filter out only "today" ones locally.
    LaunchedEffect(deviceId) {
        if (deviceId.isBlank()) return@LaunchedEffect
        viewModel.loadTasks(deviceId, "active")
    }

    val baseDay = LocalDate.now()
    val dayItems = remember(viewModel.tasksActive.value, dayTab, baseDay) {
        val base = baseDay

        val matchesByType: (String?, Int) -> Boolean = { raw, tab ->
            val ty = raw?.trim()?.lowercase()
            when (tab) {
                0 -> ty == "yesterday" || ty == "вчера"
                2 -> ty == "tomorrow" || ty == "завтра"
                3 -> ty == "future" || ty == "будущее" || ty == "later" || ty == "послезавтра" || ty == "aftertomorrow"
                else -> ty == "today" || ty == "сегодня" || ty == "day"
            }
        }

        fun inferredDueByType(type: String?): LocalDate? {
            val ty = type?.trim()?.lowercase().orEmpty()
            return when (ty) {
                "послезавтра", "aftertomorrow" -> base.plusDays(2)
                else -> null
            }
        }

        when (dayTab) {
            3 -> {
                // "Будущее": всё, что строго ПОСЛЕ завтра (т.е. >= base+2).
                val afterTomorrow = base.plusDays(1)
                viewModel.tasksActive.value.filter { t ->
                    val due = parseDueLocalDate(t.dueDate) ?: inferredDueByType(t.type)
                    if (due != null) {
                        due.isAfter(afterTomorrow)
                    } else {
                        matchesByType(t.type, dayTab)
                    }
                }
            }
            else -> {
                val target = when (dayTab) {
                    0 -> base.minusDays(1)
                    2 -> base.plusDays(1)
                    else -> base
                }
                viewModel.tasksActive.value.filter { t ->
                    val due = parseDueLocalDate(t.dueDate)
                    if (due != null) {
                        due == target
                    } else {
                        matchesByType(t.type, dayTab)
                    }
                }
            }
        }
    }

    val dayLabel = when (dayTab) {
        0 -> "Вчера"
        2 -> "Завтра"
        3 -> "Будущее"
        else -> "Сегодня"
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("День", fontSize = 22.sp)
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.loadTasksAsync(deviceId, "active") }) {
                androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        }

        TabRow(
            selectedTabIndex = dayTab,
            modifier = Modifier.padding(top = 8.dp),
            containerColor = Color.Transparent
        ) {
            Tab(
                selected = dayTab == 0,
                onClick = { dayTab = 0 },
                text = { Text("Вчера", fontSize = 13.sp) }
            )
            Tab(
                selected = dayTab == 1,
                onClick = { dayTab = 1 },
                text = { Text("Сегодня", fontSize = 13.sp) }
            )
            Tab(
                selected = dayTab == 2,
                onClick = { dayTab = 2 },
                text = { Text("Завтра", fontSize = 13.sp) }
            )
            Tab(
                selected = dayTab == 3,
                onClick = { dayTab = 3 },
                text = { Text("Будущее", fontSize = 13.sp) }
            )
        }

        if (loading) {
            Text("Загрузка...", color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
        }
        if (!err.isNullOrBlank()) {
            Text("Ошибка: $err", color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 12.dp))
        }
        if (!loading && err.isNullOrBlank() && dayItems.isEmpty()) {
            Text("На $dayLabel задач нет.", color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
            return
        }

        fun inferredDueByTypeForUi(type: String?): LocalDate? {
            val ty = type?.trim()?.lowercase().orEmpty()
            return when (ty) {
                "послезавтра", "aftertomorrow" -> baseDay.plusDays(2)
                else -> null
            }
        }

        val ruFmt = remember {
            DateTimeFormatter.ofPattern("d MMM, EEE", Locale("ru"))
        }
        val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }

        @Composable
        fun TaskRow(t: TaskItem, metaLine: String? = null) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (editingId == t.id) {
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it },
                                label = { Text("Текст") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(modifier = Modifier.padding(top = 6.dp)) {
                                Button(
                                    onClick = {
                                        val newText = editingText.trim()
                                        if (newText.isBlank()) {
                                            onMessage("Текст не может быть пустым.")
                                            return@Button
                                        }
                                        viewModel.updateTaskAndRefreshActiveAsync(
                                            deviceId = deviceId,
                                            taskId = t.id,
                                            text = newText,
                                            urgent = null,
                                            important = null,
                                            status = null
                                        )
                                        editingId = null
                                        editingText = ""
                                    }
                                ) { Text("Сохранить", fontSize = 12.sp) }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        editingId = null
                                        editingText = ""
                                    }
                                ) { Text("Отмена", fontSize = 12.sp) }
                            }
                        } else {
                            val meta = metaLine?.trim().orEmpty()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = t.text ?: "<без текста>",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (meta.isNotBlank()) {
                                    Text(
                                        meta,
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(start = 10.dp)
                                    )
                                }
                            }
                        }

                        // Show type badge on Day screen too.
                        if (!t.type.isNullOrBlank()) {
                            Row(modifier = Modifier.padding(top = 4.dp)) {
                                StatusBadge(status = null, type = t.type, place = null)
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        IconButton(
                            onClick = {
                                if (editingId == t.id) {
                                    editingId = null
                                    editingText = ""
                                } else {
                                    editingId = t.id
                                    editingText = (t.text ?: "").trim()
                                }
                            }
                        ) {
                            androidx.compose.material3.Icon(Icons.Filled.Edit, contentDescription = "Править")
                        }
                        Button(
                            onClick = {
                                viewModel.archiveTaskAndRefreshAsync(deviceId, t.id)
                            }
                        ) {
                            Text("В архив", fontSize = 12.sp)
                        }
                    }
                }
                Divider(modifier = Modifier.padding(top = 8.dp))
            }
        }

        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
            items(dayItems.size) { idx ->
                val t = dayItems[idx]
                val time = parseDueLocalTime(t.dueDate)?.format(timeFmt)
                TaskRow(t, metaLine = time)
            }
        }
    }
}

private fun parseDueLocalDate(raw: String?): LocalDate? {
    val s = raw?.trim().orEmpty()
    if (s.isBlank()) return null
    // 1) Plain date "YYYY-MM-DD"
    try {
        return LocalDate.parse(s)
    } catch (_: Throwable) {
    }
    // 2) ISO datetime with offset
    try {
        return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    } catch (_: Throwable) {
    }
    // 3) ISO datetime with zone
    try {
        return ZonedDateTime.parse(s).withZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    } catch (_: Throwable) {
    }
    // 4) ISO instant
    try {
        return Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (_: Throwable) {
    }
    return null
}

private fun parseDueLocalTime(raw: String?): java.time.LocalTime? {
    val s = raw?.trim().orEmpty()
    if (s.isBlank()) return null
    // 1) ISO datetime with offset
    try {
        return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalTime()
    } catch (_: Throwable) {
    }
    // 2) ISO datetime with zone
    try {
        return ZonedDateTime.parse(s).withZoneSameInstant(ZoneId.systemDefault()).toLocalTime()
    } catch (_: Throwable) {
    }
    // 3) ISO local datetime (no zone/offset)
    try {
        return java.time.LocalDateTime.parse(s).toLocalTime()
    } catch (_: Throwable) {
    }
    // 4) ISO instant
    try {
        return Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalTime()
    } catch (_: Throwable) {
    }
    return null
}

@Composable
private fun StatusBadge(status: String?, type: String? = null, place: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Плашка ТИПА задания
        when (type?.lowercase()) {
            "memory" -> Badge(color = Color(0xFF673AB7), text = "ПАМЯТЬ")
            "timer", "text-timer" -> Badge(color = Color(0xFF009688), text = "ТАЙМЕР")
        }

        // Плашка СТАТУСА задания (выполнено, ожидает и т.д.)
        val (color, text) = when (status?.lowercase()) {
            "fired" -> Color(0xFF4CAF50) to "ВЫПОЛНЕНО"
            "pending" -> Color(0xFFFFA000) to "ОЖИДАЕТ"
            "scheduled" -> Color(0xFF2196F3) to "ПРИНЯТО"
            "cancelled" -> Color(0xFF9E9E9E) to "ОТМЕНЕНО"
            null -> Color.Transparent to ""
            else -> Color(0xFF757575) to status.uppercase()
        }

        if (text.isNotEmpty()) {
            Badge(color = color, text = text)
        }

        val p = place?.trim().orEmpty()
        if (p.isNotBlank()) {
            // "Place" is informational context for the task. Keep it subtle but visible.
            Badge(color = Color(0xFF00BCD4), text = "МЕСТО: $p")
        }
    }
}

@Composable
private fun Badge(color: Color, text: String) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(start = 8.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RequestsScreen(
    deviceId: String,
    viewModel: MainViewModel,
    onCancel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading = viewModel.requestsLoading.value
    val err = viewModel.requestsError.value
    val items = viewModel.requests.value
    val ctx = LocalContext.current

    var selectedFilter by remember { mutableStateOf(RequestFilter.PENDING) }

    val filteredItems = remember(items, selectedFilter) {
        when (selectedFilter) {
            RequestFilter.ALL -> items
            RequestFilter.PENDING -> items.filter {
                val s = it.pendingAction?.status?.lowercase()
                s == "pending" || s == "scheduled"
            }
            RequestFilter.DONE -> items.filter {
                it.pendingAction?.status?.lowercase() == "fired"
            }
            RequestFilter.CANCELLED -> items.filter {
                it.pendingAction?.status?.lowercase() == "cancelled"
            }
        }
    }

    LaunchedEffect(deviceId) {
        if (deviceId.isNotBlank()) viewModel.loadRequests(deviceId)
    }

    // Ingest "type=task" items from requests history into backend /tasks.
    LaunchedEffect(items, deviceId) {
        if (items.isEmpty()) return@LaunchedEffect
        val added = TasksIngestor.ingestFromRequests(ctx, deviceId, items)
        if (added > 0) {
            // Refresh tasks list so new tasks appear in Tasks tab.
            viewModel.loadTasks(deviceId, "active")
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Запросы", fontSize = 22.sp)
            Box(modifier = Modifier.weight(1f))
            Button(onClick = { viewModel.loadRequestsAsync(deviceId) }) {
                Text("Обновить")
            }
        }

        TabRow(
            selectedTabIndex = selectedFilter.ordinal,
            modifier = Modifier.padding(top = 8.dp),
            containerColor = Color.Transparent
        ) {
            RequestFilter.values().forEach { filter ->
                Tab(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    text = { Text(filter.label, fontSize = 13.sp) }
                )
            }
        }

        if (loading) {
            Text("Загрузка...", modifier = Modifier.padding(top = 12.dp))
        }
        if (!err.isNullOrBlank()) {
            Text("Ошибка: $err", modifier = Modifier.padding(top = 12.dp))
        }
        if (!loading && err.isNullOrBlank() && filteredItems.isEmpty()) {
            Text("В этой категории пока пусто.", modifier = Modifier.padding(top = 12.dp))
        }
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
            items(filteredItems.size) { idx ->
                val it = filteredItems[idx]
                val title = it.payload?.received?.text ?: "<без текста>"
                val timeAt = it.createdAt ?: ""
                val status = it.pendingAction?.status
                
                // Ищем тип: сначала в pendingAction, потом в nlu, потом в intent
                val type = it.pendingAction?.type 
                    ?: (it.payload?.nlu?.get("type") as? String)
                    ?: (it.payload?.intent?.get("type") as? String)

                // Ищем место (если есть). Это не "гео-алерт", просто контекст где выполнить.
                val place = (it.payload?.nlu?.get("place") as? String)
                    ?: (it.payload?.intent?.get("place") as? String)
                
                // Ищем время (длительность) в nlu
                val duration = it.payload?.nlu?.get("time") as? String

                val intentStr = it.payload?.intent?.toString()
                val nluStr = it.payload?.nlu?.toString()
                val ackStr = it.pendingAction?.ack?.toString()

                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f, fill = false))
                        StatusBadge(status, type, place)
                    }
                    if (timeAt.isNotBlank()) {
                        Text(timeAt, fontSize = 12.sp, color = Color.Gray)
                    }
                    
                    Row(modifier = Modifier.padding(top = 2.dp)) {
                        if (!type.isNullOrBlank()) {
                            Text("type: $type", fontSize = 11.sp, color = Color.Cyan.copy(alpha = 0.6f))
                        }
                        if (!duration.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text("time: $duration", fontSize = 11.sp, color = Color(0xFF81C784))
                        }
                    }

                    if (!nluStr.isNullOrBlank()) {
                        Text("nlu: $nluStr", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (!intentStr.isNullOrBlank()) {
                        Text("intent: $intentStr", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (!ackStr.isNullOrBlank()) {
                        Text("ack: $ackStr", fontSize = 11.sp, color = Color.LightGray, modifier = Modifier.padding(top = 2.dp))
                    }

                    // Кнопка отмены для активных задач
                    val actionId = it.pendingAction?.id
                    val s = it.pendingAction?.status?.lowercase()
                    if (actionId != null && (s == "pending" || s == "scheduled")) {
                        Button(
                            onClick = { onCancel(actionId) },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Отменить", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusScreen(
    statusText: String,
    activeActions: List<ActiveActionsStore.ActiveAction>,
    isWakeListeningEnabled: Boolean,
    onToggleWakeListening: (Boolean) -> Unit,
    onCancel: (Int) -> Unit,
    onDone: (Int, String, String) -> Unit,
    onSnooze: (Int, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val nowMs by produceState(initialValue = System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1000L)
            }
        }
        val activeScheduled = remember(activeActions) { activeActions.filter { it.state != "ringing" } }
        val activeRinging = remember(activeActions) { activeActions.filter { it.state == "ringing" } }

        var statusTab by remember { mutableStateOf(0) } // 0 = active, 1 = tasks

        Column(modifier = Modifier.padding(all = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = statusText,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = { onToggleWakeListening(!isWakeListeningEnabled) },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(
                    text = if (isWakeListeningEnabled) "Отключить прослушку" else "Включить прослушку",
                    fontSize = 14.sp
                )
            }

            TabRow(
                selectedTabIndex = statusTab,
                modifier = Modifier.padding(top = 12.dp),
                containerColor = Color.Transparent
            ) {
                Tab(
                    selected = statusTab == 0,
                    onClick = { statusTab = 0 },
                    text = { Text("Активные (${activeScheduled.size})", fontSize = 13.sp) }
                )
                Tab(
                    selected = statusTab == 1,
                    onClick = { statusTab = 1 },
                    text = { Text("Задачи (${activeRinging.size})", fontSize = 13.sp) }
                )
            }

            Spacer(Modifier.size(8.dp))

            when (statusTab) {
                0 -> {
                    if (activeScheduled.isEmpty()) {
                        Text("Активных таймеров/будильников нет.", modifier = Modifier.padding(top = 8.dp))
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(top = 4.dp)) {
                            items(activeScheduled.size) { idx ->
                                val a = activeScheduled[idx]
                                val prettyDateTime = remember(a.triggerAtEpochMs) { formatEpochMsRu(a.triggerAtEpochMs) }
                                val prettyCountdown = remember(nowMs, a.triggerAtEpochMs) { formatCountdownHms(nowMs, a.triggerAtEpochMs) }

                                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                    Text(text = a.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "$prettyDateTime  ($prettyCountdown)",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )

                    // Progress only for timers where we know duration.
                    val dur = a.durationMs
                    if (dur != null && dur > 0L) {
                        val elapsed = (nowMs - a.scheduledAtEpochMs).coerceAtLeast(0L).coerceAtMost(dur)
                        val progress = (elapsed.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.padding(top = 6.dp))
                    }

                                    Button(onClick = { onCancel(a.actionId) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Отменить")
                    }
                }
            }
        }
    }
                }

                else -> {
                    if (activeRinging.isEmpty()) {
                        Text("Пока нет задач, которые “сработали”.", modifier = Modifier.padding(top = 8.dp))
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(top = 4.dp)) {
                            items(activeRinging.size) { idx ->
                                val a = activeRinging[idx]
                                val prettyDateTime = remember(a.triggerAtEpochMs) { formatEpochMsRu(a.triggerAtEpochMs) }

                                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                    Text(text = a.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "$prettyDateTime  (00:00:00)",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )

                                    Row(modifier = Modifier.padding(top = 8.dp)) {
                                        Button(onClick = { onDone(a.actionId, a.type, a.label) }) {
                                            Text("Выполнено")
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Button(onClick = { onSnooze(a.actionId, a.type, a.label) }) {
                                            Text("+15")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatEpochMsRu(epochMs: Long): String {
    return try {
        val dt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale("ru", "RU"))
        dt.format(fmt)
    } catch (_: Throwable) {
        epochMs.toString()
    }
}

private fun formatCountdownHms(nowMs: Long, targetEpochMs: Long): String {
    val remainMs = (targetEpochMs - nowMs).coerceAtLeast(0L)
    val totalSec = (remainMs / 1000L).coerceAtLeast(0L)
    val hh = totalSec / 3600L
    val mm = (totalSec % 3600L) / 60L
    val ss = totalSec % 60L
    return "%02d:%02d:%02d".format(hh, mm, ss)
}
