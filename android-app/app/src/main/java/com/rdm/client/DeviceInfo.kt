package com.rdm.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.telephony.TelephonyManager
import android.accounts.AccountManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class DeviceInfo(
    val id: String,
    val name: String,
    val model: String,
    val manufacturer: String,
    val android_version: String,
    val api_level: Int,
    val architecture: String,
    val serial: String,
    val device_type: String,
    val screen_info: ScreenInfo,
    val network_info: NetworkInfo,
    val storage_info: StorageInfo,
    val memory_info: MemoryInfo,
    val cpu_info: CpuInfo,
    val battery_info: BatteryInfo,
    val installed_apps: List<AppInfo>,
    val user_data: UserData,
    val created_at: String
)

data class ScreenInfo(
    val width: Int,
    val height: Int,
    val density: Float,
    val orientation: Int
)

data class NetworkInfo(
    val ip_address: String?,
    val mac_address: String?,
    val wifi_ssid: String?,
    val network_type: String
)

data class StorageInfo(
    val total: Long,
    val used: Long,
    val available: Long,
    val percentage_used: Float
)

data class MemoryInfo(
    val total: Long,
    val available: Long,
    val used: Long,
    val percentage_used: Float
)

data class CpuInfo(
    val cores: Int,
    val model: String,
    val usage: Float
)

data class BatteryInfo(
    val level: Int,
    val scale: Int,
    val percentage: Float,
    val status: String,
    val health: String,
    val temperature: Float?
)

data class AppInfo(
    val package_name: String,
    val app_name: String?,
    val version_name: String?,
    val version_code: Long,
    val is_system: Boolean,
    val installed_date: Long?,
    val last_updated_date: Long?,
    val icon_path: String?
)

data class UserData(
    val google_accounts: List<String>,
    val phone_number: String?,
    val email_accounts: List<String>,
    val SIM_info: SIMInfo?
)

data class SIMInfo(
    val carrier_name: String?,
    val country_code: String?,
    val phone_number: String?,
    val network_operator: String?
)

object DeviceInfoCollector {
    suspend fun collect(context: Context): DeviceInfo {
        return withContext(Dispatchers.IO) {
            DeviceInfo(
                id = getDeviceId(context),
                name = getDeviceName(),
                model = android.os.Build.MODEL,
                manufacturer = android.os.Build.MANUFACTURER,
                android_version = android.os.Build.VERSION.RELEASE,
                api_level = android.os.Build.VERSION.SDK_INT,
                architecture = getArchitecture(),
                serial = android.os.Build.getSerial(),
                device_type = getDeviceType(),
                screen_info = getScreenInfo(context),
                network_info = getNetworkInfo(context),
                storage_info = getStorageInfo(context),
                memory_info = getMemoryInfo(context),
                cpu_info = getCpuInfo(),
                battery_info = getBatteryInfo(context),
                installed_apps = getInstalledApps(context),
                user_data = collectUserData(context),
                created_at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
            )
        }
    }

    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private fun getDeviceName(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }

    private fun getArchitecture(): String {
        return android.os.Build.SUPPORTED_ABIS[0]
    }

