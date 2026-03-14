package com.rdm.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SystemAppHelper(private val context: Context) {
    private val TAG = "SystemAppHelper"

    companion object {
        const val SYSTEM_APP_PATH = "/system/priv-app/RdmClient"
        const val SYSTEM_APP_FILE = "$SYSTEM_APP_PATH/RdmClient.apk"
    }

    /**
     * Check if the app is installed as a system app
     */
    fun isSystemApp(): Boolean {
        return try {
            val systemAppFile = File(SYSTEM_APP_FILE)
            systemAppFile.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if system app", e)
            false
        }
    }

    /**
     * Check if the device is rooted
     */
    fun isDeviceRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }

    private fun checkRootMethod1(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )

        for (path in paths) {
            if (File(path).exists()) return true
        }
        return false
    }

    private fun checkRootMethod2(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            output.trim().isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun checkRootMethod3(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verify root access by attempting to execute a simple command
     */
    suspend fun verifyRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootExecutor = RootExecutor()
            val result = rootExecutor.execute("echo test", useSudo = true)
            result.success && result.output?.contains("test") == true
        } catch (e: Exception) {
            Log.e(TAG, "Root verification failed", e)
            false
        }
    }

    /**
     * Check if battery optimizations are disabled
     */
    fun areBatteryOptimizationsDisabled(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val packageName = context.packageName
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.isIgnoringBatteryOptimizations(packageName)
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimizations", e)
            false
        }
    }

    /**
     * Request to disable battery optimizations
     */
    fun requestDisableBatteryOptimizations() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!areBatteryOptimizationsDisabled()) {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting battery optimization disable", e)
        }
    }

    /**
     * Check if the app has all necessary permissions
     */
    suspend fun checkPermissions(): PermissionStatus = withContext(Dispatchers.IO) {
        return@withContext PermissionStatus(
            isSystemApp = isSystemApp(),
            isRooted = isDeviceRooted(),
            hasRootAccess = verifyRootAccess(),
            batteryOptimizationsDisabled = areBatteryOptimizationsDisabled()
        )
    }

    /**
     * Print diagnostic information
     */
    suspend fun printDiagnostics() = withContext(Dispatchers.IO) {
        val status = checkPermissions()
        Log.d(TAG, "=== RDM Client Diagnostics ===")
        Log.d(TAG, "System App: ${status.isSystemApp}")
        Log.d(TAG, "Device Rooted: ${status.isRooted}")
        Log.d(TAG, "Root Access: ${status.hasRootAccess}")
        Log.d(TAG, "Battery Optimizations Disabled: ${status.batteryOptimizationsDisabled}")
        Log.d(TAG, "==============================")
    }

    /**
     * Get system information
     */
    suspend fun getSystemInfo(): String = withContext(Dispatchers.IO) {
        return@withContext buildString {
            append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            append("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
            append("App Version: ${getAppVersion()}\n")
            append("Package: ${context.packageName}\n")
            append("System App: ${if (isSystemApp()) "Yes" else "No"}\n")
            append("Rooted: ${if (isDeviceRooted()) "Yes" else "No"}\n")
            append("Root Access: ${if (verifyRootAccess()) "Yes" else "No"}\n")
            append("Battery Opt: ${if (areBatteryOptimizationsDisabled()) "Disabled" else "Enabled"}")
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}

data class PermissionStatus(
    val isSystemApp: Boolean,
    val isRooted: Boolean,
    val hasRootAccess: Boolean,
    val batteryOptimizationsDisabled: Boolean
) {
    fun isFullyConfigured(): Boolean {
        return isSystemApp && isRooted && hasRootAccess && batteryOptimizationsDisabled
    }

    fun getIssues(): List<String> {
        val issues = mutableListOf<String>()
        if (!isSystemApp) issues.add("Not installed as system app")
        if (!isRooted) issues.add("Device is not rooted")
        if (!hasRootAccess) issues.add("No root access available")
        if (!batteryOptimizationsDisabled) issues.add("Battery optimizations enabled")
        return issues
    }
}