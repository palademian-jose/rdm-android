package com.rdm.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Monitors USB/ADB connections and device state changes
 */
class DeviceConnectionMonitor(private val context: Context) {
    private val TAG = "DeviceConnectionMonitor"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // State
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _usbConnected = MutableStateFlow(false)
    val usbConnected: StateFlow<Boolean> = _usbConnected.asStateFlow()

    private var lastConnectionTime: Long = 0
    private var onConnectionEvent: ((ConnectionEvent) -> Unit)? = null

    // Event logging
    private val eventLog = mutableListOf<ConnectionEvent>()

    data class ConnectionEvent(
        val eventType: ConnectionEventType,
        val timestamp: Long,
        val connectionType: ConnectionType,
        val details: Map<String, Any>
    )

    enum class ConnectionEventType {
        USB_CONNECTED,
        USB_DISCONNECTED,
        ADB_DETECTED,
        UNAUTHORIZED_CONNECTION,
        FASTBOOT_DETECTED,
        CONNECTION_TIMEOUT,
        MULTIPLE_CONNECTIONS
    }

    enum class ConnectionType {
        USB_MTP,          // Media Transfer Protocol
        USB_PTP,          // Picture Transfer Protocol
        USB_ADB,          // Android Debug Bridge
        USB_CHARGING,     // Charging only
        FASTBOOT,         // Fastboot mode
        UNKNOWN
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    handleUsbConnected(ConnectionType.UNKNOWN)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    handleUsbDisconnected()
                }
                android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    handleUsbConnected(ConnectionType.UNKNOWN)
                }
                android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    handleUsbDisconnected()
                }
            }
        }
    }

    fun setEventCallback(callback: (ConnectionEvent) -> Unit) {
        onConnectionEvent = callback
    }

    fun startMonitoring() {
        if (_isMonitoring.value) {
            Log.w(TAG, "Device connection monitoring already active")
            return
        }

        _isMonitoring.value = true

        // Register USB receivers
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        context.registerReceiver(usbReceiver, filter)

        // Check initial USB state
        checkUsbState()

        // Start periodic ADB check
        startPeriodicChecks()

        Log.d(TAG, "Device connection monitoring started")
    }

    fun stopMonitoring() {
        if (!_isMonitoring.value) {
            return
        }

        _isMonitoring.value = false

        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        Log.d(TAG, "Device connection monitoring stopped")
    }

    private fun checkUsbState() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        if (deviceList.isNotEmpty()) {
            _usbConnected.value = true
            Log.d(TAG, "USB devices connected: ${deviceList.size}")

            for (device in deviceList.values) {
                handleUsbConnected(detectUsbType(device))
            }
        } else {
            _usbConnected.value = false
        }
    }

    private fun detectUsbType(device: android.hardware.usb.UsbDevice): ConnectionType {
        // Try to detect USB type based on device class and interfaces
        return when {
            device.deviceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE -> ConnectionType.USB_MTP
            device.deviceClass == android.hardware.usb.UsbConstants.USB_CLASS_STILL_IMAGE -> ConnectionType.USB_PTP
            device.interfaceCount > 0 -> ConnectionType.USB_ADB // Assume ADB if interfaces present
            else -> ConnectionType.USB_CHARGING
        }
    }

    private fun handleUsbConnected(connectionType: ConnectionType) {
        val timestamp = System.currentTimeMillis()
        lastConnectionTime = timestamp

        val event = ConnectionEvent(
            eventType = ConnectionEventType.USB_CONNECTED,
            timestamp = timestamp,
            connectionType = connectionType,
            details = mapOf(
                "connection_duration_ms" to 0L,
                "device_info" to getConnectedDeviceInfo()
            )
        )

        logEvent(event)
        onConnectionEvent?.invoke(event)

        _usbConnected.value = true
    }

    private fun handleUsbDisconnected() {
        val timestamp = System.currentTimeMillis()
        val connectionDuration = if (lastConnectionTime > 0) {
            timestamp - lastConnectionTime
        } else 0L

        val event = ConnectionEvent(
            eventType = ConnectionEventType.USB_DISCONNECTED,
            timestamp = timestamp,
            connectionType = ConnectionType.UNKNOWN,
            details = mapOf(
                "connection_duration_ms" to connectionDuration,
                "data_transferred_possible" to (connectionDuration > 5000) // Allow time for data transfer
            )
        )

        logEvent(event)
        onConnectionEvent?.invoke(event)

        _usbConnected.value = false
        lastConnectionTime = 0
    }

    private fun startPeriodicChecks() {
        scope.launch {
            while (_isMonitoring.value) {
                try {
                    checkADBStatus()
                    checkFastbootStatus()
                    delay(5000) // Check every 5 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic checks", e)
                    delay(10000)
                }
            }
        }
    }

    private fun checkADBStatus() {
        // Check if ADB is enabled
        val adbEnabled = android.provider.Settings.Global.getInt(
            context.contentResolver,
            android.provider.Settings.Global.ADB_ENABLED,
            0
        ) == 1

        if (adbEnabled && _usbConnected.value) {
            val timestamp = System.currentTimeMillis()

            val event = ConnectionEvent(
                eventType = ConnectionEventType.ADB_DETECTED,
                timestamp = timestamp,
                connectionType = ConnectionType.USB_ADB,
                details = mapOf(
                    "security_warning" to "ADB access allows complete device control",
                    "data_extraction_risk" to "HIGH",
                    "recommendation" to "Disable ADB when not needed"
                )
            )

            logEvent(event)
            onConnectionEvent?.invoke(event)

            Log.w(TAG, "⚠️ ADB connection detected - high security risk")
        }
    }

    private fun checkFastbootStatus() {
        // Check for fastboot indicators
        val fastbootFiles = listOf(
            "/sbin/fastboot",
            "/system/bin/fastboot",
            "/system/xbin/fastboot"
        )

        for (file in fastbootFiles) {
            if (File(file).exists()) {
                val timestamp = System.currentTimeMillis()

                val event = ConnectionEvent(
                    eventType = ConnectionEventType.FASTBOOT_DETECTED,
                    timestamp = timestamp,
                    connectionType = ConnectionType.FASTBOOT,
                    details = mapOf(
                        "fastboot_path" to file,
                        "security_risk" to "Fastboot allows system modification"
                    )
                )

                logEvent(event)
                onConnectionEvent?.invoke(event)

                Log.w(TAG, "⚠️ Fastboot detected - device modification possible")
                break
            }
        }
    }

    private fun getConnectedDeviceInfo(): String {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) return "No devices"

        return buildString {
            append("Devices: ${deviceList.size}")
            for (device in deviceList.values) {
                append(" | ${device.vendorId}:${device.productId}")
                append(" (${device.productName ?: "Unknown"})")
            }
        }
    }

    private fun logEvent(event: ConnectionEvent) {
        synchronized(eventLog) {
            eventLog.add(event)
            if (eventLog.size > 500) {
                eventLog.removeAt(0)
            }
        }

        val logMessage = buildString {
            append("[${event.eventType.name}]")
            append(" Type: ${event.connectionType.name}")
            if (event.details.isNotEmpty()) append(" | ${event.details}")
        }

        when (event.eventType) {
            ConnectionEventType.ADB_DETECTED,
            ConnectionEventType.UNAUTHORIZED_CONNECTION,
            ConnectionEventType.FASTBOOT_DETECTED -> Log.w(TAG, "⚠️ $logMessage")
            else -> Log.i(TAG, logMessage)
        }
    }

    fun getEventLog(): List<ConnectionEvent> {
        synchronized(eventLog) {
            return eventLog.toList()
        }
    }

    fun getRecentEvents(count: Int = 50): List<ConnectionEvent> {
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
                put("connection_type", event.connectionType.name)
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
