package com.rdm.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.telephony.TelephonyManager
import android.accounts.AccountManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.app.ActivityManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                model = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                android_version = Build.VERSION.RELEASE,
                api_level = Build.VERSION.SDK_INT,
                architecture = Build.SUPPORTED_ABIS[0],
                serial = getSerial(),
                device_type = getDeviceType(),
                screen_info = getScreenInfo(context),
                network_info = getNetworkInfo(context),
                storage_info = getStorageInfo(context),
                memory_info = getMemoryInfo(context),
                cpu_info = getCpuInfo(),
                battery_info = getBatteryInfo(context),
                installed_apps = getInstalledApps(context),
                user_data = collectUserData(context),
                created_at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            )
        }
    }

    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun getSerial(): String {
        return try {
            Build.getSerial()
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getDeviceType(): String {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "Tablet"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "Large Tablet"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB -> "Tablet"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.DONUT -> "Phone"
            else -> "Phone"
        }
    }

    private fun getScreenInfo(context: Context): ScreenInfo {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)

        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val windowContext = context.createWindowContext(context.display).also {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        it.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                windowContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            } catch (e: Exception) {
                0
            }
        } else {
            val windowParams = windowManager.defaultDisplay.attributes
            windowParams.rotation
        }

        return ScreenInfo(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.density,
            orientation = rotation
        )
    }

    private fun getNetworkInfo(context: Context): NetworkInfo {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetworkInfo
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

            val ipAddress = activeNetwork?.let { network ->
                try {
                    val linkProperties = network.linkProperties
                    val addresses = linkProperties.linkAddresses
                    addresses.firstOrNull()?.let { addr ->
                        val address = InetAddress.getByAddress(addr.address)
                        address?.hostAddress
                    }
                } catch (e: Exception) {
                    null
                }
            }

            val macAddress = activeNetwork?.let { network ->
                try {
                    val linkProperties = network.linkProperties
                    linkProperties.linkAddresses.firstOrNull()?.interfaceAddress?.hostAddress
                } catch (e: Exception) {
                    null
                }
            }

            val wifiSSID = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    wifiManager?.currentNetwork?.ssid?.removeSurrounding("\"")
                } catch (e: Exception) {
                    null
                }
            } else {
                try {
                    @Suppress("DEPRECATION")
                    wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
                } catch (e: Exception) {
                    null
                }
            }

            return NetworkInfo(
                ip_address = ipAddress,
                mac_address = macAddress,
                wifi_ssid = wifiSSID,
                network_type = activeNetwork?.typeName ?: "unknown"
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
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            try {
                val stat = android.os.StatFs.getExternalStorageDirectory()
                stat.blockSizeLong * stat.blockCount
            } catch (e: Exception) {
                0L
            }
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
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = activityManager.getMemoryInfo(ActivityManager.MemoryInfo.MAX)

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
        val model = Build.SUPPORTED_ABIS[0]
        val usage = 0.0f

        return CpuInfo(
            cores = cores,
            model = model,
            usage = usage
        )
    }

    private fun getBatteryInfo(context: Context): BatteryInfo {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (batteryManager != null) {
                    val batteryState = batteryManager.getBatteryProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                    val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val scale = 100
                    val percentage = (batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_LEVEL).toFloat() / level) * 100

                    return BatteryInfo(
                        level = level,
                        scale = scale,
                        percentage = percentage,
                        status = batteryState ?: "unknown",
                        health = batteryState ?: "unknown",
                        temperature = null
                    )
                }
            }

            @Suppress("DEPRECATION")
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            if (batteryManager != null) {
                val intent = batteryManager.queryIntentStatus(BatteryManager.BATTERY_STATUS_PLUGGED)
                val isPlugged = intent == 1
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val status = when {
                    intent -> "Plugged in"
                    else -> "Unplugged"
                }

                return BatteryInfo(
                    level = level,
                    scale = 100,
                    percentage = level.toFloat(),
                    status = status,
                    health = "Good",
                    temperature = null
                )
            }

            BatteryInfo(0, 0, 0f, "unknown", "unknown", null)
        } catch (e: Exception) {
            BatteryInfo(0, 0, 0f, "unknown", "unknown", null)
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
                    app_name = appInfo.loadLabel(packageManager)?.toString(),
                    version_name = appInfo.versionName,
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
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                if (telephonyManager != null) {
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
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager == null) return null

            return SIMInfo(
                carrier_name = telephonyManager.simOperatorName,
                country_code = telephonyManager.simCountryIso,
                phone_number = getPhoneNumber(context),
                network_operator = telephonyManager.simOperator
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
