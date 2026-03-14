package com.rdm.client

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Monitors all notifications to detect sensitive information leakage
 * and communication patterns
 */
class NotificationMonitor : NotificationListenerService() {
    private val TAG = "NotificationMonitor"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // State
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var onNotificationEvent: ((NotificationEvent) -> Unit)? = null

    // Event logging
    private val eventLog = mutableListOf<NotificationEvent>()

    data class NotificationEvent(
        val eventType: NotificationEventType,
        val timestamp: Long,
        val packageName: String,
        val notificationTitle: String?,
        val notificationText: String?,
        val category: String?,
        val isSensitive: Boolean,
        val details: Map<String, Any>
    )

    enum class NotificationEventType {
        NOTIFICATION_POSTED,
        NOTIFICATION_REMOVED,
        SENSITIVE_CONTENT_DETECTED,
        COMMUNICATION_DETECTED,
        BANKING_NOTIFICATION,
        AUTHENTICATION_NOTIFICATION,
        HIDDEN_NOTIFICATION,
        GROUP_NOTIFICATION
    }

    fun setEventCallback(callback: (NotificationEvent) -> Unit) {
        onNotificationEvent = callback
    }

    override fun onCreate() {
        super.onCreate()
        _isActive.value = true
        Log.d(TAG, "Notification monitor created")
    }

    override fun onDestroy() {
        _isActive.value = false
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "Notification monitor destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isActive.value = true
        Log.d(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras

            val packageName = sbn.packageName
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val category = notification.category

            val fullContent = listOfNotNull(title, text, bigText).joinToString(" ")

            analyzeNotification(packageName, title, text ?: bigText, category, fullContent, sbn.key)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)

        try {
            val packageName = sbn.packageName
            val timestamp = System.currentTimeMillis()

            val event = NotificationEvent(
                eventType = NotificationEventType.NOTIFICATION_REMOVED,
                timestamp = timestamp,
                packageName = packageName,
                notificationTitle = null,
                notificationText = null,
                category = null,
                isSensitive = false,
                details = mapOf(
                    "notification_key" to sbn.key,
                    "user_removed" to true
                )
            )

            logEvent(event)
            onNotificationEvent?.invoke(event)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification removal", e)
        }
    }

    private fun analyzeNotification(
        packageName: String,
        title: String?,
        text: String?,
        category: String?,
        fullContent: String,
        notificationKey: String
    ) {
        val timestamp = System.currentTimeMillis()
        val isSensitive = detectSensitiveContent(fullContent)
        val communicationType = detectCommunicationType(packageName, title, text)
        val isGroupNotification = detectGroupNotification(fullContent)

        val eventType = when {
            isSensitive && communicationType != "none" -> NotificationEventType.SENSITIVE_CONTENT_DETECTED
            isSensitive -> NotificationEventType.SENSITIVE_CONTENT_DETECTED
            communicationType == "banking" -> NotificationEventType.BANKING_NOTIFICATION
            communicationType == "authentication" -> NotificationEventType.AUTHENTICATION_NOTIFICATION
            communicationType != "none" -> NotificationEventType.COMMUNICATION_DETECTED
            isGroupNotification -> NotificationEventType.GROUP_NOTIFICATION
            else -> NotificationEventType.NOTIFICATION_POSTED
        }

        val event = NotificationEvent(
            eventType = eventType,
            timestamp = timestamp,
            packageName = packageName,
            notificationTitle = title,
            notificationText = if (isSensitive || (text?.length ?: 0) > 200) {
                text?.take(100) + "..."
            } else text,
            category = category,
            isSensitive = isSensitive,
            details = mapOf(
                "communication_type" to communicationType,
                "is_group_notification" to isGroupNotification,
                "notification_key" to notificationKey,
                "category" to (category ?: "none"),
                "content_length" to fullContent.length
            )
        )

        logEvent(event)
        onNotificationEvent?.invoke(event)

        if (isSensitive) {
            Log.w(TAG, "🔒 Sensitive notification from $packageName: ${title?.take(50)}")
        }
    }

