package com.rdm.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unified monitoring manager that integrates all detection systems
 * and provides comprehensive logging with server synchronization
 */
class UnifiedMonitoringManager(private val context: Context) {
    private val TAG = "UnifiedMonitoringManager"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // All monitoring systems
    private val clipboardMonitor = ClipboardMonitor(context)
    private val deviceConnectionMonitor = DeviceConnectionMonitor(context)
    private val systemMonitor = SystemMonitor(context)
    private val smartRecordingManager = SmartRecordingManager(context)

    // State
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Server communication
    private var webSocketClient: WebSocketClient? = null
    private var deviceId: String? = null

    // Unified event logging
    private val unifiedEventLog = mutableListOf<UnifiedEvent>()

    // Local storage
    private val logsDir = File(context.getExternalFilesDir("logs"), "monitoring")
    private val maxLogFileSize = 10 * 1024 * 1024 // 10MB per log file

    data class UnifiedEvent(
        val eventSource: EventSource,
        val eventType: String,
        val timestamp: Long,
        val packageName: String?,
        val severity: String,
        val description: String,
        val details: Map<String, Any>
    )

    enum class EventSource {
        CLIPBOARD,
        DEVICE_CONNECTION,
        NOTIFICATION,
        SYSTEM,
        RECORDING,
        ANOMALY,
        FOREGROUND_APP
    }

    enum class EventSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    fun initialize(webSocketClient: WebSocketClient, deviceId: String) {
        this.webSocketClient = webSocketClient
        this.deviceId = deviceId

        // Create logs directory
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }

