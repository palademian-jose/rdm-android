package com.rdm.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
    const val SIM_info: SIMInfo?
)

data class SIMInfo(
    val carrier_name: String?,
    val country_code: String?,
    val phone_number: String?,
    val network_operator: String?
)

object DeviceInfoCollector {

    suspend fun collect(context: Context): DeviceInfo = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val packageInfo = packageManager.getPackageInfo(context.packageName, 0)

        // Generate unique device ID
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: generateDeviceId()

        DeviceInfo(
            id = deviceId,
            name = getDeviceName(),
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            android_version = Build.VERSION.RELEASE,
            api_level = Build.VERSION.SDK_INT,
            architecture = System.getProperty("os.arch") ?: "unknown",
            serial = getSerial(),
            device_type = getDeviceType(),
            screen_info = collectScreenInfo(context),
            network_info = collectNetworkInfo(context),
            storage_info = collectStorageInfo(),
            memory_info = collectMemoryInfo(),
            cpu_info = collectCpuInfo(),
            battery_info = collectBatteryInfo(context),
            installed_apps = collectInstalledApps(context),
            user_data = collectUserData(context),
            created_at = System.currentTimeMillis().toString()
        )
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

    private fun generateDeviceId(): String {
        return "rdm_${(Math.random() * 1000000).toInt()}"
    }