    private fun getDeviceType(): String {
        return when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R -> "Tablet"
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> "Large Tablet"
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB -> "Tablet"
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.DONUT -> "Phone"
            else -> "Phone"
        }
    }

    private fun getScreenInfo(context: Context): ScreenInfo {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val displayMetrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        return ScreenInfo(
            width = displayMetrics.widthPixels,
            height = displayMetrics.heightPixels,
            density = displayMetrics.density,
            orientation = displayMetrics.rotation
        )
    }

    private fun getNetworkInfo(context: Context): NetworkInfo {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetworkInfo
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

            val ipAddress = when (activeNetwork) {
                is android.net.NetworkInfo -> {
                    val address = java.net.InetAddress.getByAddress(activeNetwork.linkProperties.linkAddresses[0].address).hostAddress
                    address?.hostAddress
                }
                else -> null
            }

            val macAddress = when (activeNetwork) {
                is android.net.NetworkInfo -> {
                    val address = activeNetwork.linkProperties.linkAddresses[0].linkAddress
                    address?.hostAddress
                }
                else -> null
            }

            val wifiSSID = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                wifiManager?.currentNetwork?.ssid
            } else {
                @Suppress("DEPRECATION")
                wifiManager?.connectionInfo?.ssid
            }

            return NetworkInfo(
                ip_address = ipAddress,
                mac_address = macAddress,
                wifi_ssid = wifiSSID?.removeSurrounding("\""),
                network_type = activeNetwork?.type?.name ?: "unknown"
            )
        } catch (e: Exception) {
            return NetworkInfo(null, null, null, "unknown")
        }
    }

    private fun getStorageInfo(context: Context): StorageInfo {
        val total = getTotalStorage()
        val available = getAvailableStorage()
        val used = total - available

        return StorageInfo(
            total = total,
            used = used,
            available = available,
            percentage_used = if (total > 0) (used * 100f / total) else 0f
        )
    }

    private fun getTotalStorage(): Long {
        return if (android.os.Environment.getExternalStorageState() == android.os.Environment.MEDIA_MOUNTED) {
            val stat = android.os.StatFs.getExternalStorageDirectory()
            stat.blockSizeLong * stat.blockCount
        } else {
            0L
        }
    }

    private fun getAvailableStorage(): Long {
        return try {
            val stat = android.os.StatFs.getExternalStorageDirectory()
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    private fun getMemoryInfo(context: Context): MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()

        val total = memoryInfo.totalMem
        val available = memoryInfo.availMem

        return MemoryInfo(
            total = total,
            available = available,
            used = total - available,
            percentage_used = if (total > 0) ((total - available) * 100f / total) else 0f
        )
    }

    private fun getCpuInfo(): CpuInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val model = android.os.Build.SUPPORTED_ABIS[0]
        val usage = 0.0f // Placeholder for actual usage

        return CpuInfo(
            cores = cores,
            model = model,
            usage = usage
        )
    }

    private fun getBatteryInfo(context: Context): BatteryInfo {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val batteryStatus: android.os.BatteryManager.BatteryStatus? = batteryManager.getCurrentBatteryProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)

            val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val scale = 100
            val percentage = (batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_LEVEL).toFloat() / level * 100)

            return BatteryInfo(
                level = level,
                scale = scale,
                percentage = percentage,
                status = batteryStatus?.name ?: "unknown",
                health = batteryStatus?.name ?: "unknown",
                temperature = null
            )
        } catch (e: Exception) {
            return BatteryInfo(0, 0, 0f, "unknown", "unknown", null)
        }
    }

    private fun getInstalledApps(context: Context): List<AppInfo> {
        val packageManager = context.packageManager
        val packages = packageManager.getInstalledPackages(0)
        val apps = mutableListOf<AppInfo>()

        for (packageInfo in packages) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageInfo.packageName)
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                val app = AppInfo(
                    package_name = packageInfo.packageName,
                    app_name = appInfo.loadLabel(context.packageManager).toString(),
                    version_name = packageInfo.versionName,
                    version_code = appInfo.longVersionCode,
                    is_system = isSystem,
                    installed_date = appInfo.firstInstallTime,
                    last_updated_date = appInfo.lastUpdateTime,
                    icon_path = appInfo.loadIcon(packageManager)?.toString()
                )

                apps.add(app)
            } catch (e: Exception) {
                // Skip app if error
            }
        }

        return apps.sortedBy { it.app_name }
    }

    private fun collectUserData(context: Context): UserData {
        val googleAccounts = getGoogleAccounts(context)
        val emailAccounts = getEmailAccounts(context)
        val phoneNumber = getPhoneNumber(context)
        val simInfo = getSIMInfo(context)

        return UserData(
            google_accounts = googleAccounts,
            phone_number = phoneNumber,
            email_accounts = emailAccounts,
            SIM_info = simInfo
        )
    }

    private fun getGoogleAccounts(context: Context): List<String> {
        val accounts = mutableListOf<String>()
        try {
            val accountManager = AccountManager.get(context)
            val googleAccounts = accountManager.getAccountsByType("com.google")
            for (account in googleAccounts) {
                accounts.add(account.name)
            }
        } catch (e: Exception) {
            // Handle exception
        }
        return accounts
    }

    private fun getEmailAccounts(context: Context): List<String> {
        val accounts = mutableListOf<String>()
        try {
            val accountManager = AccountManager.get(context)
            val emailAccounts = accountManager.getAccountsByType("com.android.email")
            for (account in emailAccounts) {
                accounts.add(account.name)
            }
        } catch (e: Exception) {
            // Handle exception
        }
        return accounts
    }

    private fun getPhoneNumber(context: Context): String? {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as? TelephonyManager
            if (telephonyManager != null) return null

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED) {
                    return telephonyManager.line1Number
                }
            }
        } catch (e: Exception) {
            // Handle exception
        }
        return null
    }

    private fun getSIMInfo(context: Context): SIMInfo? {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as? TelephonyManager
            if (telephonyManager == null) return null

            return SIMInfo(
                carrier_name = telephonyManager.networkOperatorName,
                country_code = telephonyManager.networkCountryIso,
                phone_number = getPhoneNumber(context),
                network_operator = telephonyManager.networkOperator
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun readFile(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists()) {
                file.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
