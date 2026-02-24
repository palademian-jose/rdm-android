package com.rdm.client

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvDeviceInfoHint: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnTestCommand: Button
    private lateinit var etServerUrl: EditText
    private lateinit var switchAppendDeviceId: Switch

    private lateinit var webSocketClient: WebSocketClient
    private lateinit var deviceId: String
    private var isServiceRunning = false

    private val TAG = "MainActivity"

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
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnTestCommand = findViewById(R.id.btnTestCommand)
        etServerUrl = findViewById(R.id.etServerUrl)
        switchAppendDeviceId = findViewById(R.id.switchAppendDeviceId)

        // Set device ID hint
        tvDeviceInfoHint.text = "Device ID: $deviceId"

        // Initialize WebSocket client (will be replaced on connect)
        webSocketClient = WebSocketClient(this, "ws://placeholder:8443/ws/device", deviceId)

        // Setup listeners
        setupWebSocketListeners()
        setupSwitchListener()

        // Setup button listeners
        setupButtonListeners()

        // Start service
        startRdmService()

        // Update UI
        updateDeviceInfo()
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
                tvStatus.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                tvStatus.setTextColor(Color.WHITE)
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                Toast.makeText(this@MainActivity, "Connected to server", Toast.LENGTH_SHORT).show()
            }
        }

        webSocketClient.onDisconnected = {
            runOnUiThread {
                tvStatus.text = "✗ Disconnected"
                tvStatus.setBackgroundColor(getColor(android.R.color.holo_red_dark))
                tvStatus.setTextColor(Color.WHITE)
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
            }
        }

        webSocketClient.onError = { exception ->
            runOnUiThread {
                tvStatus.text = "⚠ Error: ${exception.message}"
                tvStatus.setBackgroundColor(getColor(android.R.color.holo_orange_dark))
                tvStatus.setTextColor(Color.WHITE)
                Toast.makeText(this@MainActivity, "Connection error: ${exception.message}", Toast.LENGTH_LONG).show()
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
                tvStatus.setBackgroundColor(getColor(android.R.color.holo_blue_dark))
                tvStatus.setTextColor(Color.WHITE)

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
    }
}
