package com.rdm.client

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvDeviceInfoHint: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var btnTestCommand: MaterialButton
    private lateinit var etServerUrl: TextInputEditText
    private lateinit var switchAppendDeviceId: MaterialSwitch
    private lateinit var statusPill: LinearLayout
    private lateinit var ivStatusDot: ImageView

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
        statusPill = findViewById(R.id.statusPill)
        ivStatusDot = findViewById(R.id.ivStatusDot)

        // Set device ID
        tvDeviceInfoHint.text = deviceId

        // Initialize WebSocket client (placeholder until connect)
        webSocketClient = WebSocketClient(this, "ws://placeholder:8443/ws/device", deviceId, "admin123")

        // Setup
        setupWebSocketListeners()
        setupSwitchListener()
        setupButtonListeners()
        startRdmService()
        updateDeviceInfo()

        // Initial status: disconnected
        setStatus(ConnectionStatus.DISCONNECTED)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Status UI
    // ──────────────────────────────────────────────────────────────────────

    enum class ConnectionStatus { CONNECTED, DISCONNECTED, CONNECTING, ERROR }

    private fun setStatus(status: ConnectionStatus) {
        val (label, textColor, bgDrawable, dotColor) = when (status) {
            ConnectionStatus.CONNECTED -> Quadruple(
                "Online",
                R.color.status_connected,
                R.drawable.bg_status_connected,
                R.color.status_connected
            )
            ConnectionStatus.DISCONNECTED -> Quadruple(
                "Offline",
                R.color.status_disconnected,
                R.drawable.bg_status_disconnected,
                R.color.status_disconnected
            )
            ConnectionStatus.CONNECTING -> Quadruple(
                "Connecting…",
                R.color.status_connecting,
                R.drawable.bg_status_connecting,
                R.color.status_connecting
            )
            ConnectionStatus.ERROR -> Quadruple(
                "Error",
                R.color.status_disconnected,
                R.drawable.bg_status_disconnected,
                R.color.status_disconnected
            )
        }

        tvStatus.text = label
        tvStatus.setTextColor(ContextCompat.getColor(this, textColor))
        statusPill.background = ContextCompat.getDrawable(this, bgDrawable)

        // Tint the dot
        val dotDrawable = ivStatusDot.background.mutate() as GradientDrawable
        dotDrawable.setColor(ContextCompat.getColor(this, dotColor))
        ivStatusDot.background = dotDrawable

        // Pulse animation only when connecting
        if (status == ConnectionStatus.CONNECTING) {
            val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
            ivStatusDot.startAnimation(pulse)
        } else {
            ivStatusDot.clearAnimation()
            // Fade-in the pill for a nice transition
            val fadeIn = ObjectAnimator.ofFloat(statusPill, "alpha", 0.6f, 1f).apply {
                duration = 300
            }
            fadeIn.start()
        }
    }

    // Simple data holder (Kotlin doesn't have built-in Quadruple)
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // ──────────────────────────────────────────────────────────────────────
    // Listeners
    // ──────────────────────────────────────────────────────────────────────

    private fun setupSwitchListener() {
        switchAppendDeviceId.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Append Device ID: $isChecked")
        }
    }

    private fun setupWebSocketListeners() {
        webSocketClient.onConnected = {
            runOnUiThread {
                setStatus(ConnectionStatus.CONNECTED)
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                Toast.makeText(this@MainActivity, "Connected to server", Toast.LENGTH_SHORT).show()
            }
        }

        webSocketClient.onDisconnected = {
            runOnUiThread {
                setStatus(ConnectionStatus.DISCONNECTED)
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
            }
        }

        webSocketClient.onError = { exception ->
            runOnUiThread {
                setStatus(ConnectionStatus.ERROR)
                tvStatus.text = "Error"
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
                Toast.makeText(
                    this@MainActivity,
                    "Connection error: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        webSocketClient.onMessage = { message ->
            runOnUiThread {
                val type = message.get("type")?.asString
                when (type) {
                    "command" -> {
                        val command = message.get("command")?.asString
                        val commandId = message.get("id")?.asString
                        Log.d(TAG, "Command received: $command (ID: $commandId)")
                        Toast.makeText(this@MainActivity, "⚡ Command: $command", Toast.LENGTH_SHORT).show()
                    }
                    "log_request" -> {
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
        btnConnect.setOnClickListener { connectToServer() }
        btnDisconnect.setOnClickListener { disconnectFromServer() }
        btnTestCommand.setOnClickListener { testCommand() }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Actions
    // ──────────────────────────────────────────────────────────────────────

    private fun connectToServer() {
        lifecycleScope.launch {
            try {
                val serverUrl = etServerUrl.text.toString().trim()
                val appendDeviceId = switchAppendDeviceId.isChecked

                if (serverUrl.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter a server URL", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Build final URL
                val finalUrl = if (appendDeviceId) {
                    if (!serverUrl.endsWith(deviceId)) "$serverUrl/$deviceId" else serverUrl
                } else {
                    serverUrl
                }

                // Disconnect existing client
                webSocketClient.disconnect()

                // Create new client
                webSocketClient = WebSocketClient(this@MainActivity, finalUrl, deviceId, "admin123")
                setupWebSocketListeners()

                Log.d(TAG, "Connecting to: $finalUrl")
                runOnUiThread { setStatus(ConnectionStatus.CONNECTING) }

                webSocketClient.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                runOnUiThread {
                    setStatus(ConnectionStatus.ERROR)
                    Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun disconnectFromServer() {
        lifecycleScope.launch {
            webSocketClient.disconnect()
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
            }
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
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
                    val ramFree = (deviceInfo.memory_info.available / 1024 / 1024).toInt()
                    val storageFree = (deviceInfo.storage_info.available / 1024 / 1024 / 1024).toInt()
                    val batteryPct = deviceInfo.battery_info.percentage.toInt()

                    val infoText = buildString {
                        appendLine("# System Overview")
                        appendLine("  name     : ${deviceInfo.name}")
                        appendLine("  model    : ${deviceInfo.model}")
                        appendLine("  android  : ${deviceInfo.android_version} (API ${deviceInfo.api_level})")
                        appendLine()
                        appendLine("# Hardware")
                        appendLine("  cpu      : ${deviceInfo.cpu_info.cores} cores · ${deviceInfo.cpu_info.model}")
                        appendLine("  ram      : $ramFree MB free")
                        appendLine("  storage  : $storageFree GB free")
                        appendLine("  battery  : $batteryPct%")
                        appendLine()
                        appendLine("# Software")
                        appendLine("  apps     : ${deviceInfo.installed_apps.size} installed")
                        appendLine()
                        appendLine("# Identity")
                        append("  id       : $deviceId")
                    }

                    tvDeviceInfo.text = infoText
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting device info", e)
                runOnUiThread {
                    tvDeviceInfo.text = "# Error\n  ${e.message}"
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient.disconnect()
    }
}
