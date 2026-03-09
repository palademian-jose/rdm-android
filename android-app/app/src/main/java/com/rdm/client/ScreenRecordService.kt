package com.rdm.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordService : Service() {
    private val TAG = "ScreenRecordService"
    private val CHANNEL_ID = "ScreenRecordChannel"
    private val NOTIFICATION_ID = 1001

    private var isRecording = false
    private var currentOutputPath: String? = null
    private var recordJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var rootExecutor: RootExecutor

    companion object {
        const val ACTION_START = "com.rdm.client.START_RECORDING"
        const val ACTION_STOP = "com.rdm.client.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_APP_PACKAGE = "app_package"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        rootExecutor = RootExecutor()
        Log.d(TAG, "Screen Record Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE)
                if (appPackage != null) {
                    startRecording(appPackage)
                } else {
                    Log.e(TAG, "Missing app package")
                }
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        serviceScope.cancel()
        Log.d(TAG, "Screen Record Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen recording notification"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createRecordingNotification(appName: String?): Notification {
        val title = "Recording: ${appName ?: "Screen"}"
        val message = "Tap to stop recording"

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startRecording(appPackage: String) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        serviceScope.launch {
            try {
                // Get screen dimensions
                val screenWidth = getScreenWidth()
                val screenHeight = getScreenHeight()

                // Create output file
                val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outputFile = File(outputDir, "recording_${appPackage}_$timestamp.mp4")
                currentOutputPath = outputFile.absolutePath

                Log.d(TAG, "Starting recording to: $currentOutputPath")

                // Build screenrecord command
                val screenrecordCmd = buildString {
                    append("screenrecord")
                    append(" --size ${screenWidth}x$screenHeight")
                    append(" --bit-rate 8000000") // 8 Mbps
                    append(" --time-limit 1800") // 30 minutes max
                    append(" \"$currentOutputPath\"")
                }

                Log.d(TAG, "Executing: $screenrecordCmd")

                // Start recording via root in background
                isRecording = true

                // Start foreground service
                val notification = createRecordingNotification(appPackage)
                startForeground(NOTIFICATION_ID, notification)

                // Execute screenrecord command (blocking, run in separate coroutine)
                recordJob = launch(Dispatchers.IO) {
                    val result = rootExecutor.execute(screenrecordCmd, useSudo = true)
                    if (result.success) {
                        Log.d(TAG, "Recording completed successfully")
                        Log.d(TAG, "Output: ${result.output}")
                    } else {
                        Log.e(TAG, "Recording failed: ${result.error}")
                    }
                    // After screenrecord completes, stop service
                    stopRecording()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                isRecording = false
                stopSelf()
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Not recording")
            return
        }

        serviceScope.launch {
            try {
                Log.d(TAG, "Stopping recording...")

                // Send SIGINT to screenrecord process (graceful stop)
                val stopResult = rootExecutor.execute(
                    "pkill -SIGINT -f 'screenrecord'",
                    useSudo = true
                )

                if (stopResult.success) {
                    Log.d(TAG, "Recording stopped gracefully")
                } else {
                    Log.e(TAG, "Failed to stop recording: ${stopResult.error}")
                    // Force kill as fallback
                    rootExecutor.execute("pkill -9 -f 'screenrecord'", useSudo = true)
                }

                // Wait for recording to complete
                recordJob?.join()

                Log.d(TAG, "Recording saved to: $currentOutputPath")

                // Check if file exists and get size
                currentOutputPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val sizeKB = file.length() / 1024
                        Log.d(TAG, "Recording file size: ${sizeKB}KB")
                    } else {
                        Log.w(TAG, "Recording file not found: $path")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording", e)
            } finally {
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cleanup() {
        isRecording = false
        recordJob?.cancel()
        recordJob = null
        currentOutputPath = null
        Log.d(TAG, "Cleanup complete")
    }

    private suspend fun getScreenWidth(): Int = withContext(Dispatchers.IO) {
        return@withContext try {
            val result = rootExecutor.execute("wm size", useSudo = false)
            if (result.success && result.output != null) {
                // Parse output: Physical size: 1080x2280
                val regex = """(\d+)x(\d+)""".toRegex()
                val match = regex.find(result.output)
                match?.groupValues?.get(1)?.toInt() ?: 1080
            } else {
                1080 // fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get screen width", e)
            1080
        }
    }

    private suspend fun getScreenHeight(): Int = withContext(Dispatchers.IO) {
        return@withContext try {
            val result = rootExecutor.execute("wm size", useSudo = false)
            if (result.success && result.output != null) {
                val regex = """(\d+)x(\d+)""".toRegex()
                val match = regex.find(result.output)
                match?.groupValues?.get(2)?.toInt() ?: 2280
            } else {
                2280 // fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get screen height", e)
            2280
        }
    }
}
