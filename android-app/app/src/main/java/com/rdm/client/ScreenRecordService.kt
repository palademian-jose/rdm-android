package com.rdm.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordService : Service() {
    private val TAG = "ScreenRecordService"
    private val CHANNEL_ID = "ScreenRecordChannel"
    private val NOTIFICATION_ID = 1001

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var surface: Surface? = null

    private var isRecording = false
    private var currentOutputPath: String? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Screen Record Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<android.content.Intent>(EXTRA_DATA)
                val appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE)

                if (resultCode != -1 && data != null && appPackage != null) {
                    startRecording(resultCode, data, appPackage)
                } else {
                    Log.e(TAG, "Invalid start command")
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

    private fun startRecording(resultCode: Int, data: android.content.Intent, appPackage: String) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        serviceScope.launch {
            try {
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)

                val screenDensity = displayMetrics.densityDpi
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                // Create output file
                val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                currentOutputPath = File(outputDir, "recording_${appPackage}_$timestamp.mp4").absolutePath

                // Initialize MediaRecorder
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@ScreenRecordService)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(currentOutputPath)
                    setVideoEncodingBitRate(8 * 1000 * 1000) // 8 Mbps
                    setVideoFrameRate(30)
                    setVideoSize(screenWidth, screenHeight)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128 * 1000) // 128 kbps
                    setAudioSamplingRate(44100)

                    try {
                        prepare()
                        Log.d(TAG, "MediaRecorder prepared")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare MediaRecorder", e)
                        throw e
                    }
                }

                surface = mediaRecorder?.surface

                // Create virtual display
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenRecord",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )

                // Start recording
                mediaRecorder?.start()
                isRecording = true

                // Start foreground service
                val notification = createRecordingNotification(appPackage)
                startForeground(NOTIFICATION_ID, notification)

                Log.d(TAG, "Recording started: $currentOutputPath")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                cleanup()
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

                mediaRecorder?.apply {
                    stop()
                    reset()
                }

                virtualDisplay?.release()
                mediaProjection?.stop()

                Log.d(TAG, "Recording saved to: $currentOutputPath")

                // Send recording info via WebSocket
                // This would need to be integrated with the main app's WebSocket client

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

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        }

        try {
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing media recorder", e)
        }

        try {
            surface?.release()
            surface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing surface", e)
        }

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        }
    }

    companion object {
        const val ACTION_START = "com.rdm.client.START_RECORDING"
        const val ACTION_STOP = "com.rdm.client.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_APP_PACKAGE = "app_package"
    }
}
