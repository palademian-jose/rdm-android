package com.rdm.client

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.MediaRouter
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Comprehensive system monitoring for detection of:
 * - File system changes
 * - Screen casting/mirroring
 * - Camera usage
 * - Work profile apps
 * - Lock screen activity
 * - Network changes
 */
class SystemMonitor(private val context: Context) {
    private val TAG = "SystemMonitor"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // State
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    // Sub-monitors
    private var onSystemEvent: ((SystemEvent) -> Unit)? = null

    // Event logging
    private val eventLog = mutableListOf<SystemEvent>()

    // File system monitoring state
    private var fileSnapshots = mutableMapOf<String, Long>()
    private var lastFileCheckTime = 0L

    // Screen casting state
    private var isScreenCasting = false
    private var mediaRouter: MediaRouter? = null

    data class SystemEvent(
        val eventType: SystemEventType,
        val timestamp: Long,
        val packageName: String?,
        val description: String,
        val severity: EventSeverity,
        val details: Map<String, Any>
    )

    enum class SystemEventType {
        // File system events
        FILE_CREATED,
        FILE_MODIFIED,
        FILE_DELETED,
        SUSPICIOUS_FILE_OPERATION,

        // Screen events
        SCREEN_CASTING_STARTED,
        SCREEN_CASTING_STOPPED,
        SCREEN_RECORDING_DETECTED,

        // Camera events
        CAMERA_OPENED,
        CAMERA_CLOSED,
        SUSPICIOUS_CAMERA_USAGE,

        // Work profile events
        WORK_PROFILE_APP_DETECTED,
        SECONDARY_USER_DETECTED,
        ISOLATED_PROCESS_DETECTED,

        // Lock screen events
        LOCK_SCREEN_ACTIVITY_DETECTED,
        DEVICE_LOCKED,
        DEVICE_UNLOCKED,

        // Network events
        VPN_DETECTED,
        TOR_DETECTED,
        SUSPICIOUS_NETWORK_ACTIVITY,

        // System events
        SYSTEM_SETTINGS_CHANGED,
        ACCESSIBILITY_SERVICE_CHANGED,
        INPUT_METHOD_CHANGED
    }

    enum class EventSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    fun setEventCallback(callback: (SystemEvent) -> Unit) {
        onSystemEvent = callback
    }

    fun startMonitoring() {
        if (_isMonitoring.value) {
            Log.w(TAG, "System monitoring already active")
            return
        }

        _isMonitoring.value = true

        // Initialize media router for screen casting detection
        mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter

        // Take initial file system snapshot
        takeFileSystemSnapshot()

        // Start monitoring tasks
        startFileSystemMonitoring()
        startWorkProfileMonitoring()
        startNetworkMonitoring()
        startAccessibilityMonitoring()

        Log.d(TAG, "Comprehensive system monitoring started")
    }

    fun stopMonitoring() {
        if (!_isMonitoring.value) {
            return
        }

        _isMonitoring.value = false
        scope.cancel()
        mediaRouter = null

        Log.d(TAG, "System monitoring stopped")
    }

