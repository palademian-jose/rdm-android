package com.rdm.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class CommandResult(
    val success: Boolean,
    val output: String?,
    val error: String?
)

class RootExecutor {
    private val TAG = "RootExecutor"

    suspend fun execute(command: String, useSudo: Boolean = false): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val fullCommand = if (useSudo) {
                    "su -c '$command'"
                } else {
                    command
                }

                Log.d(TAG, "Executing: $fullCommand")

                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCommand))
                val exitCode = process.waitFor()

                val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readText()
                }

                val error = BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.readText()
                }

                if (exitCode == 0) {
                    Log.d(TAG, "Command succeeded")
                    CommandResult(success = true, output = output, error = null)
                } else {
                    Log.e(TAG, "Command failed with exit code $exitCode")
                    CommandResult(
                        success = false,
                        output = output,
                        error = error.ifEmpty { "Exit code: $exitCode" }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution error", e)
                CommandResult(
                    success = false,
                    output = null,
                    error = e.message ?: "Unknown error"
                )
            }
        }

    suspend fun executeMultiple(commands: List<String>, useSudo: Boolean = false): List<CommandResult> =
        withContext(Dispatchers.IO) {
            commands.map { command ->
                execute(command, useSudo)
            }
        }

    suspend fun executeScript(script: String, useSudo: Boolean = false): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val fullCommand = if (useSudo) {
                    "su -c '$script'"
                } else {
                    script
                }

                Log.d(TAG, "Executing script (length: ${script.length})")

                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCommand))
                val exitCode = process.waitFor()

                val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readText()
                }

                val error = BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.readText()
                }

                if (exitCode == 0) {
                    Log.d(TAG, "Script executed successfully")
                    CommandResult(success = true, output = output, error = null)
                } else {
                    Log.e(TAG, "Script failed with exit code $exitCode")
                    CommandResult(
                        success = false,
                        output = output,
                        error = error.ifEmpty { "Exit code: $exitCode" }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Script execution error", e)
                CommandResult(
                    success = false,
                    output = null,
                    error = e.message ?: "Unknown error"
                )
            }
        }

    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = execute("su -c 'echo test'", useSudo = false)
            result.success
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    suspend fun getDeviceInfo(): CommandResult {
        return execute("getprop", useSudo = false)
    }

    suspend fun getNetworkInfo(): CommandResult {
        return execute("ip addr show", useSudo = false)
    }

    suspend fun getProcesses(): CommandResult {
        return execute("ps aux", useSudo = false)
    }

    suspend fun getMemoryInfo(): CommandResult {
        return execute("cat /proc/meminfo", useSudo = false)
    }

    suspend fun getCpuInfo(): CommandResult {
        return execute("cat /proc/cpuinfo", useSudo = false)
    }

    suspend fun getStorageInfo(): CommandResult {
        return execute("df -h", useSudo = false)
    }

    suspend fun getBatteryInfo(): CommandResult {
        return execute("dumpsys battery", useSudo = false)
    }

    suspend fun installApp(apkPath: String): CommandResult {
        return execute("pm install -r $apkPath", useSudo = true)
    }

    suspend fun uninstallApp(packageName: String): CommandResult {
        return execute("pm uninstall $packageName", useSudo = true)
    }

    suspend fun clearAppData(packageName: String): CommandResult {
        return execute("pm clear $packageName", useSudo = true)
    }

    suspend fun grantPermission(packageName: String, permission: String): CommandResult {
        return execute("pm grant $packageName $permission", useSudo = true)
    }

    suspend fun revokePermission(packageName: String, permission: String): CommandResult {
        return execute("pm revoke $packageName $permission", useSudo = true)
    }

    suspend fun listApps(): CommandResult {
        return execute("pm list packages -3", useSudo = false)
    }

    suspend fun listSystemApps(): CommandResult {
        return execute("pm list packages -s", useSudo = false)
    }

    suspend fun startService(packageName: String, serviceName: String): CommandResult {
        return execute("am startservice -n $packageName/$serviceName", useSudo = false)
    }

    suspend fun stopService(packageName: String, serviceName: String): CommandResult {
        return execute("am stopservice $packageName/$serviceName", useSudo = false)
    }

    suspend fun forceStopApp(packageName: String): CommandResult {
        return execute("am force-stop $packageName", useSudo = true)
    }

    suspend fun reboot(): CommandResult {
        return execute("reboot", useSudo = true)
    }

    suspend fun shutdown(): CommandResult {
        return execute("shutdown now", useSudo = true)
    }

    suspend fun setScreenBrightness(level: Int): CommandResult {
        return execute("settings put system screen_brightness $level", useSudo = true)
    }

    suspend fun enableWiFi(): CommandResult {
        return execute("svc wifi enable", useSudo = true)
    }

    suspend fun disableWiFi(): CommandResult {
        return execute("svc wifi disable", useSudo = true)
    }

    suspend fun enableBluetooth(): CommandResult {
        return execute("service call bluetooth_manager 6", useSudo = true)
    }

    suspend fun disableBluetooth(): CommandResult {
        return execute("service call bluetooth_manager 8", useSudo = true)
    }

    suspend fun getCurrentForegroundApp(): CommandResult {
        return execute("dumpsys activity activities | grep 'mResumedActivity'", useSudo = false)
    }

    suspend fun getForegroundAppInfo(): ForegroundAppInfo = withContext(Dispatchers.IO) {
        try {
            val result = execute("dumpsys activity activities", useSudo = false)
            if (result.success && result.output != null) {
                parseForegroundApp(result.output)
            } else {
                ForegroundAppInfo(packageName = null, activityName = null, timestamp = System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get foreground app info", e)
            ForegroundAppInfo(packageName = null, activityName = null, timestamp = System.currentTimeMillis())
        }
    }

    private fun parseForegroundApp(output: String): ForegroundAppInfo {
        val timestamp = System.currentTimeMillis()

        // Look for mResumedActivity line which contains the current foreground app
        val resumedLine = output.lines().firstOrNull { line ->
            line.trim().startsWith("mResumedActivity:")
        }

        if (resumedLine != null) {
            // Parse line like: mResumedActivity: ActivityRecord{... u0 com.example.app/.MainActivity t1234}
            val regex = """([a-zA-Z][a-zA-Z0-9_.]*(?:\.[a-zA-Z][a-zA-Z0-9_.]*)+)/""".toRegex()
            val match = regex.find(resumedLine)

            if (match != null) {
                val packageName = match.groupValues[1]
                val activityMatch = Regex("""/([a-zA-Z][a-zA-Z0-9_.]*)""").find(resumedLine)
                val activityName = activityMatch?.groupValues?.get(1)

                return ForegroundAppInfo(
                    packageName = packageName,
                    activityName = activityName,
                    timestamp = timestamp
                )
            }
        }

        return ForegroundAppInfo(packageName = null, activityName = null, timestamp = timestamp)
    }

    suspend fun getAppName(packageName: String): String = withContext(Dispatchers.IO) {
        try {
            val result = execute("pm list packages -f $packageName", useSudo = false)
            if (result.success && result.output != null) {
                // Parse package name from output
                val regex = """package:.*?name=([a-zA-Z][a-zA-Z0-9_.]*(?:\.[a-zA-Z][a-zA-Z0-9_.]*)*)""".toRegex()
                val match = regex.find(result.output)
                match?.groupValues?.get(1) ?: packageName
            } else {
                packageName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app name", e)
            packageName
        }
    }
}

data class ForegroundAppInfo(
    val packageName: String?,
    val activityName: String?,
    val timestamp: Long
)