        setupEventCallbacks()
        Log.d(TAG, "Unified monitoring manager initialized")
    }

    fun start() {
        if (_isRunning.value) {
            Log.w(TAG, "Already running")
            return
        }

        _isRunning.value = true

        // Start all monitoring systems
        clipboardMonitor.startMonitoring()
        deviceConnectionMonitor.startMonitoring()
        systemMonitor.startMonitoring()

        // Start server synchronization
        startServerSync()

        // Start local log rotation
        startLogRotation()

        Log.d(TAG, "Unified monitoring manager started - all systems active")
    }

    fun stop() {
        if (!_isRunning.value) {
            return
        }

        _isRunning.value = false

        // Stop all monitoring systems
        clipboardMonitor.stopMonitoring()
        deviceConnectionMonitor.stopMonitoring()
        systemMonitor.stopMonitoring()

        // Final sync to server
        syncToServer()

        Log.d(TAG, "Unified monitoring manager stopped")
    }

    fun cleanup() {
        stop()
        scope.cancel()

        // Cleanup all monitors
        clipboardMonitor.cleanup()
        deviceConnectionMonitor.cleanup()
        systemMonitor.cleanup()
        smartRecordingManager.cleanup()

        Log.d(TAG, "Unified monitoring manager cleaned up")
    }

    private fun setupEventCallbacks() {
        // Clipboard events
        clipboardMonitor.setEventCallback { event ->
            handleEvent(
                EventSource.CLIPBOARD,
                event.eventType.name,
                event.packageName,
                event.isSensitive.toString(),
                event.content,
                event.details
            )
        }

        // Device connection events
        deviceConnectionMonitor.setEventCallback { event ->
            handleEvent(
                EventSource.DEVICE_CONNECTION,
                event.eventType.name,
                null,
                when (event.eventType) {
                    DeviceConnectionMonitor.ConnectionEventType.ADB_DETECTED,
                    DeviceConnectionMonitor.ConnectionEventType.UNAUTHORIZED_CONNECTION -> "HIGH"
                    else -> "MEDIUM"
                },
                event.connectionType.name,
                event.details
            )
        }

        // System events
        systemMonitor.setEventCallback { event ->
            handleEvent(
                EventSource.SYSTEM,
                event.eventType.name,
                event.packageName,
                event.severity.name,
                event.description,
                event.details
            )
        }
    }

    private fun handleEvent(
        source: EventSource,
        eventType: String,
        packageName: String?,
        severity: String,
        description: String,
        details: Map<String, Any>
    ) {
        val timestamp = System.currentTimeMillis()

        val event = UnifiedEvent(
            eventSource = source,
            eventType = eventType,
            timestamp = timestamp,
            packageName = packageName,
            severity = severity,
            description = description,
            details = details
        )

        // Add to unified log
        synchronized(unifiedEventLog) {
            unifiedEventLog.add(event)
            if (unifiedEventLog.size > 5000) {
                unifiedEventLog.removeAt(0)
            }
        }

        // Log locally
        logEventLocally(event)

        // Log to Android log system
        val logMessage = buildString {
            append("[$source]")
            append(" [$eventType]")
            if (packageName != null) append(" [$packageName]")
            append(" $description")
        }

        when (severity) {
            "HIGH", "CRITICAL" -> Log.w(TAG, "⚠️ $logMessage")
            else -> Log.i(TAG, logMessage)
        }

        // Send to server immediately for critical events
        if (severity in listOf("HIGH", "CRITICAL")) {
            sendEventToServer(event)
        }
    }

    private fun logEventLocally(event: UnifiedEvent) {
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(event.timestamp))
            val logFile = File(logsDir, "events_$date.jsonl")

            // Create log entry
            val logEntry = JSONObject().apply {
                put("timestamp", event.timestamp)
                put("source", event.eventSource.name)
                put("event_type", event.eventType)
                put("package_name", event.packageName)
                put("severity", event.severity)
                put("description", event.description)
                put("details", JSONObject(event.details))
            }

            // Append to file
            logFile.appendText("$logEntry\n")

            // Check file size and rotate if needed
            if (logFile.length() > maxLogFileSize) {
                rotateLogFile(logFile)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error logging event locally", e)
        }
    }

    private fun rotateLogFile(logFile: File) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val archiveName = logFile.name.replace(".jsonl", "_archived_$timestamp.jsonl")
            val archiveFile = File(logFile.parent, archiveName)

            logFile.renameTo(archiveFile)
            Log.d(TAG, "Log file rotated: ${archiveFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating log file", e)
        }
    }

    private fun startServerSync() {
        scope.launch {
            // Wait for WebSocket to be ready before first sync
            delay(5000) // 5 second delay to let WebSocket connect and stabilize

            while (_isRunning.value) {
                try {
                    syncToServer()
                    delay(60000) // Sync every minute
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing to server", e)
                    delay(120000) // Wait longer on error
                }
            }
        }
    }

    private fun syncToServer() {
        val recentEvents = synchronized(unifiedEventLog) {
            unifiedEventLog.filter {
                System.currentTimeMillis() - it.timestamp < 300000 // Last 5 minutes
            }.toList()
        }

        if (recentEvents.isNotEmpty()) {
            try {
                // Very aggressive batching to prevent overflow
                val maxBatchSize = 10 // Only send 10 most recent events
                val batchSize = minOf(recentEvents.size, maxBatchSize)
                val eventsToSend = recentEvents.takeLast(batchSize)

                // Send events one at a time to be extra safe
                for (event in eventsToSend) {
                    try {
                        val message = JSONObject().apply {
                            put("type", "unified_event")
                            put("device_id", deviceId)
                            put("data", JSONObject().apply {
                                put("timestamp", event.timestamp)
                                put("source", event.eventSource.name)
                                put("event_type", event.eventType)
                                put("package_name", event.packageName)
                                put("severity", event.severity)
                                put("description", event.description)
                                // Only include top 5 most important details
                                val limitedDetails = event.details.entries.take(5).associate { it.key to it.value }
                                put("details", JSONObject(limitedDetails))
                            })
                        }

                        val messageString = message.toString()
                        val messageSize = messageString.toByteArray().size

                        // Reject messages larger than 100 KB
                        if (messageSize > 100 * 1024) {
                            Log.w(TAG, "Event too large (${messageSize} bytes), skipping")
                            continue
                        }

                        webSocketClient?.send(messageString)
                        Log.d(TAG, "Sent event (${messageSize} bytes): ${event.eventType}")

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send individual event", e)
                    }
                }

                Log.d(TAG, "Synced ${eventsToSend.size} events")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending events to server", e)
            }
        }
    }

    private fun sendEventToServer(event: UnifiedEvent) {
        try {
            val message = JSONObject().apply {
                put("type", "unified_event")
                put("device_id", deviceId)
                put("data", JSONObject().apply {
                    put("timestamp", event.timestamp)
                    put("source", event.eventSource.name)
                    put("event_type", event.eventType)
                    put("package_name", event.packageName)
                    put("severity", event.severity)
                    put("description", event.description)
                    put("details", JSONObject(event.details))
                })
            }

            webSocketClient?.send(message.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending event to server", e)
        }
    }

    private fun startLogRotation() {
        scope.launch {
            while (_isRunning.value) {
                try {
                    performLogRotation()
                    delay(3600000) // Check every hour
                } catch (e: Exception) {
                    Log.e(TAG, "Error in log rotation", e)
                    delay(7200000) // Check every 2 hours on error
                }
            }
        }
    }

    private fun performLogRotation() {
        try {
            val logFiles = logsDir.listFiles()?.filter {
                it.name.endsWith(".jsonl") && !it.name.contains("archived")
            } ?: return

            for (logFile in logFiles) {
                if (logFile.length() > maxLogFileSize) {
                    rotateLogFile(logFile)
                }
            }

            // Delete old archived logs (older than 7 days)
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
            logsDir.listFiles()?.filter {
                it.name.contains("archived") && it.lastModified() < sevenDaysAgo
            }?.forEach {
                it.delete()
                Log.d(TAG, "Deleted old log file: ${it.name}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in log rotation", e)
        }
    }

    // Public API methods

    fun getRecentEvents(count: Int = 100): List<UnifiedEvent> {
        synchronized(unifiedEventLog) {
            return unifiedEventLog.takeLast(count)
        }
    }

    fun getEventsBySource(source: EventSource): List<UnifiedEvent> {
        synchronized(unifiedEventLog) {
            return unifiedEventLog.filter { it.eventSource == source }
        }
    }

    fun getEventsBySeverity(severity: String): List<UnifiedEvent> {
        synchronized(unifiedEventLog) {
            return unifiedEventLog.filter { it.severity == severity }
        }
    }

    fun getEventStatistics(): Map<String, Any> {
        synchronized(unifiedEventLog) {
            val events = unifiedEventLog

            return mapOf(
                "total_events" to events.size,
                "by_source" to events.groupingBy { it.eventSource.name }.eachCount(),
                "by_severity" to events.groupingBy { it.severity }.eachCount(),
                "by_type" to events.groupingBy { it.eventType }.eachCount(),
                "recent_24h" to events.count {
                    System.currentTimeMillis() - it.timestamp < 86400000
                }
            )
        }
    }

    fun getEventsAsJson(): String {
        val events = getRecentEvents(200)
        val jsonArray = org.json.JSONArray()

        for (event in events) {
            val eventJson = JSONObject().apply {
                put("timestamp", event.timestamp)
                put("source", event.eventSource.name)
                put("event_type", event.eventType)
                put("package_name", event.packageName)
                put("severity", event.severity)
                put("description", event.description)
                put("details", JSONObject(event.details))
            }
            jsonArray.put(eventJson)
        }

        return jsonArray.toString()
    }

    fun exportLogsToDate(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val logFile = File(logsDir, "events_$date.jsonl")

        return if (logFile.exists()) {
            logFile.readText()
        } else {
            "No logs found for today"
        }
    }

    fun clearLogs() {
        synchronized(unifiedEventLog) {
            unifiedEventLog.clear()
        }
        Log.d(TAG, "Unified event log cleared")
    }

    // Access to individual monitors
    fun getClipboardMonitor(): ClipboardMonitor = clipboardMonitor
    fun getDeviceConnectionMonitor(): DeviceConnectionMonitor = deviceConnectionMonitor
    fun getSystemMonitor(): SystemMonitor = systemMonitor
    fun getSmartRecordingManager(): SmartRecordingManager = smartRecordingManager
}
