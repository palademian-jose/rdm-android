package com.rdm.client

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*

class AppUsageTracker(
    private val context: Context,
    private val onAppEvent: (String, String, Long) -> Unit // (packageName, eventType, timestamp)
) {
    private val TAG = "AppUsageTracker"
    private val usageStatsManager: UsageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var currentForegroundApp: String? = null
    private var isTracking = false

    fun startTracking() {
        if (isTracking) {
            Log.w(TAG, "App usage tracking already started")
            return
        }

        Log.i(TAG, "Starting app usage tracking")
        isTracking = true

        // Start periodic check for app changes
        scope.launch {
            while (isTracking) {
                try {
                    checkForegroundApp()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking foreground app", e)
                }
                delay(5000) // Check every 5 seconds
            }
        }
    }

    fun stopTracking() {
        Log.i(TAG, "Stopping app usage tracking")
        isTracking = false
        scope.cancel()
    }

    private fun checkForegroundApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }

        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60 * 60 * 1000 // Check last hour

        val events = usageStatsManager.queryEvents(startTime, endTime)

        val eventList = mutableListOf<UsageEvents.Event>()
        events.forEachTo(object : UsageEvents.Event() {
            override fun onEvent(event: UsageEvents.Event) {
                eventList.add(event)
            }
        })

        // Find the most recent foreground event
        var latestForegroundApp: String? = null
        var latestTimestamp = 0L

        eventList.reversed().forEach { event ->
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.timeStamp > latestTimestamp) {
                    latestTimestamp = event.timeStamp
                    latestForegroundApp = event.packageName
                }
            }
        }

        // Check if foreground app changed
        if (latestForegroundApp != null && latestForegroundApp != currentForegroundApp) {
            val oldApp = currentForegroundApp
            currentForegroundApp = latestForegroundApp

            if (oldApp != null) {
                // Previous app closed
                Log.i(TAG, "App closed: $oldApp at $endTime")
                onAppEvent(oldApp, "app_closed", endTime)
            }

            // New app opened
            Log.i(TAG, "App opened: $currentForegroundApp at $endTime")
            onAppEvent(currentForegroundApp!!, "app_opened", endTime)
        }
    }

    fun getForegroundPackageName(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null
        }

        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60 * 1000 // Check last minute

        val events = usageStatsManager.queryEvents(startTime, endTime)
        val eventList = mutableListOf<UsageEvents.Event>()

        events.forEachTo(object : UsageEvents.Event() {
            override fun onEvent(event: UsageEvents.Event) {
                eventList.add(event)
            }
        })

        // Find most recent foreground event
        var latestApp: String? = null
        var latestTimestamp = 0L

        eventList.reversed().forEach { event ->
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.timeStamp > latestTimestamp) {
                latestTimestamp = event.timeStamp
                latestApp = event.packageName
            }
        }

        return latestApp
    }

    fun getAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appInfo.loadLabel(pm).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
