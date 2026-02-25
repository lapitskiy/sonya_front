package com.example.sonya_front

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.PUT
import retrofit2.http.Path
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// 1. Модель данных для отправки на сервер, соответствующая вашему Body
data class CommandRequest(
    @param:Json(name = "device_id")
    @field:Json(name = "device_id")
    val deviceId: String,
    val text: String,
    val lat: Double?,
    val lon: Double?,
    @param:Json(name = "device_time")
    @field:Json(name = "device_time")
    val deviceTime: String
)

// ---- Pending actions (backend -> Android) ----

data class PendingAction(
    val id: Int,
    val type: String, // timer | text-timer | unknown | ...
    // Some action types (e.g. "memory") may not carry time.
    val time: String? = null, // ISO-8601 duration (PT10S) or datetime with timezone (2026-01-29T09:00:00+03:00)
    val text: String? = null,
    // Backend may send interest as number (0.8 / 80) or as string ("100%").
    val interest: Any? = null,
    @param:Json(name = "lat")
    @field:Json(name = "lat")
    val lat: Double? = null,
    @param:Json(name = "lon")
    @field:Json(name = "lon")
    val lon: Double? = null,
    @param:Json(name = "radius_m")
    @field:Json(name = "radius_m")
    val radiusM: Double? = null,
)

// ---- Optional direct /command response (backend -> Android) ----
// Some backends may return a single action directly from /command, e.g.
// {"type":"text-timer","time":"PT30M","text":"Вынуть белье"} (id may be absent).
data class CommandResponseAction(
    val id: Int? = null,
    val type: String? = null,
    val time: String? = null,
    val text: String? = null,
)

data class PendingActionsResponse(
    val items: List<PendingAction> = emptyList()
)

// ---- Tasks (simple todos without due date) ----

data class TasksResponse(
    val items: List<TaskItem> = emptyList()
)

data class TaskItem(
    val id: Int,
    val text: String? = null,
    val urgent: Boolean? = null,
    val important: Boolean? = null,
    val type: String? = null, // e.g. "today"
    @param:Json(name = "due_date")
    @field:Json(name = "due_date")
    val dueDate: String? = null, // ISO datetime/date, e.g. 2026-02-02 or 2026-02-02T09:00:00+03:00
    val status: String? = null, // active | done
    @param:Json(name = "created_at")
    @field:Json(name = "created_at")
    val createdAt: String? = null,
)

data class CreateTaskRequest(
    @param:Json(name = "device_id")
    @field:Json(name = "device_id")
    val deviceId: String,
    val text: String,
    val urgent: Boolean = false,
    val important: Boolean = false,
    val type: String? = null,
)

data class UpdateTaskRequest(
    val text: String? = null,
    val urgent: Boolean? = null,
    val important: Boolean? = null,
    val status: String? = null, // "done" to archive
)

data class AckRequest(
    @param:Json(name = "device_id")
    @field:Json(name = "device_id")
    val deviceId: String,
    @param:Json(name = "action_id")
    @field:Json(name = "action_id")
    val actionId: Int,
    val status: String, // scheduled | fired
    val ack: Map<String, String> = emptyMap(),
)

// 2. Интерфейс, описывающий API
interface BackendApi {
    // Указываем эндпоинт "command"
    @POST("command")
    suspend fun sendCommand(@Body command: CommandRequest): Response<ResponseBody>

    @GET("pending-actions")
    suspend fun getPendingActions(
        @Query("device_id") deviceId: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): PendingActionsResponse

    @POST("ack")
    suspend fun ack(@Body body: AckRequest)

    // --- Tasks (backend) ---
    @GET("tasks")
    suspend fun getTasks(
        @Query("device_id") deviceId: String,
        @Query("status") status: String? = null, // active | done
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): TasksResponse

    @POST("tasks")
    suspend fun createTask(@Body body: CreateTaskRequest): TaskItem

    @PUT("tasks/{id}")
    suspend fun updateTask(@Path("id") id: Int, @Body body: UpdateTaskRequest): TaskItem

    // --- Requests/history (UI tab) ---
    @GET("what-said/requests")
    suspend fun getWhatSaidRequests(
        @Query("device_id") deviceId: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): WhatSaidRequestsResponse
}

data class WhatSaidRequestsResponse(
    val items: List<WhatSaidRequestItem> = emptyList()
)

data class WhatSaidRequestItem(
    val id: Int? = null,
    @param:Json(name = "created_at")
    @field:Json(name = "created_at")
    val createdAt: String? = null,
    val payload: WhatSaidPayload? = null,
    @param:Json(name = "pending_action")
    @field:Json(name = "pending_action")
    val pendingAction: WhatSaidPendingAction? = null,
)

data class WhatSaidPayload(
    val received: WhatSaidReceived? = null,
    val intent: Map<String, Any?>? = null,
    val nlu: Map<String, Any?>? = null,
)

data class WhatSaidReceived(
    val text: String? = null,
)

data class WhatSaidPendingAction(
    val id: Int? = null,
    val type: String? = null,
    val status: String? = null, // pending | ack | ...
    val ack: Map<String, Any?>? = null,
)

