package com.rdm.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class ForegroundAppMonitor(
    private val context: Context,
    private val rootExecutor: RootExecutor,
    private val webSocketClient: WebSocketClient,
    private val onRecordingTrigger: (String) -> Unit,
    private val onRecordingStop: () -> Unit
) {
    private val TAG = "ForegroundAppMonitor"

    private val _currentApp = MutableStateFlow<ForegroundAppInfo?>(null)
    val currentApp: StateFlow<ForegroundAppInfo?> = _currentApp.asStateFlow()

    private var monitoringJob: Job? = null
    private var isRunning = false
    private val checkIntervalMs = 3000L // Check every 3 seconds

    private var lastPackage: String? = null
    private val recordingApps = mutableSetOf<String>()
    private var isRecording = false

    suspend fun start() {
        if (isRunning) {
            Log.d(TAG, "Monitor already running")
            return
        }

        isRunning = true

        // Load recording apps from preferences
        val appListCollector = AppListCollector(context)
        recordingApps.addAll(appListCollector.getRecordingApps())
        Log.d(TAG, "Loaded ${recordingApps.size} recording apps: $recordingApps")

        Log.d(TAG, "Starting foreground app monitor (interval: ${checkIntervalMs}ms)")

        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                try {
                    checkForegroundApp()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking foreground app", e)
                }
                delay(checkIntervalMs)
            }
        }
    }

    fun stop() {
        if (!isRunning) {
            return
        }

        isRunning = false
        monitoringJob?.cancel()
        monitoringJob = null

        // Stop recording if active
        if (isRecording) {
            onRecordingStop()
            isRecording = false
        }

        Log.d(TAG, "Foreground app monitor stopped")
    }

    private suspend fun checkForegroundApp() {
        val appInfo = rootExecutor.getForegroundAppInfo()

        // Only send update if app has changed
        if (appInfo.packageName != null && appInfo.packageName != lastPackage) {
            lastPackage = appInfo.packageName
            _currentApp.value = appInfo

            Log.d(TAG, "Foreground app changed: ${appInfo.packageName} (${appInfo.activityName})")
            sendForegroundAppUpdate(appInfo)

            // Check if we should start/stop recording
            handleRecordingForApp(appInfo.packageName)
        }
    }

    private fun handleRecordingForApp(packageName: String?) {
        if (packageName == null) return

        val shouldRecord = packageName in recordingApps

        if (shouldRecord && !isRecording) {
            // Start recording when app opens
            Log.d(TAG, "Starting recording for app: $packageName")
            isRecording = true
            onRecordingTrigger(packageName)
        } else if (!shouldRecord && isRecording) {
            // Stop recording when app closes
            Log.d(TAG, "Stopping recording (app changed)")
            isRecording = false
            onRecordingStop()
        }
    }

    fun updateRecordingApps(appPackages: List<String>) {
        recordingApps.clear()
        recordingApps.addAll(appPackages)
        Log.d(TAG, "Updated recording apps: $recordingApps")

        // Update preferences
        val appListCollector = AppListCollector(context)
        appPackages.forEach { appListCollector.addRecordingApp(it) }

        // If currently recording and current app is not in new list, stop recording
        val currentPackage = _currentApp.value?.packageName
        if (isRecording && currentPackage != null && currentPackage !in recordingApps) {
            Log.d(TAG, "Current app removed from recording list, stopping recording")
            isRecording = false
            onRecordingStop()
        }
    }

    fun getRecordingApps(): List<String> {
        return recordingApps.toList()
    }

    private fun sendForegroundAppUpdate(appInfo: ForegroundAppInfo) {
        try {
            val message = JSONObject().apply {
                put("type", "foreground_app")
                put("device_id", webSocketClient.deviceId)
                put("data", JSONObject().apply {
                    put("package_name", appInfo.packageName)
                    put("activity_name", appInfo.activityName)
                    put("timestamp", appInfo.timestamp)
                    put("is_recording", isRecording)
                })
            }
            webSocketClient.send(message.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send foreground app update", e)
        }
    }

    fun getCurrentForegroundApp(): ForegroundAppInfo? {
        return _currentApp.value
    }

    fun isMonitoring(): Boolean {
        return isRunning
    }

    fun isCurrentlyRecording(): Boolean {
        return isRecording
    }
}
