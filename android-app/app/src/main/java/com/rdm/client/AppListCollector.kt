package com.rdm.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppListCollector(private val context: Context) {
    private val TAG = "AppListCollector"
    private val packageManager = context.packageManager

    suspend fun getAllApps(): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        try {
            val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            val appList = packages.map { appInfo ->
                try {
                    val appName = packageManager.getApplicationLabel(appInfo)?.toString()
                    val packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0)
                    val versionName = packageInfo.versionName
                    val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    }
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = appName ?: appInfo.packageName,
                        versionName = versionName,
                        versionCode = versionCode,
                        isSystem = isSystem,
                        icon = null // Could add icon extraction later
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing app ${appInfo.packageName}", e)
                    null
                }
            }.filterNotNull()
                .sortedWith(compareBy({ !it.isSystem }, { it.appName }))

            Result.success(appList)
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting app list", e)
            Result.failure(e)
        }
    }

    suspend fun getAppInfo(packageName: String): Result<AppInfo> = withContext(Dispatchers.IO) {
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo)?.toString()
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            Result.success(AppInfo(
                packageName = packageName,
                appName = appName ?: packageName,
                versionName = versionName,
                versionCode = versionCode,
                isSystem = isSystem,
                icon = null
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app info for $packageName", e)
            Result.failure(e)
        }
    }

    suspend fun searchApps(query: String): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        try {
            val allAppsResult = getAllApps()
            if (allAppsResult.isFailure) {
                return@withContext allAppsResult
            }

            val filtered = allAppsResult.getOrNull()?.filter { app ->
                app.packageName.contains(query, ignoreCase = true) ||
                app.appName?.contains(query, ignoreCase = true) == true
            } ?: emptyList()

            Result.success(filtered)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching apps", e)
            Result.failure(e)
        }
    }

    fun getRecordingApps(): List<String> {
        // Load from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_RECORDING_APPS, emptySet())?.toList() ?: emptyList()
    }

    fun addRecordingApp(packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_RECORDING_APPS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_RECORDING_APPS, current + packageName).apply()
        Log.d(TAG, "Added recording app: $packageName")
    }

    fun removeRecordingApp(packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_RECORDING_APPS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_RECORDING_APPS, current - packageName).apply()
        Log.d(TAG, "Removed recording app: $packageName")
    }

    companion object {
        private const val PREFS_NAME = "rdm_recording_prefs"
        private const val KEY_RECORDING_APPS = "recording_apps"
    }
}
