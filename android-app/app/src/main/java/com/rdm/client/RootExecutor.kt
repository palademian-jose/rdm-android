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

    suspend fun execute(command: String, useSudo: Boolean = false, timeoutMs: Long = 3000): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val fullCommand = if (useSudo) {
                    "su -c '$command'"
                } else {
                    command
                }

                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCommand))
                
                // Add timeout
                var finished = false
                val timeoutThread = Thread {
                    Thread.sleep(timeoutMs)
                    if (!finished) {
                        process.destroy()
                    }
                }
                timeoutThread.start()

                val exitCode = process.waitFor()
                finished = true

                val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readText()
                }

                val error = BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.readText()
                }

                if (exitCode == 0) {
                    CommandResult(success = true, output = output, error = null)
                } else {
                    CommandResult(
                        success = false,
                        output = output,
                        error = error.ifEmpty { "Exit code: $exitCode" }
                    )
                }
            } catch (e: Exception) {
                CommandResult(
                    success = false,
                    output = null,
                    error = e.message ?: "Unknown error"
                )
            }
        }

    suspend fun getForegroundAppInfo(): ForegroundAppInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "getForegroundAppInfo: starting")
            
            // Use dumpsys which should work without special permissions
            val result = execute("dumpsys activity activities", useSudo = false, timeoutMs = 3000)
            
            Log.d(TAG, "getForegroundAppInfo: dumpsys success=${result.success}, outputLength=${result.output?.length ?: 0}")

            if (result.success && result.output != null && result.output.isNotEmpty()) {
                Log.d(TAG, "getForegroundAppInfo: calling parseForegroundApp")
                parseForegroundApp(result.output)
            } else {
                Log.w(TAG, "getForegroundAppInfo: dumpsys returned empty or failed")
                ForegroundAppInfo(packageName = null, activityName = null, timestamp = System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get foreground app info", e)
            ForegroundAppInfo(packageName = null, activityName = null, timestamp = System.currentTimeMillis())
        }
    }

    private fun parseForegroundApp(output: String): ForegroundAppInfo {
        val timestamp = System.currentTimeMillis()

        Log.d(TAG, "parseForegroundApp: output length = ${output.length}")
        Log.d(TAG, "parseForegroundApp: output (first 500 chars):\n${output.take(500)}")

        // Look for mResumedActivity line which contains to current foreground app
        // Format: mResumedActivity: ActivityRecord{... u0 com.example.app/.MainActivity t1234}
        val lines = output.lines()
        
        Log.d(TAG, "parseForegroundApp: total lines = ${lines.size}")

        val resumedLine = lines.firstOrNull { line ->
            line.trim().startsWith("mResumedActivity:")
        }

        Log.d(TAG, "parseForegroundApp: resumedLine = ${resumedLine?.take(150)}")

        if (resumedLine != null) {
            // Parse line like: mResumedActivity: ActivityRecord{... u0 com.example.app/.MainActivity t1234}
            val regex = """u0 ([a-zA-Z][a-zA-Z0-9_.]*(?:\.[a-zA-Z][a-zA-Z0-9_.]*)+)/""".toRegex()
            val match = regex.find(resumedLine)

            Log.d(TAG, "parseForegroundApp: regex match = $match")

            if (match != null) {
                val packageName = match.groupValues[1]
                
                Log.d(TAG, "parseForegroundApp: packageName = $packageName")

                return ForegroundAppInfo(
                    packageName = packageName,
                    activityName = null,
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
                val regex = """package:.*?name=([a-zA-Z][a-zA-Z0-9_.]*(?:\.[a-zA-Z][a-zA-Z0-9_.]*)*)""".toRegex()
                val match = regex.find(result.output)
                match?.groupValues?.get(1) ?: packageName
            } else {
                packageName
            }
        } catch (e: Exception) {
            packageName
        }
    }
}

data class ForegroundAppInfo(
    val packageName: String?,
    val activityName: String?,
    val timestamp: Long
)
