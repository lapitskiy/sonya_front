package com.example.sonya_front

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import android.util.Log
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val _recognizedText = mutableStateOf("Готов к работе. Скажите 'соня приём'...")
    val recognizedText: State<String> = _recognizedText

    private val _snackbarMessage = mutableStateOf<String?>(null)
    val snackbarMessage: State<String?> = _snackbarMessage

    private val _activeActions = mutableStateOf<List<ActiveActionsStore.ActiveAction>>(emptyList())
    val activeActions: State<List<ActiveActionsStore.ActiveAction>> = _activeActions

    private val _requests = mutableStateOf<List<WhatSaidRequestItem>>(emptyList())
    val requests: State<List<WhatSaidRequestItem>> = _requests

    private val _requestsLoading = mutableStateOf(false)
    val requestsLoading: State<Boolean> = _requestsLoading

    private val _requestsError = mutableStateOf<String?>(null)
    val requestsError: State<String?> = _requestsError

    private val _tasksActive = mutableStateOf<List<TaskItem>>(emptyList())
    val tasksActive: State<List<TaskItem>> = _tasksActive

    private val _tasksDone = mutableStateOf<List<TaskItem>>(emptyList())
    val tasksDone: State<List<TaskItem>> = _tasksDone

    private val _tasksLoading = mutableStateOf(false)
    val tasksLoading: State<Boolean> = _tasksLoading

    private val _tasksError = mutableStateOf<String?>(null)
    val tasksError: State<String?> = _tasksError

    fun onNewRecognitionResult(text: String) {
        _recognizedText.value = text
    }

    fun updateStatus(status: String) {
        _recognizedText.value = status
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun refreshActiveActions(context: Context) {
        _activeActions.value = ActiveActionsStore.getAll(context.applicationContext)
    }

    suspend fun loadTasks(deviceId: String, status: String) {
        if (deviceId.isBlank()) { Log.w("VM_TASKS", "loadTasks: deviceId is blank"); return }
        Log.d("VM_TASKS", "loadTasks start deviceId=$deviceId status=$status")
        _tasksLoading.value = true
        _tasksError.value = null
        try {
            val resp = withContext(Dispatchers.IO) {
                ApiClient.instance.getTasks(deviceId = deviceId, status = status, limit = 50, offset = 0)
            }
            Log.d("VM_TASKS", "loadTasks OK: ${resp.items.size} items")
            if (status.lowercase() == "done") {
                _tasksDone.value = resp.items
            } else {
                _tasksActive.value = resp.items
            }
        } catch (t: Throwable) {
            Log.e("VM_TASKS", "loadTasks FAIL: ${t.javaClass.simpleName}: ${t.message}", t)
            _tasksError.value = t.message ?: "Ошибка загрузки задач"
        } finally {
            _tasksLoading.value = false
        }
    }

    fun loadTasksAsync(deviceId: String, status: String) {
        viewModelScope.launch {
            loadTasks(deviceId, status)
        }
    }

    suspend fun createTask(deviceId: String, text: String, urgent: Boolean, important: Boolean, type: String? = null) {
        if (deviceId.isBlank()) return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        _tasksError.value = null
        try {
            withContext(Dispatchers.IO) {
                ApiClient.instance.createTask(
                    CreateTaskRequest(
                        deviceId = deviceId,
                        text = trimmed,
                        urgent = urgent,
                        important = important,
                        type = type,
                    )
                )
            }
        } catch (t: Throwable) {
            _tasksError.value = t.message ?: "Ошибка создания задачи"
        }
    }

    fun createTaskAndRefreshActiveAsync(deviceId: String, text: String, urgent: Boolean, important: Boolean, type: String? = null) {
        viewModelScope.launch {
            createTask(deviceId, text, urgent, important, type)
            loadTasks(deviceId, "active")
        }
    }

    suspend fun updateTask(taskId: Int, text: String?, urgent: Boolean?, important: Boolean?, status: String?) {
        if (taskId <= 0) return
        _tasksError.value = null
        try {
            withContext(Dispatchers.IO) {
                ApiClient.instance.updateTask(
                    id = taskId,
                    body = UpdateTaskRequest(
                        text = text,
                        urgent = urgent,
                        important = important,
                        status = status,
                    )
                )
            }
        } catch (t: Throwable) {
            _tasksError.value = t.message ?: "Ошибка обновления задачи"
        }
    }

    fun updateTaskAndRefreshActiveAsync(deviceId: String, taskId: Int, text: String?, urgent: Boolean?, important: Boolean?, status: String?) {
        viewModelScope.launch {
            updateTask(taskId, text, urgent, important, status)
            loadTasks(deviceId, "active")
        }
    }

    fun archiveTaskAndRefreshAsync(deviceId: String, taskId: Int) {
        viewModelScope.launch {
            updateTask(taskId, text = null, urgent = null, important = null, status = "done")
            loadTasks(deviceId, "active")
            loadTasks(deviceId, "done")
        }
    }

    suspend fun loadRequests(deviceId: String) {
        if (deviceId.isBlank()) { Log.w("VM_REQ", "loadRequests: deviceId is blank"); return }
        Log.d("VM_REQ", "loadRequests start deviceId=$deviceId")
        _requestsLoading.value = true
        _requestsError.value = null
        try {
            val resp = withContext(Dispatchers.IO) {
                ApiClient.instance.getWhatSaidRequests(deviceId = deviceId, limit = 50, offset = 0)
            }
            Log.d("VM_REQ", "loadRequests OK: ${resp.items.size} items")
            _requests.value = resp.items
        } catch (t: Throwable) {
            Log.e("VM_REQ", "loadRequests FAIL: ${t.javaClass.simpleName}: ${t.message}", t)
            _requestsError.value = t.message ?: "Ошибка загрузки запросов"
        } finally {
            _requestsLoading.value = false
        }
    }

    fun loadRequestsAsync(deviceId: String) {
        viewModelScope.launch {
            loadRequests(deviceId)
        }
    }
}