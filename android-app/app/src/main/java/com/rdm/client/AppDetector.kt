package com.rdm.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DetectedApp(
    val packageName: String,
    val appName: String,
    val isInstalled: Boolean,
    val isRecording: Boolean
)

class AppDetector(private val context: Context) {
    private val TAG = "AppDetector"
    private val packageManager = context.packageManager

    // Target apps to monitor
    private val targetApps = listOf(
        TargetApp(
            packageName = "org.thoughtcrime.securesms",
            displayName = "Signal",
            appType = "Signal"
        ),
        TargetApp(
            packageName = "org.thoughtcrime.securesms.beta",
            displayName = "Signal Beta",
            appType = "Signal"
        ),
        TargetApp(
            packageName = "org.telegram.messenger",
            displayName = "Telegram",
            appType = "Telegram"
        ),
        TargetApp(
            packageName = "org.telegram.messenger.web",
            displayName = "Telegram Web",
            appType = "Telegram"
        ),
        TargetApp(
            packageName = "plus.messenger.android",
            displayName = "Telegram Plus",
            appType = "Telegram"
        ),
        TargetApp(
            packageName = "org.telegram.plus",
            displayName = "Telegram+",
            appType = "Telegram"
        )
    )

    suspend fun detectApps(): Result<Map<String, DetectedApp>> = withContext(Dispatchers.IO) {
        try {
            val detectedApps = mutableMapOf<String, DetectedApp>()

            Log.d(TAG, "Checking ${targetApps.size} target apps...")
            Log.d(TAG, "AppDetector: packageManager = ${packageManager != null}")

            Log.d(TAG, "Checking ${targetApps.size} target apps...")
            Log.d(TAG, "Target apps: ${targetApps.map { "${it.displayName} (${it.packageName})" }}")

            // Get all installed packages directly (more reliable)
            val allPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            Log.d(TAG, "  Total installed packages: ${allPackages.size}")
            
            val installedPackageNames = allPackages.map { it.packageName }.toSet()
            Log.d(TAG, "  Installed package names set created: ${installedPackageNames.size} packages")
            Log.d(TAG, "  First 5 packages: ${installedPackageNames.take(5)}")

            // Debug: search for telegram packages
            val telegramPackages = installedPackageNames.filter { it.contains("telegram", ignoreCase = true) }
            Log.d(TAG, "  Found ${telegramPackages.size} packages containing 'telegram': $telegramPackages")

            // Debug: search for signal packages  
            val signalPackages = installedPackageNames.filter { it.contains("thoughtcrime", ignoreCase = true) || it.contains("signal", ignoreCase = true) }
            Log.d(TAG, "  Found ${signalPackages.size} packages containing 'signal': $signalPackages")

            for (targetApp in targetApps) {
                Log.d(TAG, "Checking app: ${targetApp.displayName} (${targetApp.packageName})")

                // Check if package is in installed packages
                val isInstalled = targetApp.packageName in installedPackageNames

                Log.d(TAG, "  -> Package in installed list: $isInstalled")

                if (isInstalled) {
                    Log.d(TAG, "Detected app: ${targetApp.displayName} (${targetApp.packageName})")

                    detectedApps[targetApp.appType] = DetectedApp(
                        packageName = targetApp.packageName,
                        appName = targetApp.displayName,
                        isInstalled = true,
                        isRecording = false
                    )
                }
            }

            Log.d(TAG, "Total detected apps: ${detectedApps.size}")

            Result.success(detectedApps)
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting apps", e)
            Result.failure(e)
        }
    }

    fun isAppInstalled(packageName: String): Boolean {
        return try {
            // Try different flags for compatibility
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_DISABLED_COMPONENTS
            } else {
                0
            }
            packageManager.getApplicationInfo(packageName, flags)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "App not found: $packageName - ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking app $packageName: ${e.javaClass.simpleName} - ${e.message}", e)
            false
        }
    }

    fun getAppName(packageName: String): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo)?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for $packageName", e)
            null
        }
    }

    fun getRecordablePackages(): List<String> {
        // Return package names that are installed and should be recorded
        return targetApps.filter { isAppInstalled(it.packageName) }
            .map { it.packageName }
    }

    private data class TargetApp(
        val packageName: String,
        val displayName: String,
        val appType: String
    )

    companion object {
        // Package names for Telegram variants
        const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        const val TELEGRAM_WEB_PACKAGE = "org.telegram.messenger.web"

        // Package names for Signal variants
        const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
        const val SIGNAL_BETA_PACKAGE = "org.thoughtcrime.securesms.beta"
    }
}
