package com.dynamictransport.dynamicone.core.mdm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.dynamictransport.dynamicone.BuildConfig
import com.dynamictransport.dynamicone.core.remote.RemoteControlAccess
import com.dynamictransport.dynamicone.domain.model.DeviceStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The phone's state for enrolment and check-in (docs/29 §3.1, §3.2). No location, no account data. */
fun interface DeviceStatusCollector {
    fun collect(): DeviceStatus
}

@Singleton
class AndroidDeviceStatusCollector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val policy: DevicePolicyController,
    private val remoteControl: RemoteControlAccess,
) : DeviceStatusCollector {

    override fun collect(): DeviceStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val owner = policy.isDeviceOwner
        val storage = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        return DeviceStatus(
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() },
            isDeviceOwner = owner,
            accessibilityEnabled = remoteControl.isEnabled,
            batteryPercent = batteryPercent(battery),
            isCharging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            networkType = networkType(),
            storageFreeMb = (storage?.availableBytes ?: 0L) / BYTES_PER_MB,
            storageTotalMb = (storage?.totalBytes ?: 0L) / BYTES_PER_MB,
            locale = Locale.getDefault().toLanguageTag(),
            manufacturer = Build.MANUFACTURER.orEmpty().trim(),
            model = Build.MODEL.orEmpty().trim(),
            serialNumber = if (owner) serialNumber() else null,
        )
    }

    private fun batteryPercent(battery: Intent?): Int? {
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
    }

    /** The contract knows `wifi`, `cellular` and `none`: any other connection (cable, VPN) counts as `wifi`. */
    private fun networkType(): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return NETWORK_NONE
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return NETWORK_NONE
        return when {
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NETWORK_NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NETWORK_WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NETWORK_CELLULAR
            else -> NETWORK_WIFI
        }
    }

    /** The device owner may read the serial number once it holds READ_PHONE_STATE (granted by [DevicePolicyApplier]). */
    @SuppressLint("HardwareIds", "MissingPermission")
    private fun serialNumber(): String? = try {
        Build.getSerial()?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
    } catch (_: SecurityException) {
        null
    }

    companion object {
        const val NETWORK_WIFI = "wifi"
        const val NETWORK_CELLULAR = "cellular"
        const val NETWORK_NONE = "none"
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