// 3. Объект для создания клиента Retrofit
object ApiClient {
    private const val EXTERNAL_BASE_URL = "http://188.243.119.154:18000/"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val logging = HttpLoggingInterceptor { message -> Log.d("API_HTTP", message) }.apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.BASIC
    }

    private fun debugEventListenerFactory(): EventListener.Factory {
        if (!BuildConfig.DEBUG) return EventListener.Factory { EventListener.NONE }
        return EventListener.Factory { call ->
            object : EventListener() {
                private val startNs = System.nanoTime()
                private fun ms(): Long = (System.nanoTime() - startNs) / 1_000_000L
                private fun id(): String = Integer.toHexString(System.identityHashCode(call))
                private fun url(): String = try { call.request().url.toString() } catch (_: Throwable) { "<unknown>" }

                override fun callStart(call: Call) {
                    Log.d("API_EVT", "[${id()}] callStart ${url()}")
                }

                override fun dnsStart(call: Call, domainName: String) {
                    Log.d("API_EVT", "[${id()}] dnsStart +${ms()}ms $domainName")
                }

                override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
                    Log.d("API_EVT", "[${id()}] dnsEnd +${ms()}ms $domainName ips=${inetAddressList.size}")
                }

                override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                    Log.d("API_EVT", "[${id()}] connectStart +${ms()}ms ${inetSocketAddress.address}:${inetSocketAddress.port} proxy=$proxy")
                }

                override fun secureConnectStart(call: Call) {
                    Log.d("API_EVT", "[${id()}] secureConnectStart +${ms()}ms")
                }

                override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                    Log.d("API_EVT", "[${id()}] secureConnectEnd +${ms()}ms tls=${handshake?.tlsVersion}")
                }

                override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: Protocol?) {
                    Log.d("API_EVT", "[${id()}] connectEnd +${ms()}ms protocol=$protocol")
                }

                override fun connectFailed(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: Protocol?, ioe: IOException) {
                    Log.w("API_EVT", "[${id()}] connectFailed +${ms()}ms ${ioe.javaClass.simpleName}: ${ioe.message}")
                }

                override fun requestHeadersStart(call: Call) {
                    Log.d("API_EVT", "[${id()}] requestHeadersStart +${ms()}ms")
                }

                override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                    Log.d("API_EVT", "[${id()}] requestHeadersEnd +${ms()}ms")
                }

                override fun responseHeadersStart(call: Call) {
                    Log.d("API_EVT", "[${id()}] responseHeadersStart +${ms()}ms")
                }

                override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                    Log.d("API_EVT", "[${id()}] responseHeadersEnd +${ms()}ms code=${response.code}")
                }

                override fun responseBodyStart(call: Call) {
                    Log.d("API_EVT", "[${id()}] responseBodyStart +${ms()}ms")
                }

                override fun responseBodyEnd(call: Call, byteCount: Long) {
                    Log.d("API_EVT", "[${id()}] responseBodyEnd +${ms()}ms bytes=$byteCount")
                }

                override fun callEnd(call: Call) {
                    Log.d("API_EVT", "[${id()}] callEnd +${ms()}ms")
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    Log.e("API_EVT", "[${id()}] callFailed +${ms()}ms ${ioe.javaClass.simpleName}: ${ioe.message}")
                }
            }
        }
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .eventListenerFactory(debugEventListenerFactory())
            .build()
    }

    // Only external API is supported (home LAN base is intentionally disabled).

    private fun buildApi(baseUrl: String): BackendApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BackendApi::class.java)
    }

    private val externalApi: BackendApi by lazy { buildApi(EXTERNAL_BASE_URL) }

    // Backwards-compatible instance: existing call sites can keep calling ApiClient.instance.sendCommand(...)
    val instance: BackendApi by lazy {
        object : BackendApi {
            override suspend fun sendCommand(command: CommandRequest): Response<ResponseBody> {
                Log.i("API_BASE", "Using external base for /command")
                return externalApi.sendCommand(command)
            }

            override suspend fun getPendingActions(deviceId: String, limit: Int?, offset: Int?): PendingActionsResponse {
                Log.i("API_BASE", "Using external base for /pending-actions")
                return externalApi.getPendingActions(deviceId, limit, offset)
            }

            override suspend fun ack(body: AckRequest) {
                Log.i("API_BASE", "Using external base for /ack")
                externalApi.ack(body)
            }

            override suspend fun getTasks(deviceId: String, status: String?, limit: Int?, offset: Int?): TasksResponse {
                Log.i("API_BASE", "Using external base for /tasks")
                return externalApi.getTasks(deviceId, status, limit, offset)
            }

            override suspend fun createTask(body: CreateTaskRequest): TaskItem {
                Log.i("API_BASE", "Using external base for POST /tasks")
                return externalApi.createTask(body)
            }

            override suspend fun updateTask(id: Int, body: UpdateTaskRequest): TaskItem {
                Log.i("API_BASE", "Using external base for PUT /tasks/$id")
                return externalApi.updateTask(id, body)
            }

            override suspend fun getWhatSaidRequests(deviceId: String, limit: Int?, offset: Int?): WhatSaidRequestsResponse {
                Log.i("API_BASE", "Using external base for /what-said/requests")
                return externalApi.getWhatSaidRequests(deviceId, limit, offset)
            }
        }
    }
}