package com.rdm.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

data class CommandResult(
    val success: Boolean,
    val output: String?,
    val error: String?
)

class RootExecutor {
    private val TAG = "RootExecutor"

    // Persistent root shell
    private var rootProcess: Process? = null
    private var rootWriter: OutputStreamWriter? = null
    private var rootReader: BufferedReader? = null
    private val rootMutex = Mutex()
    private var isRootShellActive = false

    private suspend fun ensureRootShell() = withContext(Dispatchers.IO) {
        rootMutex.withLock {
            if (rootProcess == null || rootProcess?.isAlive == false) {
                try {
                    Log.d(TAG, "Starting persistent root shell...")

                    // Start a persistent su shell
                    rootProcess = ProcessBuilder("su").start()
                    rootWriter = OutputStreamWriter(rootProcess?.outputStream)
                    rootReader = BufferedReader(InputStreamReader(rootProcess?.inputStream))

                    // Don't wait for initial output - just mark as active
                    // The root shell might not output anything initially
                    isRootShellActive = true
                    Log.d(TAG, "Root shell initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start root shell", e)
                    isRootShellActive = false
                    rootProcess = null
                    rootWriter = null
                    rootReader = null
                }
            }
        }
    }

    private suspend fun executeInRootShell(command: String, timeoutMs: Long): CommandResult = withContext(Dispatchers.IO) {
        rootMutex.withLock {
            try {
                if (!isRootShellActive || rootWriter == null || rootReader == null) {
                    return@withContext CommandResult(
                        success = false,
                        output = null,
                        error = "Root shell not active"
                    )
                }

                // Write command
                rootWriter?.write("$command\n")
                rootWriter?.flush()

                // Add a marker to detect command completion
                val marker = "EOF_${System.currentTimeMillis()}"
                rootWriter?.write("echo $marker\n")
                rootWriter?.flush()

                // Read output until marker with timeout
                val output = StringBuilder()
                val startTime = System.currentTimeMillis()
                val checkInterval = 50L // Check every 50ms

                while (true) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > timeoutMs) {
                        Log.e(TAG, "Command timed out after ${elapsed}ms: $command")
                        return@withContext CommandResult(
                            success = false,
                            output = output.takeIf { it.isNotEmpty() }?.toString(),
                            error = "Command timed out after ${timeoutMs}ms"
                        )
                    }

                    // Try to read while data is available
                    var hasData: Boolean
                    var readLine: String?

                    while (true) {
                        hasData = try {
                            // Check if data is available
                            rootReader?.ready() == true
                        } catch (e: Exception) {
                            false
                        }

                        if (hasData) {
                            // Data available, read it
                            readLine = try {
                                rootReader?.readLine()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error reading from root shell", e)
                                null
                            }

                            if (readLine != null) {
                                if (readLine.contains(marker)) {
                                    // Found the marker, we're done
                                    Log.d(TAG, "Found marker after ${elapsed}ms")
                                    return@withContext CommandResult(
                                        success = true,
                                        output = output.toString(),
                                        error = null
                                    )
                                }
                                output.append(readLine).append("\n")
                            } else {
                                // End of stream
                                hasData = false
                            }
                        } else {
                            // No data available right now
                            hasData = false
                        }
                    }

                    // Small delay before checking again
                    kotlinx.coroutines.delay(checkInterval)
                }

                CommandResult(success = true, output = output.toString(), error = null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute command in root shell", e)
                // Shell might be broken, try to recreate next time
                closeRootShell()
                CommandResult(
                    success = false,
                    output = null,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun closeRootShell() {
        try {
            rootWriter?.close()
            rootReader?.close()
            rootProcess?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing root shell", e)
        } finally {
            rootProcess = null
            rootWriter = null
            rootReader = null
            isRootShellActive = false
        }
    }

    fun cleanup() {
        closeRootShell()
    }

    suspend fun execute(command: String, useSudo: Boolean = false, timeoutMs: Long = 3000): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                if (useSudo) {
                    // Use persistent root shell
                    ensureRootShell()
                    if (isRootShellActive) {
                        executeInRootShell(command, timeoutMs)
                    } else {
                        // Fallback to non-persistent method
                        executeDirectly("su -c '$command'", timeoutMs)
                    }
                } else {
                    // Non-root commands execute directly
                    executeDirectly(command, timeoutMs)
                }
            } catch (e: Exception) {
                CommandResult(
                    success = false,
                    output = null,
                    error = e.message ?: "Unknown error"
                )
            }
        }

    private suspend fun executeDirectly(command: String, timeoutMs: Long): CommandResult {
        try {
            // Use ProcessBuilder for better control
            val processBuilder = ProcessBuilder("sh", "-c", command)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            // Read output with timeout
            val startTime = System.currentTimeMillis()
            val output = StringBuilder()

            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > timeoutMs) {
                    process.destroy()
                    return CommandResult(
                        success = false,
                        output = null,
                        error = "Command timed out after ${timeoutMs}ms"
                    )
                }

                val char = process.inputStream.read()
                if (char == -1) break

                output.append(char.toChar())

                // Check if process has completed
                if (!process.isAlive) {
                    // Read remaining output
                    val remaining = process.inputStream.readAllBytes()
                    if (remaining.isNotEmpty()) {
                        output.append(String(remaining))
                    }
                    break
                }

                // Small delay to avoid busy waiting
                kotlinx.coroutines.delay(10)
            }

            val exitCode = process.waitFor()

            if (exitCode == 0) {
                return CommandResult(success = true, output = output.toString(), error = null)
            } else {
                return CommandResult(
                    success = false,
                    output = output.toString(),
                    error = "Exit code: $exitCode"
                )
            }
        } catch (e: Exception) {
            return CommandResult(
                success = false,
                output = null,
                error = e.message ?: "Unknown error"
            )
        }
    }

    suspend fun getForegroundAppInfo(): ForegroundAppInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "getForegroundAppInfo: starting")

            // Try dumpsys without root first (might work without su on some devices)
            var result = execute("dumpsys activity activities", useSudo = false, timeoutMs = 10000)

            // If that fails, try with su wrapper
            if (!result.success || (result.output?.length ?: 0) < 1000) {
                Log.d(TAG, "dumpsys without root failed, trying with su")
                result = execute("dumpsys activity activities", useSudo = true, timeoutMs = 10000)
            }

            Log.d(TAG, "getForegroundAppInfo: dumpsys success=${result.success}, outputLength=${result.output?.length ?: 0}")

            if (result.success && result.output != null && result.output.isNotEmpty()) {
                Log.d(TAG, "getForegroundAppInfo: calling parseForegroundApp")
                parseForegroundApp(result.output)
            } else {
                Log.w(TAG, "getForegroundAppInfo: dumpsys returned empty or failed: ${result.error}")
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

        // Look for topResumedActivity line which contains the current foreground app
        // Format: topResumedActivity=ActivityRecord{... u0 com.example.app/.MainActivity t1234}
        val lines = output.lines()

        Log.d(TAG, "parseForegroundApp: total lines = ${lines.size}")

        // Try topResumedActivity first (Android 10+)
        // Handle both direct format and grep -A context format
        val resumedLine = lines.firstOrNull { line ->
            line.trim().startsWith("topResumedActivity=") ||
            line.trim().startsWith("mResumedActivity:") ||
            line.contains("topResumedActivity=") ||
            line.contains("mResumedActivity:")
        }

        Log.d(TAG, "parseForegroundApp: resumedLine = ${resumedLine?.take(150)}")

        if (resumedLine != null) {
            // Parse line like: topResumedActivity=ActivityRecord{... u0 com.example.app/.MainActivity t1234}
            // or: mResumedActivity: ActivityRecord{... u0 com.example.app/.MainActivity t1234}
            val regex = """[u0\s]+([a-zA-Z][a-zA-Z0-9_.]*(?:\.[a-zA-Z][a-zA-Z0-9_.]*)+)/""".toRegex()
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
