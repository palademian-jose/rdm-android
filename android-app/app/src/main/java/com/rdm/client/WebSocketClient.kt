package com.rdm.client

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketClient(
    private val context: Context,
    private val serverUrl: String,
    private val deviceId: String
) {
    private val TAG = "WebSocketClient"
    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onMessage: ((JsonObject) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    fun connect() {
        scope.launch {
            try {
                Log.d(TAG, "Connecting to $serverUrl")
                val request = Request.Builder()
                    .url(serverUrl)
                    .build()

                webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        Log.d(TAG, "WebSocket connected")
                        isConnected = true
                        onConnected?.invoke()
                        sendDeviceInfo()
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        Log.d(TAG, "Received: $text")
                        try {
                            val json = JSONObject(text)
                            handleIncomingMessage(json)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing message", e)
                        }
                    }

                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "Closing: $code - $reason")
                        isConnected = false
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "Closed: $code - $reason")
                        isConnected = false
                        onDisconnected?.invoke()
                        scheduleReconnect()
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "WebSocket error", t)
                        isConnected = false
                        onError?.invoke(Exception(t))
                        scheduleReconnect()
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                onError?.invoke(e)
                scheduleReconnect()
            }
        }
    }

    private fun handleIncomingMessage(json: JSONObject) {
        when (val type = json.optString("type")) {
            "auth" -> {
                // Authentication request from server
                val token = json.optString("token")
                if (token.isNotEmpty()) {
                    sendAuth(token)
                }
            }
            "command" -> {
                // Execute command
                val commandId = json.optString("id")
                val command = json.optString("command")
                val sudo = json.optBoolean("sudo", false)
                executeCommand(commandId, command, sudo)
            }
            else -> {
                // Pass to handler
                scope.launch {
                    onMessage?.invoke(gson.fromJson(json.toString(), JsonObject::class.java))
                }
            }
        }
    }

    private fun sendDeviceInfo() {
        scope.launch {
            try {
                val deviceInfo = DeviceInfoCollector.collect(context)
                val message = gson.toJson(mapOf(
                    "type" to "device_info",
                    "device_id" to deviceId,
                    "info" to deviceInfo
                ))

                sendMessage(message)
                Log.d(TAG, "Device info sent")

                // Schedule periodic updates
                while (isConnected) {
                    delay(60000) // Every minute
                    sendHeartbeat()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending device info", e)
            }
        }
    }

    private fun sendHeartbeat() {
        val message = gson.toJson(mapOf(
            "type" to "heartbeat",
            "device_id" to deviceId,
            "timestamp" to System.currentTimeMillis()
        ))
        sendMessage(message)
    }

    private fun sendAuth(token: String) {
        val message = gson.toJson(mapOf(
            "type" to "auth",
            "token" to token
        ))
        sendMessage(message)
    }

    private fun sendLog(level: String, message: String, data: String? = null) {
        val payload = mutableMapOf(
            "type" to "log",
            "device_id" to deviceId,
            "level" to level,
            "message" to message
        )
        if (data != null) {
            payload["data"] = data
        }

        val messageStr = gson.toJson(payload)
        sendMessage(messageStr)
    }

    private suspend fun executeCommand(commandId: String, command: String, sudo: Boolean) {
        try {
            sendLog("info", "Executing command: $command", mapOf("command_id" to commandId).toString())

            val rootExecutor = RootExecutor()
            val result = rootExecutor.execute(command, sudo)

            val response = gson.toJson(mapOf(
                "type" to "command_result",
                "id" to commandId,
                "success" to result.success,
                "output" to (result.output ?: ""),
                "error" to result.error
            ))

            sendMessage(response)

            if (result.success) {
                sendLog("info", "Command completed: $command", result.output)
            } else {
                sendLog("error", "Command failed: $command", result.error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command", e)

            val response = gson.toJson(mapOf(
                "type" to "command_result",
                "id" to commandId,
                "success" to false,
                "output" to "",
                "error" to e.message
            ))

            sendMessage(response)
            sendLog("error", "Command execution error: ${e.message}")
        }
    }

    private fun sendMessage(message: String) {
        try {
            webSocket?.send(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Scheduling reconnect in 5 seconds...")
            delay(5000)
            if (!isConnected) {
                connect()
            }
        }
    }

    fun disconnect() {
        scope.launch {
            reconnectJob?.cancel()
            webSocket?.close(1000, "Client disconnecting")
            isConnected = false
        }
    }

    fun isConnected(): Boolean = isConnected
}