    private fun detectSensitiveContent(content: String): Boolean {
        val sensitivePatterns = listOf(
            Regex("(?i)\\b\\d{16}\\b"), // Credit card
            Regex("(?i)\\b\\d{4}-\\d{4}-\\d{4}-\\d{4}\\b"), // Credit card with dashes
            Regex("(?i)\\b\\d{3}-\\d{2}-\\d{4}\\b"), // SSN
            Regex("(?i)password"), // Password
            Regex("(?i)otp|one time|verification code"), // OTP
            Regex("(?i)pin|security code"), // PIN
            Regex("(?i)account balance|transaction|payment|transfer"), // Banking
            Regex("(?i)login|sign in|authentication"), // Authentication
            Regex("(?i)api[_-]?key|secret|token"), // API keys
            Regex("(?i)confidential|private|secret|sensitive") // Confidential info
        )

        return sensitivePatterns.any { it.find(content) != null }
    }

    private fun detectCommunicationType(packageName: String, title: String?, text: String?): String {
        val content = listOfNotNull(title, text).joinToString(" ").lowercase()

        return when {
            // Banking apps
            packageName.matches(Regex(".*bank.*|.*finance.*|.*payment.*")) ||
            content.contains("transaction") ||
            content.contains("account balance") ||
            content.contains("payment") ||
            content.contains("transfer") -> "banking"

            // Authentication
            content.contains("otp") ||
            content.contains("verification code") ||
            content.contains("login") ||
            content.contains("sign in") ||
            content.contains("authentication") -> "authentication"

            // Communication apps
            packageName in setOf(
                "com.whatsapp",
                "com.telegram.messenger",
                "jp.naver.line.android",
                "com.viber.voip",
                "com.discord",
                "com.facebook.orca",
                "com.snapchat.android",
                "com.instagram.android",
                "com.google.android.gm"
            ) -> "messaging"

            // Email
            packageName.contains("mail") ||
            packageName.contains("email") ||
            content.contains("@") && content.contains(".") -> "email"

            else -> "none"
        }
    }

    private fun detectGroupNotification(content: String): Boolean {
        val groupIndicators = listOf(
            "messages",
            "notifications",
            "unread",
            "new items",
            "and",
            "plus",
            "others"
        )

        return groupIndicators.any { content.lowercase().contains(it) }
    }

    private fun logEvent(event: NotificationEvent) {
        synchronized(eventLog) {
            eventLog.add(event)
            if (eventLog.size > 1000) {
                eventLog.removeAt(0)
            }
        }

        val logMessage = buildString {
            append("[${event.eventType.name}]")
            append(" App: ${event.packageName}")
            if (event.notificationTitle != null) append(" | ${event.notificationTitle.take(50)}")
            if (event.isSensitive) append(" [SENSITIVE]")
        }

        when (event.eventType) {
            NotificationEventType.SENSITIVE_CONTENT_DETECTED,
            NotificationEventType.AUTHENTICATION_NOTIFICATION -> Log.w(TAG, "🔒 $logMessage")
            else -> Log.i(TAG, logMessage)
        }
    }

    fun getEventLog(): List<NotificationEvent> {
        synchronized(eventLog) {
            return eventLog.toList()
        }
    }

    fun getRecentEvents(count: Int = 50): List<NotificationEvent> {
        synchronized(eventLog) {
            return eventLog.takeLast(count)
        }
    }

    fun getEventsForApp(packageName: String): List<NotificationEvent> {
        synchronized(eventLog) {
            return eventLog.filter { it.packageName == packageName }
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
                put("title", event.notificationTitle)
                put("text", event.notificationText)
                put("category", event.category)
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

    companion object {
        // Static instance for callbacks
        private var instance: NotificationMonitor? = null

        fun getInstance(): NotificationMonitor? = instance

        fun setInstance(monitor: NotificationMonitor) {
            instance = monitor
        }
    }
}
