package com.rdm.client

data class AppInfo(
    val packageName: String,
    val appName: String?,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val icon: String?,  // Could store icon data URI or path
    val isRecordingEnabled: Boolean = false
) {
    override fun toString(): String {
        val systemPrefix = if (isSystem) "[System] " else ""
        val recordingMark = if (isRecordingEnabled) " 🔴" else ""
        return "$systemPrefix$appName${recordingMark ?: ""} ($packageName)"
    }
}
