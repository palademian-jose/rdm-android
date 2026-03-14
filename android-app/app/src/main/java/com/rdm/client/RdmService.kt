package com.rdm.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RdmService : Service() {
    private val TAG = "RdmService"
    private val CHANNEL_ID = "RdmServiceChannel"
    private val NOTIFICATION_ID = 1

    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var foregroundAppMonitor: ForegroundAppMonitor
    private lateinit var rootExecutor: RootExecutor
    private lateinit var appDetector: AppDetector
    private lateinit var anomalyDetector: AnomalyDetector
    private lateinit var screenshotDetector: ScreenshotDetector
    private lateinit var unifiedMonitoringManager: UnifiedMonitoringManager
    private lateinit var deviceId: String
    private var isServiceRunning = false

    // Audio recording
    private var audioRecorder: MediaRecorder? = null
    private var currentAudioPath: String? = null
    private var isRecordingAudio = false

    // Periodic anomaly check job
    private var anomalyCheckJob: Job? = null

    companion object {
        const val ACTION_START = "com.rdm.client.START"
        const val ACTION_STOP = "com.rdm.client.STOP"
        const val EXTRA_SERVER_URL = "server_url"

        // Broadcast actions for connection status
        const val ACTION_CONNECTION_STATUS_CHANGED = "com.rdm.client.CONNECTION_STATUS_CHANGED"
        const val EXTRA_IS_CONNECTED = "is_connected"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "RDM Service created")

        // Get device ID
        deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        // Initialize components
        rootExecutor = RootExecutor()
        appDetector = AppDetector(this)
        webSocketClient = WebSocketClient(this, "ws://placeholder:8443/ws/device", deviceId)
        anomalyDetector = AnomalyDetector(this)

        // Initialize screenshot detector
        screenshotDetector = ScreenshotDetector(this) { packageName, metadata ->
            // Callback when screenshot is detected and processed
            Log.d(TAG, "Screenshot detected in $packageName: ${metadata.duplicatedPath}")
            anomalyDetector.detectScreenshot(packageName)
        }

        // Initialize unified monitoring manager
        unifiedMonitoringManager = UnifiedMonitoringManager(this)
        unifiedMonitoringManager.initialize(webSocketClient, deviceId)

        foregroundAppMonitor = ForegroundAppMonitor(
            context = this,
            rootExecutor = rootExecutor,
            webSocketClient = webSocketClient,
            onRecordingTrigger = { packageName ->
                // Actually start the recording service!
                try {
                    val intent = Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_START
                        putExtra(ScreenRecordService.EXTRA_APP_PACKAGE, packageName)
                    }
                    startForegroundService(intent)
                    Log.d(TAG, "Started recording service for: $packageName")

                    // Also start audio recording in this service
                    startAudioRecording(packageName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start recording service for $packageName", e)
                }
            },
            onRecordingStop = {
                // Stop the recording service!
                try {
                    val intent = Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    }
                    startService(intent)
                    Log.d(TAG, "Stopped recording service")

                    // Also stop audio recording
                    stopAudioRecording()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop recording service", e)
                }
            },
            onAppChanged = { packageName ->
                // Optional: Handle app changes
                Log.d(TAG, "Foreground app changed to: $packageName")
            },
            anomalyDetector = anomalyDetector
        )

        // Set up recording event logging to server
        foregroundAppMonitor.getSmartRecordingManager().setRecordingCallbacks(
            onStart = { packageName ->
                Log.d(TAG, "Recording started callback for: $packageName")
                // Trigger the actual recording service
                try {
                    val intent = Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_START
                        putExtra(ScreenRecordService.EXTRA_APP_PACKAGE, packageName)
                    }
                    startForegroundService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start recording for $packageName", e)
                }
            },
            onStop = { packageName ->
                Log.d(TAG, "Recording stopped callback for: $packageName")
                // Stop the recording service
                try {
                    val intent = Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    }
                    startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop recording", e)
                }
            },
            onLog = { event ->
                // Send recording events to server
                try {
                    val message = org.json.JSONObject().apply {
                        put("type", "recording_event")
                        put("device_id", deviceId)
                        put("data", org.json.JSONObject().apply {
                            put("event_type", event.eventType.name)
                            put("package_name", event.packageName)
                            put("reason", event.reason)
                            put("timestamp", event.timestamp)
                            put("details", org.json.JSONObject(event.details))
                        })
                    }
                    webSocketClient.send(message.toString())
                    Log.d(TAG, "Recording event sent: ${event.eventType.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send recording event", e)
                }
            }
        )

        // Set up WebSocket callbacks
        webSocketClient.onConnected = {
            Log.d(TAG, "WebSocket connected in service")
            updateNotification("Connected to server")

            // Broadcast connection status to MainActivity (global broadcast for inter-process)
            val intent = Intent(ACTION_CONNECTION_STATUS_CHANGED).apply {
                putExtra(EXTRA_IS_CONNECTED, true)
                setPackage(packageName) // Restrict to our app
            }
            sendBroadcast(intent)
        }

        webSocketClient.onDisconnected = {
            Log.d(TAG, "WebSocket disconnected in service")
            updateNotification("Disconnected - Reconnecting...")

            // Broadcast connection status to MainActivity (global broadcast for inter-process)
            val intent = Intent(ACTION_CONNECTION_STATUS_CHANGED).apply {
                putExtra(EXTRA_IS_CONNECTED, false)
                setPackage(packageName) // Restrict to our app
            }
            sendBroadcast(intent)
        }

        webSocketClient.onError = { exception ->
            Log.e(TAG, "WebSocket error in service: ${exception.message}")
            updateNotification("Connection Error")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "RDM Service started")

        when (intent?.action) {
            ACTION_START -> {
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
                if (serverUrl != null) {
                    connectToServer(serverUrl)
                } else {
                    Log.w(TAG, "No server URL provided")
                }
            }
            ACTION_STOP -> {
                stopService()
                return START_NOT_STICKY
            }
        }

        // Start foreground service with notification
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start app monitoring
        if (!foregroundAppMonitor.isMonitoring()) {
            serviceScope.launch {
                foregroundAppMonitor.start()
            }
        }

        // Start screenshot detector (delayed to allow WebSocket to stabilize)
        serviceScope.launch {
            delay(5000) // Wait 5 seconds
            screenshotDetector.start()
        }

        // Start unified monitoring manager (delayed to allow WebSocket to stabilize)
        serviceScope.launch {
            delay(5000) // Wait 5 seconds
            unifiedMonitoringManager.start()
        }

        // Start periodic anomaly checks (delayed internally)
        startPeriodicAnomalyChecks()

        // Monitor for anomaly events (delayed internally)
        monitorAnomalyEvents()

        isServiceRunning = true

        // Make service sticky - it will restart if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startPeriodicAnomalyChecks() {
        anomalyCheckJob = serviceScope.launch {
            // Wait 10 seconds before first check to allow WebSocket to stabilize
            delay(10000)

            while (isServiceRunning) {
                try {
                    // Check for root evasion attempts every 5 minutes
                    anomalyDetector.checkRootEvasionAttempts()

                    // Check for system modifications every 5 minutes
                    anomalyDetector.checkSystemModifications()

                    delay(300000) // 5 minutes
                } catch (e: Exception) {
                    Log.e(TAG, "Error in anomaly checks", e)
                    delay(60000) // Wait 1 minute before retry
                }
            }
        }
    }

    private fun monitorAnomalyEvents() {
        serviceScope.launch {
            // Wait 10 seconds before monitoring to allow WebSocket to stabilize
            delay(10000)

            anomalyDetector.getAnomalyFlow().collect { event ->
                try {
                    Log.w(TAG, "Anomaly detected: ${event.type} - ${event.message}")

                    // Send anomaly to server
                    val message = org.json.JSONObject().apply {
                        put("type", "anomaly")
                        put("device_id", deviceId)
                        put("data", org.json.JSONObject().apply {
                            put("anomaly_type", event.type.name)
                            put("source", event.source)
                            put("message", event.message)
                            put("severity", event.severity.name)
                            put("timestamp", event.timestamp)
                        })
                    }
                    webSocketClient.send(message.toString())

                    // Handle high severity anomalies
                    if (event.severity == com.rdm.client.AnomalySeverity.HIGH ||
                        event.severity == com.rdm.client.AnomalySeverity.CRITICAL) {
                        Log.e(TAG, "HIGH SEVERITY ANOMALY: ${event.type.name}")
                        updateNotification("Security Alert: ${event.type.name}")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle anomaly event", e)
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - service will continue running")
        // Don't stop the service when app is removed from recent tasks
        // START_STICKY will restart it
    }

    private fun connectToServer(serverUrl: String) {
        serviceScope.launch {
            try {
                // Check if already connected to the same URL
                if (webSocketClient.isConnected()) {
                    Log.d(TAG, "Already connected to server, skipping duplicate connection")
                    return@launch
                }

                Log.d(TAG, "Connecting to server: $serverUrl")

                // Disconnect existing connection if any
                if (webSocketClient != null) {
                    webSocketClient.disconnect()
                }

                // Reinitialize WebSocket client with server URL
                webSocketClient = WebSocketClient(this@RdmService, serverUrl, deviceId)

                // Set up callbacks
                webSocketClient.onConnected = {
                    Log.d(TAG, "Service WebSocket connected")
                    updateNotification("RDM Client - Connected")

                    // Broadcast connection status to MainActivity (global broadcast for inter-process)
                    val intent = Intent(ACTION_CONNECTION_STATUS_CHANGED).apply {
                        putExtra(EXTRA_IS_CONNECTED, true)
                        setPackage(packageName) // Restrict to our app
                    }
                    sendBroadcast(intent)
                }

                webSocketClient.onDisconnected = {
                    Log.d(TAG, "Service WebSocket disconnected - will reconnect")
                    updateNotification("RDM Client - Reconnecting...")

                    // Broadcast connection status to MainActivity (global broadcast for inter-process)
                    val intent = Intent(ACTION_CONNECTION_STATUS_CHANGED).apply {
                        putExtra(EXTRA_IS_CONNECTED, false)
                        setPackage(packageName) // Restrict to our app
                    }
                    sendBroadcast(intent)
                }

                webSocketClient.onError = { exception ->
                    Log.e(TAG, "Service WebSocket error: ${exception.message}")
                    updateNotification("RDM Client - Connection Error")
                }

                // Connect
                webSocketClient.connect()

                // Start monitoring
                if (!foregroundAppMonitor.isMonitoring()) {
                    foregroundAppMonitor.start()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to server", e)
                updateNotification("RDM Client - Failed to connect")
            }
        }
    }

    private fun stopService() {
        Log.d(TAG, "Stopping service")
        isServiceRunning = false

        screenshotDetector.stop()
        unifiedMonitoringManager.stop()
        foregroundAppMonitor.stop()
        webSocketClient.disconnect()
        serviceScope.cancel()

        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RDM Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Remote Device Manager persistent service"
            setShowBadge(false)
            setSound(null, null)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RDM Client")
            .setContentText("Monitoring device in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Make notification non-dismissible
            .setAutoCancel(false)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RDM Client")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startAudioRecording(packageName: String) {
        if (isRecordingAudio) {
            Log.w(TAG, "Audio recording already in progress")
            return
        }

        serviceScope.launch {
            try {
                // Create audio file
                val outputDir = getExternalFilesDir(null)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val audioFile = File(outputDir, "audio_${packageName}_$timestamp.m4a")
                currentAudioPath = audioFile.absolutePath

                // Initialize MediaRecorder
                audioRecorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(currentAudioPath)
                    prepare()
                    start()
                }

                isRecordingAudio = true
                Log.d(TAG, "Audio recording started: $currentAudioPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio recording", e)
                currentAudioPath = null
                isRecordingAudio = false
            }
        }
    }

    private fun stopAudioRecording() {
        if (!isRecordingAudio) {
            return
        }

        try {
            audioRecorder?.apply {
                try {
                    stop()
                    Log.d(TAG, "Audio recording stopped: $currentAudioPath")

                    // Get file size
                    currentAudioPath?.let { path ->
                        val file = File(path)
                        if (file.exists()) {
                            val sizeKB = file.length() / 1024
                            Log.d(TAG, "Audio file size: ${sizeKB}KB")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping audio recorder", e)
                }
                release()
            }
            audioRecorder = null
            currentAudioPath = null
            isRecordingAudio = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio recording", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RDM Service destroyed - but will restart due to START_STICKY")
        isServiceRunning = false

        // Stop audio recording if active
        stopAudioRecording()

        // Stop recording service if running
        try {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording service", e)
        }

        // Clean up resources
        anomalyCheckJob?.cancel()
        anomalyDetector.cleanup()
        screenshotDetector.stop()
        unifiedMonitoringManager.cleanup()
        serviceScope.cancel()
        foregroundAppMonitor.stop()
        webSocketClient.disconnect()
        rootExecutor.cleanup()
    }
}