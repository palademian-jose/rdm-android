package com.rdm.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.provider.Settings
import android.net.Uri
import android.os.Build

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvDeviceInfoHint: TextView
    private lateinit var tvRecordingCount: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnTestCommand: Button
    private lateinit var etServerUrl: EditText
    private lateinit var etAppSearch: EditText
    private lateinit var switchAppendDeviceId: Switch

    private lateinit var webSocketClient: WebSocketClient
    private lateinit var deviceId: String
    private var isServiceRunning = false
    private lateinit var rootExecutor: RootExecutor
    private lateinit var foregroundAppMonitor: ForegroundAppMonitor
    private lateinit var appListCollector: AppListCollector
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private lateinit var rvAppList: RecyclerView
    private lateinit var appListAdapter: AppListAdapter

    private val TAG = "MainActivity"
    private val SCREEN_RECORD_REQUEST_CODE = 1001

    private var pendingRecordPackageName: String? = null
    private var allApps = listOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get device ID
        deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        // Initialize views
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvDeviceInfoHint = findViewById(R.id.tvDeviceInfoHint)
        tvRecordingCount = findViewById(R.id.tvRecordingCount)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnTestCommand = findViewById(R.id.btnTestCommand)
        etServerUrl = findViewById(R.id.etServerUrl)
        etAppSearch = findViewById(R.id.etAppSearch)
        switchAppendDeviceId = findViewById(R.id.switchAppendDeviceId)

        // Set device ID hint
        tvDeviceInfoHint.text = "Device ID: $deviceId"

        // Initialize WebSocket client (will be replaced on connect)
        webSocketClient = WebSocketClient(this, "ws://placeholder:8443/ws/device", deviceId)

        // Initialize root executor
        rootExecutor = RootExecutor()

        // Initialize app list collector
        appListCollector = AppListCollector(this)

        // Initialize media projection manager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Initialize foreground app monitor with recording callbacks
        foregroundAppMonitor = ForegroundAppMonitor(
            context = this,
            rootExecutor = rootExecutor,
            webSocketClient = webSocketClient,
            onRecordingTrigger = { packageName ->
                startScreenRecording(packageName)
            },
            onRecordingStop = {
                stopScreenRecording()
            }
        )

        // Initialize app list RecyclerView
        rvAppList = findViewById(R.id.rvAppList)
        appListAdapter = AppListAdapter { app, shouldRecord ->
            if (shouldRecord) {
                appListCollector.addRecordingApp(app.packageName)
            } else {
                appListCollector.removeRecordingApp(app.packageName)
            }
            updateRecordingCount()
        }
        rvAppList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = appListAdapter
        }

        // Setup listeners
        setupWebSocketListeners()
        setupSwitchListener()
        setupAppSearchListener()

        // Load app list
        loadAppList()
        updateRecordingCount()

        // Setup button listeners
        setupButtonListeners()

        // Start service
        startRdmService()

        // Update UI
        updateDeviceInfo()
    }

    private fun setupAppSearchListener() {
        etAppSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })

        etAppSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                etAppSearch.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun loadAppList() {
        lifecycleScope.launch {
            try {
                val result = appListCollector.getAllApps()
                if (result.isSuccess) {
                    allApps = result.getOrNull() ?: emptyList()
                    appListAdapter.submitList(allApps)
                    Log.d(TAG, "Loaded ${allApps.size} apps")
                } else {
                    Log.e(TAG, "Failed to load apps", result.exceptionOrNull())
                    Toast.makeText(this@MainActivity, "Failed to load app list", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading apps", e)
                Toast.makeText(this@MainActivity, "Error loading apps", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterApps(query: String) {
        lifecycleScope.launch {
            try {
                val result = if (query.isBlank()) {
                    appListCollector.getAllApps()
                } else {
                    appListCollector.searchApps(query)
                }

                if (result.isSuccess) {
                    val filtered = result.getOrNull() ?: emptyList()
                    appListAdapter.submitList(filtered)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error filtering apps", e)
            }
        }
    }

    private fun updateRecordingCount() {
        val recordingApps = appListCollector.getRecordingApps()
        tvRecordingCount.text = "${recordingApps.size} selected"
        appListAdapter.updateRecordingApps(recordingApps.toSet())

        // Update foreground app monitor with recording list
        foregroundAppMonitor.updateRecordingApps(recordingApps)
        Log.d(TAG, "Recording apps: $recordingApps")
    }

    private fun setupSwitchListener() {
        switchAppendDeviceId.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            updateServerUrlHint(isChecked)
        }
    }

    private fun updateServerUrlHint(appendDeviceId: Boolean) {
        val currentUrl = etServerUrl.text.toString()
        if (appendDeviceId) {
            etServerUrl.hint = "Server URL (device ID will be appended automatically)"
        } else {
            etServerUrl.hint = "Server URL (include device ID in URL)"
        }
        Log.d(TAG, "Append Device ID: $appendDeviceId")
    }

    private fun setupWebSocketListeners() {
        webSocketClient.onConnected = {
            runOnUiThread {
                tvStatus.text = "✓ Connected"
                tvStatus.setBackgroundColor(getColor(R.color.status_connected))
                tvStatus.setTextColor(getColor(R.color.bg_card))
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                Toast.makeText(this@MainActivity, "Connected to server", Toast.LENGTH_SHORT).show()

                // Start foreground app monitoring
                lifecycleScope.launch {
                    try {
                        foregroundAppMonitor.start()
                        Log.d(TAG, "Foreground app monitoring started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start foreground app monitor", e)
                    }
                }
            }
        }

        webSocketClient.onDisconnected = {
            runOnUiThread {
                tvStatus.text = "✗ Disconnected"
                tvStatus.setBackgroundColor(getColor(R.color.status_disconnected))
                tvStatus.setTextColor(getColor(R.color.bg_card))
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false

                // Stop foreground app monitoring
                foregroundAppMonitor.stop()
                Log.d(TAG, "Foreground app monitoring stopped")
            }
        }

        webSocketClient.onError = { exception ->
            runOnUiThread {
                tvStatus.text = "⚠ Error: ${exception.message}"
                tvStatus.setBackgroundColor(getColor(R.color.status_connecting))
                tvStatus.setTextColor(getColor(R.color.bg_card))
                Toast.makeText(this@MainActivity, "Connection error: ${exception.message}", Toast.LENGTH_LONG).show()

                // Stop foreground app monitoring on error
                foregroundAppMonitor.stop()
            }
        }

        webSocketClient.onMessage = { message ->
            runOnUiThread {
                // Handle incoming messages
                val type = message.get("type")?.asString
                when (type) {
                    "command" -> {
                        // Command received from server
                        val command = message.get("command")?.asString
                        val commandId = message.get("id")?.asString
                        Log.d(TAG, "Command received: $command (ID: $commandId)")
                        Toast.makeText(this@MainActivity, "Command: $command", Toast.LENGTH_SHORT).show()
                    }
                    "log_request" -> {
                        // Server requesting logs
                        Log.d(TAG, "Log request received")
                    }
                    "get_foreground_app" -> {
                        // Server requesting current foreground app
                        lifecycleScope.launch {
                            val currentApp = foregroundAppMonitor.getCurrentForegroundApp()
                            if (currentApp != null) {
                                val response = JSONObject().apply {
                                    put("type", "foreground_app_response")
                                    put("device_id", deviceId)
                                    put("data", JSONObject().apply {
                                        put("package_name", currentApp.packageName)
                                        put("activity_name", currentApp.activityName)
                                        put("timestamp", currentApp.timestamp)
                                    })
                                }
                                webSocketClient.send(response.toString())
                            }
                        }
                    }
                    else -> {
                        Log.d(TAG, "Unknown message type: $type")
                    }
                }
            }
        }
    }

    private fun setupButtonListeners() {
        btnConnect.setOnClickListener {
            connectToServer()
        }

        btnDisconnect.setOnClickListener {
            disconnectFromServer()
        }

        btnTestCommand.setOnClickListener {
            testCommand()
        }
    }

    private fun connectToServer() {
        lifecycleScope.launch {
            try {
                val serverUrl = etServerUrl.text.toString().trim()
                val appendDeviceId = switchAppendDeviceId.isChecked

                Log.d(TAG, "Server URL from input: '$serverUrl'")
                Log.d(TAG, "Append Device ID: $appendDeviceId")

                if (serverUrl.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter a server URL", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Build final URL based on switch state
                val finalUrl = if (appendDeviceId) {
                    if (!serverUrl.endsWith(deviceId)) {
                        "$serverUrl/$deviceId"
                    } else {
                        serverUrl
                    }
                } else {
                    serverUrl
                }

                // Disconnect existing client if any
                webSocketClient.disconnect()

                // Create new WebSocket client with the final URL
                webSocketClient = WebSocketClient(this@MainActivity, finalUrl, deviceId)
                setupWebSocketListeners()

                Log.d(TAG, "Connecting to: $finalUrl")
                tvStatus.text = "⏳ Connecting..."
                tvStatus.setBackgroundColor(getColor(R.color.status_connecting))
                tvStatus.setTextColor(getColor(R.color.bg_card))

                webSocketClient.connect()
                Toast.makeText(this@MainActivity, "Connecting to server...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disconnectFromServer() {
        lifecycleScope.launch {
            foregroundAppMonitor.stop()
            webSocketClient.disconnect()
            Toast.makeText(this@MainActivity, "Disconnected from server", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testCommand() {
        lifecycleScope.launch {
            try {
                val rootExecutor = RootExecutor()
                val result = rootExecutor.execute("ls -la /system/app", useSudo = true)

                runOnUiThread {
                    if (result.success) {
                        Toast.makeText(this@MainActivity, "✓ Command succeeded", Toast.LENGTH_SHORT).show()
                        tvDeviceInfo.text = result.output?.take(1000) ?: "No output"
                    } else {
                        Toast.makeText(this@MainActivity, "✗ Command failed", Toast.LENGTH_SHORT).show()
                        tvDeviceInfo.text = result.error ?: "Unknown error"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Test command error", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startRdmService() {
        try {
            val serviceIntent = Intent(this, RdmService::class.java)
            startForegroundService(serviceIntent)
            isServiceRunning = true
            Log.d(TAG, "RDM Service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    private fun updateDeviceInfo() {
        lifecycleScope.launch {
            try {
                val deviceInfo = DeviceInfoCollector.collect(this@MainActivity)

                runOnUiThread {
                    val infoText = """
                        ┌─────────────────────────────────┐
                        │  Device Information           │
                        ├─────────────────────────────────┤
                        │  Name: ${deviceInfo.name.padEnd(22)}│
                        │  Model: ${deviceInfo.model.padEnd(22)}│
                        │  Android: ${deviceInfo.android_version.padEnd(19)}│
                        │  API: ${deviceInfo.api_level.toString().padEnd(24)}│
                        │  CPU: ${deviceInfo.cpu_info.cores} cores - ${deviceInfo.cpu_info.model.padEnd(8)}│
                        │  RAM: ${(deviceInfo.memory_info.available / 1024 / 1024).toInt()} MB free${" ".repeat(13)}│
                        │  Storage: ${(deviceInfo.storage_info.available / 1024 / 1024 / 1024).toInt()} GB free${" ".repeat(11)}│
                        │  Battery: ${(deviceInfo.battery_info.percentage).toInt()}%${" ".repeat(25)}│
                        │  Apps: ${deviceInfo.installed_apps.size} installed${" ".repeat(14)}│
                        └─────────────────────────────────┘
                        
                        Device ID: $deviceId
                    """.trimIndent()

                    tvDeviceInfo.text = infoText
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting device info", e)
                runOnUiThread {
                    tvDeviceInfo.text = "Failed to load device info: ${e.message}"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient.disconnect()
        foregroundAppMonitor.stop()
        stopScreenRecording()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            SCREEN_RECORD_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val packageName = pendingRecordPackageName
                    if (packageName != null) {
                        startScreenRecordingService(resultCode, data, packageName)
                        pendingRecordPackageName = null
                    }
                } else {
                    Log.w(TAG, "Screen recording permission denied")
                    Toast.makeText(this, "Screen recording permission required", Toast.LENGTH_LONG).show()
                    foregroundAppMonitor.updateRecordingApps(emptyList())
                }
            }
        }
    }

    private fun startScreenRecording(packageName: String) {
        // Request screen recording permission
        pendingRecordPackageName = packageName
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, SCREEN_RECORD_REQUEST_CODE)
    }

    private fun startScreenRecordingService(resultCode: Int, data: Intent, packageName: String) {
        try {
            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA, data)
                putExtra(ScreenRecordService.EXTRA_APP_PACKAGE, packageName)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                @Suppress("DEPRECATION")
                startService(serviceIntent)
            }

            Log.d(TAG, "Screen recording service started for: $packageName")
            Toast.makeText(this, "Recording: $packageName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen recording service", e)
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScreenRecording() {
        try {
            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            startService(serviceIntent)
            Log.d(TAG, "Screen recording service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop screen recording service", e)
        }
    }
}