    private fun takeFileSystemSnapshot() {
        val sensitiveDirs = listOf(
            Environment.getExternalStorageDirectory().absolutePath,
            context.getExternalFilesDir(null)?.absolutePath,
            "/sdcard/Download",
            "/sdcard/Documents",
            "/sdcard/DCIM"
        )

        for (dir in sensitiveDirs) {
            try {
                val directory = File(dir)
                if (directory.exists() && directory.isDirectory) {
                    directory.walkTopDown().maxDepth(3).forEach { file ->
                        if (file.isFile) {
                            fileSnapshots[file.absolutePath] = file.lastModified()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning directory: $dir", e)
            }
        }

        Log.d(TAG, "File system snapshot taken: ${fileSnapshots.size} files")
        lastFileCheckTime = System.currentTimeMillis()
    }

    private fun startFileSystemMonitoring() {
        scope.launch {
            while (_isMonitoring.value) {
                try {
                    checkFileSystemChanges()
                    delay(30000) // Check every 30 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in file system monitoring", e)
                    delay(60000)
                }
            }
        }
    }

    private fun checkFileSystemChanges() {
        val currentTime = System.currentTimeMillis()

        // Check for new files
        val currentFiles = mutableMapOf<String, Long>()

        val sensitiveDirs = listOf(
            Environment.getExternalStorageDirectory().absolutePath,
            context.getExternalFilesDir(null)?.absolutePath,
            "/sdcard/Download",
            "/sdcard/Documents"
        )

        for (dir in sensitiveDirs) {
            try {
                val directory = File(dir)
                if (directory.exists() && directory.isDirectory) {
                    directory.walkTopDown().maxDepth(2).forEach { file ->
                        if (file.isFile) {
                            currentFiles[file.absolutePath] = file.lastModified()

                            // Check if it's a new file
                            if (!fileSnapshots.containsKey(file.absolutePath)) {
                                handleFileCreated(file)
                            }
                            // Check if it was modified
                            else if (fileSnapshots[file.absolutePath] != file.lastModified()) {
                                handleFileModified(file)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking directory: $dir", e)
            }
        }

        // Check for deleted files
        for ((filePath, _) in fileSnapshots) {
            if (!currentFiles.containsKey(filePath)) {
                handleFileDeleted(filePath)
            }
        }

        fileSnapshots = currentFiles
        lastFileCheckTime = currentTime
    }

    private fun handleFileCreated(file: File) {
        val suspiciousExtensions = listOf(
            ".log", ".txt", ".doc", ".docx", ".pdf",
            ".zip", ".rar", ".7z", ".enc", ".db"
        )

        val isSuspicious = suspiciousExtensions.any { file.name.lowercase().endsWith(it) }
        val isInSensitiveDir = file.absolutePath.contains("Download") ||
                             file.absolutePath.contains("Documents")

        if (isSuspicious && isInSensitiveDir) {
            val event = SystemEvent(
                eventType = SystemEventType.SUSPICIOUS_FILE_OPERATION,
                timestamp = System.currentTimeMillis(),
                packageName = null,
                description = "Suspicious file created: ${file.name}",
                severity = EventSeverity.MEDIUM,
                details = mapOf(
                    "file_path" to file.absolutePath,
                    "file_size" to file.length(),
                    "file_extension" to file.extension,
                    "creation_time" to file.lastModified()
                )
            )

            logEvent(event)
            onSystemEvent?.invoke(event)
        } else {
            val event = SystemEvent(
                eventType = SystemEventType.FILE_CREATED,
                timestamp = System.currentTimeMillis(),
                packageName = null,
                description = "File created: ${file.name}",
                severity = EventSeverity.LOW,
                details = mapOf(
                    "file_path" to file.absolutePath,
                    "file_size" to file.length()
                )
            )

            logEvent(event)
        }
    }

    private fun handleFileModified(file: File) {
        val event = SystemEvent(
            eventType = SystemEventType.FILE_MODIFIED,
            timestamp = System.currentTimeMillis(),
            packageName = null,
            description = "File modified: ${file.name}",
            severity = EventSeverity.LOW,
            details = mapOf(
                "file_path" to file.absolutePath,
                "new_size" to file.length(),
                "modification_time" to file.lastModified()
            )
        )

        logEvent(event)
    }

    private fun handleFileDeleted(filePath: String) {
        val fileName = File(filePath).name

        val event = SystemEvent(
            eventType = SystemEventType.FILE_DELETED,
            timestamp = System.currentTimeMillis(),
            packageName = null,
            description = "File deleted: $fileName",
            severity = EventSeverity.MEDIUM,
            details = mapOf(
                "file_path" to filePath,
                "was_tracked" to true
            )
        )

        logEvent(event)
        onSystemEvent?.invoke(event)
    }

    private fun startWorkProfileMonitoring() {
        scope.launch {
            while (_isMonitoring.value) {
                try {
                    checkWorkProfileApps()
                    delay(60000) // Check every minute
                } catch (e: Exception) {
                    Log.e(TAG, "Error in work profile monitoring", e)
                    delay(120000)
                }
            }
        }
    }

    private fun checkWorkProfileApps() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val userManager = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
                val userProfiles = userManager.userProfiles

                if (userProfiles.size > 1) {
                    val event = SystemEvent(
                        eventType = SystemEventType.SECONDARY_USER_DETECTED,
                        timestamp = System.currentTimeMillis(),
                        packageName = null,
                        description = "Multiple user profiles detected: ${userProfiles.size}",
                        severity = EventSeverity.MEDIUM,
                        details = mapOf(
                            "user_count" to userProfiles.size,
                            "user_profiles_detected" to true
                        )
                    )

                    logEvent(event)
                    onSystemEvent?.invoke(event)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking work profiles", e)
            }
        }
    }

    private fun startNetworkMonitoring() {
        scope.launch {
            while (_isMonitoring.value) {
                try {
                    checkVPNStatus()
                    delay(30000) // Check every 30 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in network monitoring", e)
                    delay(60000)
                }
            }
        }
    }

    private fun checkVPNStatus() {
        // Check if VPN is active
        val vpnInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetworkInfo
            activeNetwork?.type == android.net.ConnectivityManager.TYPE_VPN
        } else {
            false
        }

        if (vpnInfo) {
            val event = SystemEvent(
                eventType = SystemEventType.VPN_DETECTED,
                timestamp = System.currentTimeMillis(),
                packageName = null,
                description = "VPN connection detected",
                severity = EventSeverity.MEDIUM,
                details = mapOf(
                    "risk_level" to "Network traffic may be hidden",
                    "monitoring_impact" to "Cannot inspect encrypted traffic"
                )
            )

            logEvent(event)
            onSystemEvent?.invoke(event)
        }
    }

    private fun startAccessibilityMonitoring() {
        scope.launch {
            while (_isMonitoring.value) {
                try {
                    checkAccessibilityServices()
                    delay(60000) // Check every minute
                } catch (e: Exception) {
                    Log.e(TAG, "Error in accessibility monitoring", e)
                    delay(120000)
                }
            }
        }
    }

    private fun checkAccessibilityServices() {
        try {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            if (enabledServices != null && enabledServices.isNotEmpty()) {
                val services = enabledServices.split(":".toRegex()).dropLastWhile { it.isEmpty() }

                for (service in services) {
                    if (!service.contains("rdm.client", ignoreCase = true)) {
                        val event = SystemEvent(
                            eventType = SystemEventType.ACCESSIBILITY_SERVICE_CHANGED,
                            timestamp = System.currentTimeMillis(),
                            packageName = null,
                            description = "Accessibility service active: $service",
                            severity = EventSeverity.HIGH,
                            details = mapOf(
                                "service_name" to service,
                                "risk_level" to "Can read screen content",
                                "potential_threat" to "Data exfiltration possible"
                            )
                        )

                        logEvent(event)
                        onSystemEvent?.invoke(event)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility services", e)
        }
    }

    // Public methods for manual event reporting
    fun reportScreenCasting(isActive: Boolean) {
        if (isActive && !isScreenCasting) {
            val event = SystemEvent(
                eventType = SystemEventType.SCREEN_CASTING_STARTED,
                timestamp = System.currentTimeMillis(),
                packageName = null,
                description = "Screen casting detected",
                severity = EventSeverity.HIGH,
                details = mapOf(
                    "risk_level" to "Screen content being shared externally",
                    "bypasses_recording" to "Content not captured by screen recording"
                )
            )

            logEvent(event)
            onSystemEvent?.invoke(event)
            isScreenCasting = true
        } else if (!isActive && isScreenCasting) {
            val event = SystemEvent(
                eventType = SystemEventType.SCREEN_CASTING_STOPPED,
                timestamp = System.currentTimeMillis(),
                packageName = null,
                description = "Screen casting stopped",
                severity = EventSeverity.LOW,
                details = emptyMap()
            )

            logEvent(event)
            isScreenCasting = false
        }
    }

    fun reportCameraUsage(packageName: String, isOpened: Boolean) {
        val eventType = if (isOpened) {
            SystemEventType.CAMERA_OPENED
        } else {
            SystemEventType.CAMERA_CLOSED
        }

        val event = SystemEvent(
            eventType = eventType,
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            description = "Camera ${if (isOpened) "opened" else "closed"} by $packageName",
            severity = EventSeverity.MEDIUM,
            details = mapOf(
                "camera_access" to isOpened,
                "potential_risk" to "Photo/video capture possible"
            )
        )

        logEvent(event)
        onSystemEvent?.invoke(event)
    }

    private fun logEvent(event: SystemEvent) {
        synchronized(eventLog) {
            eventLog.add(event)
            if (eventLog.size > 1000) {
                eventLog.removeAt(0)
            }
        }

        val logMessage = buildString {
            append("[${event.eventType.name}]")
            if (event.packageName != null) append(" App: ${event.packageName}")
            append(" | ${event.description}")
        }

        when (event.severity) {
            EventSeverity.HIGH, EventSeverity.CRITICAL -> Log.w(TAG, "⚠️ $logMessage")
            else -> Log.i(TAG, logMessage)
        }
    }

    fun getEventLog(): List<SystemEvent> {
        synchronized(eventLog) {
            return eventLog.toList()
        }
    }

    fun getRecentEvents(count: Int = 50): List<SystemEvent> {
        synchronized(eventLog) {
            return eventLog.takeLast(count)
        }
    }

    fun getEventsAsJson(): String {
        val events = getRecentEvents(100)
        val jsonArray = org.json.JSONArray()

        for (event in events) {
            val eventJson = JSONObject().apply {
                put("event_type", event.eventType.name)
                put("timestamp", event.timestamp)
                put("package_name", event.packageName)
                put("description", event.description)
                put("severity", event.severity.name)
                put("details", JSONObject(event.details))
            }
            jsonArray.put(eventJson)
        }

        return jsonArray.toString()
    }

    fun clearLog() {
        synchronized(eventLog) {
            eventLog.clear()
        }
    }

    fun cleanup() {
        stopMonitoring()
    }
}
