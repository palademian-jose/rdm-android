package com.rdm.client

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages offline recordings and uploads them when connection is restored
 */
class RecordingManager(private val context: Context) {
    private val TAG = "RecordingManager"
    private val recordingsDir = File(context.getExternalFilesDir("Movies"), "recordings")
    private val metadataFile = File(recordingsDir, "recording_metadata.json")

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingUploads = mutableSetOf<RecordingMetadata>()
    private var isUploading = false

    init {
        // Create recordings directory
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
        loadPendingUploads()
    }

    /**
     * Called when a recording is completed
     */
    fun onRecordingComplete(filePath: String, appPackage: String, timestamp: Long) {
        scope.launch {
            try {
                val metadata = RecordingMetadata(
                    id = generateId(),
                    filePath = filePath,
                    packageName = appPackage,
                    timestamp = timestamp,
                    uploaded = false,
                    fileSize = File(filePath).length()
                )

                // Save metadata
                saveMetadata(metadata)

                // Add to pending uploads
                pendingUploads.add(metadata)
                Log.d(TAG, "Recording completed and queued for upload: $filePath")

                // Check if we have internet connection
                if (isNetworkAvailable()) {
                    Log.d(TAG, "Network available, attempting immediate upload")
                    uploadRecording(metadata)
                } else {
                    Log.d(TAG, "No network, recording queued for later upload")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue recording for upload", e)
            }
        }
    }

    /**
     * Called when network connection is restored
     */
    fun onConnectionRestored() {
        scope.launch {
            // Reload metadata to ensure we have the latest pending uploads
            try {
                val allMetadata = loadAllMetadata()
                pendingUploads.clear()
                pendingUploads.addAll(
                    allMetadata.filter { !it.uploaded }
                )
                Log.d(TAG, "Loaded ${pendingUploads.size} pending uploads on connection restored")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reload metadata", e)
            }

            if (isUploading) {
                Log.d(TAG, "Already uploading, skipping")
                return@launch
            }

            if (pendingUploads.isEmpty()) {
                Log.d(TAG, "No pending uploads")
                return@launch
            }

            Log.d(TAG, "Connection restored, uploading ${pendingUploads.size} recordings")

            isUploading = true
            try {
                pendingUploads.toList().forEach { metadata ->
                    uploadRecording(metadata)
                }
            } finally {
                isUploading = false
            }
        }
    }

    private suspend fun uploadRecording(metadata: RecordingMetadata) {
        try {
            Log.d(TAG, "Uploading recording: ${metadata.filePath}")

            val file = File(metadata.filePath)
            if (!file.exists()) {
                Log.e(TAG, "Recording file not found: ${metadata.filePath}")
                removePendingUpload(metadata.id)
                return
            }

            // Get server URL from shared preferences, with default fallback
            val prefs = context.getSharedPreferences("RdmClient", Context.MODE_PRIVATE)
            val wsUrl = prefs.getString("server_url", null) ?: "wss://separately-touched-manatee.ngrok-free.app/ws/device"

            if (wsUrl.isEmpty()) {
                Log.e(TAG, "No server URL configured")
                return
            }

            // Convert WebSocket URL to HTTP URL for upload
            // WebSocket URL format: wss://server/ws/device
            // Upload URL format: https://server/api/recordings/upload
            val uploadUrl = if (wsUrl.contains("/ws/device")) {
                val baseUrl = wsUrl.replace("ws://", "http://")
                                   .replace("wss://", "https://")
                                   .replace("/ws/device", "")
                "$baseUrl/api/recordings/upload"
            } else {
                wsUrl.replace("ws://", "http://")
                   .replace("wss://", "https://") + "/api/recordings/upload"
            }

            // Build multipart request
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "video",
                    file.name,
                    okhttp3.RequestBody.create(
                        "video/mp4".toMediaType(),
                        file
                    )
                )
                .build()

            // Build URL with query parameters
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: ""
            val urlWithQuery = "$uploadUrl?device_id=$deviceId&package_name=${metadata.packageName}&timestamp=${metadata.timestamp}"

            val request = okhttp3.Request.Builder()
                .url(urlWithQuery)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.d(TAG, "Recording uploaded successfully: ${metadata.filePath}")
                // Mark as uploaded
                val updated = metadata.copy(uploaded = true)
                saveMetadata(updated)
                removePendingUpload(metadata.id)
            } else {
                Log.e(TAG, "Upload failed with code: ${response.code}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload recording: ${metadata.filePath}", e)
        }
    }

    private fun saveMetadata(metadata: RecordingMetadata) {
        val allMetadata = loadAllMetadata().toMutableList()
        val index = allMetadata.indexOfFirst { it.id == metadata.id }
        if (index >= 0) {
            allMetadata[index] = metadata
        } else {
            allMetadata.add(metadata)
        }
        saveAllMetadata(allMetadata)
    }

    private fun loadPendingUploads() {
        scope.launch {
            try {
                val allMetadata = loadAllMetadata()
                pendingUploads.clear()
                pendingUploads.addAll(
                    allMetadata.filter { !it.uploaded }
                )
                Log.d(TAG, "Loaded ${pendingUploads.size} pending uploads")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load pending uploads", e)
            }
        }
    }

    private fun removePendingUpload(id: String) {
        pendingUploads.removeAll { it.id == id }
    }

    private fun isNetworkAvailable(): Boolean {
        // Check if network is available
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnected == true
    }

    private fun loadAllMetadata(): List<RecordingMetadata> {
        val metadata = mutableListOf<RecordingMetadata>()
        if (metadataFile.exists()) {
            try {
                val json = metadataFile.readText()
                val jsonArray = org.json.JSONArray(json)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    metadata.add(
                        RecordingMetadata(
                            id = obj.getString("id"),
                            filePath = obj.getString("filePath"),
                            packageName = obj.getString("packageName"),
                            timestamp = obj.getLong("timestamp"),
                            uploaded = obj.getBoolean("uploaded"),
                            fileSize = obj.getLong("fileSize")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load metadata", e)
            }
        }
        return metadata
    }

    private fun saveAllMetadata(metadata: List<RecordingMetadata>) {
        try {
            val jsonArray = org.json.JSONArray()
            metadata.forEach { meta ->
                val obj = JSONObject().apply {
                    put("id", meta.id)
                    put("filePath", meta.filePath)
                    put("packageName", meta.packageName)
                    put("timestamp", meta.timestamp)
                    put("uploaded", meta.uploaded)
                    put("fileSize", meta.fileSize)
                }
                jsonArray.put(obj)
            }
            metadataFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata", e)
        }
    }

    private fun generateId(): String {
        return "${System.currentTimeMillis()}-${(0..999).random()}"
    }

    fun getPendingUploadCount(): Int = pendingUploads.size

    fun getAllRecordings(): List<RecordingMetadata> = loadAllMetadata()

    fun getPendingUploads(): List<RecordingMetadata> = pendingUploads.toList()
}

data class RecordingMetadata(
    val id: String,
    val filePath: String,
    val packageName: String,
    val timestamp: Long,
    var uploaded: Boolean,
    val fileSize: Long
)