    private fun getDeviceType(): String {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                when {
                    Build.PRODUCT.contains("tv") -> "tv"
                    Build.PRODUCT.contains("watch") -> "watch"
                    Build.PRODUCT.contains("automotive") -> "automotive"
                    else -> "phone"
                }
            }
            else -> "phone"
        }
    }

    private fun collectScreenInfo(context: Context): ScreenInfo {
        val displayMetrics = context.resources.displayMetrics
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = windowManager.defaultDisplay
        val rotation = display.rotation

        return ScreenInfo(
            width = displayMetrics.widthPixels,
            height = displayMetrics.heightPixels,
            density = displayMetrics.density,
            orientation = rotation
        )
    }

    private fun collectNetworkInfo(context: Context): NetworkInfo {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager

        val wifiInfo = wifiManager.connectionInfo
        val ipAddress = wifiInfo?.ipAddress?.let { formatIpAddress(it) }
        val macAddress = wifiInfo?.macAddress
        val ssid = wifiInfo?.ssid?.replace("\"", "")

        return NetworkInfo(
            ip_address = ipAddress,
            mac_address = macAddress,
            wifi_ssid = ssid,
            network_type = connectivityManager.activeNetworkInfo?.typeName ?: "unknown"
        )
    }

    private fun formatIpAddress(ip: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xFF,
            ip shr 8 and 0xFF,
            ip shr 16 and 0xFF,
            ip shr 24 and 0xFF
        )
    }

    private fun collectStorageInfo(): StorageInfo {
        val root = android.os.Environment.getExternalStorageDirectory()
        val stat = android.os.StatFs(root.path)

        val total = stat.totalBytes
        val available = stat.availableBytes
        val used = total - available
        val percentage = (used.toFloat() / total * 100)

        return StorageInfo(
            total = total,
            used = used,
            available = available,
            percentage_used = percentage
        )
    }

    private fun collectMemoryInfo(): MemoryInfo {
        val activityManager = android.app.ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        activityManager.getMemoryInfo(activityManager)

        val total = activityManager.totalMem
        val available = activityManager.availMem
        val used = total - available
        val percentage = (used.toFloat() / total * 100)

        return MemoryInfo(
            total = total,
            used = used,
            available = available,
            percentage_used = percentage
        )
    }

    private fun collectCpuInfo(): CpuInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val cpuInfo = readFile("/proc/cpuinfo") ?: ""
        val model = extractCpuModel(cpuInfo)
        val usage = calculateCpuUsage()

        return CpuInfo(
            cores = cores,
            model = model,
            usage = usage
        )
    }

    private fun calculateCpuUsage(): Float {
        try {
            val stat = readFile("/proc/stat") ?: return 0f
            val lines = stat.split("\n")
            if (lines.isEmpty()) return 0f

            val cpuLine = lines[0]
            val values = cpuLine.split(" ")
                .filter { it.isNotEmpty() }
                .drop(1) // Skip "cpu"

            if (values.size < 4) return 0f

            val user = values[0].toLongOrNull() ?: 0
            val nice = values[1].toLongOrNull() ?: 0
            val system = values[2].toLongOrNull() ?: 0
            val idle = values[3].toLongOrNull() ?: 0

            val total = user + nice + system + idle
            val working = user + nice + system

            return if (total > 0) {
                (working.toFloat() / total * 100)
            } else {
                0f
            }
        } catch (e: Exception) {
            return 0f
        }
    }

    private fun extractCpuModel(cpuInfo: String): String {
        val lines = cpuInfo.split("\n")
        for (line in lines) {
            if (line.startsWith("Hardware")) {
                return line.substringAfter(":").trim()
            }
        }
        return "unknown"
    }

    private fun collectBatteryInfo(context: Context): BatteryInfo {
        val batteryStatus: android.content.IntentFilter? = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent: android.content.Intent? = context.registerReceiver(null, batteryStatus)

        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val health = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)
            val temperature = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0f

            val percentage = if (scale > 0) level.toFloat() / scale * 100 else 0f

            return BatteryInfo(
                level = level,
                scale = scale,
                percentage = percentage,
                status = getBatteryStatus(status),
                health = getBatteryHealth(health),
                temperature = if (temperature > -100) temperature else null
            )
        }

        return BatteryInfo(-1, -1, 0f, "unknown", "unknown", null)
    }

    private fun getBatteryStatus(status: Int): String {
        return when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            android.os.BatteryManager.BATTERY_STATUS_UNKNOWN -> "unknown"
            else -> "unknown"
        }
    }

    private fun getBatteryHealth(health: Int): String {
        return when (health) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
    }

    private fun collectInstalledApps(context: Context): List<AppInfo> {
        val packageManager = context.packageManager
        val packages = packageManager.getInstalledPackages(0)
        val apps = mutableListOf<AppInfo>()

        for (packageInfo in packages) {
            try {
                val applicationInfo = packageInfo.applicationInfo
                val appName = packageManager.getApplicationLabel(applicationInfo)?.toString()

                val app = AppInfo(
                    package_name = packageInfo.packageName,
                    app_name = appName,
                    version_name = packageInfo.versionName,
                    version_code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    },
                    is_system = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                    installed_date = packageInfo.firstInstallTime,
                    last_updated_date = packageInfo.lastUpdateTime,
                    icon_path = null // Can be added if needed
                )
                apps.add(app)
            } catch (e: Exception) {
                // Skip apps that fail to parse
            }
        }

        return apps.sortedBy { it.app_name }
    }

    private fun collectUserData(context: Context): UserData {
        val googleAccounts = getGoogleAccounts(context)
        val emailAccounts = getEmailAccounts(context)

        return UserData(
            google_accounts = googleAccounts,
            phone_number = getPhoneNumber(context),
            email_accounts = emailAccounts,
            const val SIM_info = getSIMInfo(context)
        )
    }

    private fun getGoogleAccounts(context: Context): List<String> {
        val accounts = mutableListOf<String>()
        try {
            val accountManager = android.accounts.AccountManager.get(context)
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
            val accountManager = android.accounts.AccountManager.get(context)
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
                    as android.telephony.TelephonyManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return telephonyManager.line1Number
                }
            }
        } catch (e: Exception) {
            // Handle exception
        }
        return null
    }

    private fun getSIMInfo(context: Context): SIMInfo {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager

            return SIMInfo(
                carrier_name = telephonyManager.networkOperatorName,
                country_code = telephonyManager.networkCountryIso,
                phone_number = getPhoneNumber(context),
                network_operator = telephonyManager.networkOperator
            )
        } catch (e: Exception) {
            return SIMInfo(null, null, null, null)
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
