package com.rdm.client

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnTestCommand: Button

    private lateinit var webSocketClient: WebSocketClient
    private lateinit var deviceId: String
    private var isServiceRunning = false

    private val TAG = "MainActivity"
    private val SERVER_URL = "wss://your-server.com:8443/ws/device" // Replace with your server URL

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
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnTestCommand = findViewById(R.id.btnTestCommand)

        // Initialize WebSocket client
        webSocketClient = WebSocketClient(this, SERVER_URL, deviceId)

        // Setup listeners
        setupWebSocketListeners()

        // Setup button listeners
        setupButtonListeners()

        // Start service
        startRdmService()

        // Update UI
        updateDeviceInfo()
    }

    private fun setupWebSocketListeners() {
        webSocketClient.onConnected = {
            runOnUiThread {
                tvStatus.text = "Status: Connected"
                tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
            }
        }

        webSocketClient.onDisconnected = {
            runOnUiThread {
                tvStatus.text = "Status: Disconnected"
                tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
            }
        }

        webSocketClient.onError = { exception ->
            runOnUiThread {
                tvStatus.text = "Status: Error - ${exception.message}"
                tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
        }

        webSocketClient.onMessage = { message ->
            runOnUiThread {
                // Handle incoming messages
                when (val type = message.get("type")?.asString) {
                    "command" -> {
                        // Command received from server
                        val command = message.get("command")?.asString
                        val commandId = message.get("id")?.asString
                        Log.d(TAG, "Command received: $command (ID: $commandId)")
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
                webSocketClient.connect()
                Toast.makeText(this@MainActivity, "Connecting...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disconnectFromServer() {
        lifecycleScope.launch {
            webSocketClient.disconnect()
            Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testCommand() {
        lifecycleScope.launch {
            try {
                val rootExecutor = RootExecutor()
                val result = rootExecutor.execute("ls -la /system/app", useSudo = true)

                runOnUiThread {
                    if (result.success) {
                        Toast.makeText(this@MainActivity, "Command succeeded", Toast.LENGTH_SHORT).show()
                        tvDeviceInfo.text = result.output?.take(1000) ?: "No output"
                    } else {
                        Toast.makeText(this@MainActivity, "Command failed", Toast.LENGTH_SHORT).show()
                        tvDeviceInfo.text = result.error ?: "Unknown error"
                    }
                }
            } check catch(e: Exception) {
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
                        Device: ${deviceInfo.name}
                        Model: ${deviceInfo.model}
                        Android: ${deviceInfo.android_version} (API ${deviceInfo.api_level})
                        CPU: ${deviceInfo.cpu_info.cores} cores - ${deviceInfo.cpu_info.model}
                        RAM: ${deviceInfo.memory_info.available / 1024 / 1024} MB free
                        Storage: ${deviceInfo.storage_info.available / 1024 / 1024 / 1024} GB free
                        Battery: ${deviceInfo.battery_info.percentage}%
                        Apps: ${deviceInfo.installed_apps.size} installed
                        Device ID: $deviceId
                    """.trimIndent()

                    tvDeviceInfo.text = infoText
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting device info", e)
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
