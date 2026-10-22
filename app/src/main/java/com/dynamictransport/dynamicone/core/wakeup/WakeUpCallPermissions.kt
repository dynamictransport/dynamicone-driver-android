package com.dynamictransport.dynamicone.core.wakeup

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.dynamictransport.dynamicone.core.alarm.RestAlarmSettingsScreens
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What keeps a wake-up call from ringing on this device (docs/25 U5), most serious first. */
enum class WakeUpPermissionIssue {
    /** Notifications are off: nothing rings at all. */
    NOTIFICATIONS,

    /** Android 14+: the call cannot open over the lock screen. */
    FULL_SCREEN,

    /** Battery optimisation may delay the push on a phone. */
    BATTERY,

    /** Do Not Disturb in "total silence": alarms stay silent. */
    DND,
}

fun interface WakeUpCallPermissions {
    /** The first issue the driver should fix, or null when the call can ring as designed. */
    fun firstIssue(): WakeUpPermissionIssue?
}

@Singleton
class AndroidWakeUpCallPermissions @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WakeUpCallPermissions {

    override fun firstIssue(): WakeUpPermissionIssue? {
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                )
        if (!notifications) return WakeUpPermissionIssue.NOTIFICATIONS
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || notificationManager.canUseFullScreenIntent()
        if (!fullScreen) return WakeUpPermissionIssue.FULL_SCREEN
        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(context.packageName)) return WakeUpPermissionIssue.BATTERY
        if (notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) return WakeUpPermissionIssue.DND
        return null
    }
}

/** The system screens where the driver allows what the wake-up call needs. */
object WakeUpCallSettingsScreens {
    fun open(context: Context, issue: WakeUpPermissionIssue) {
        when (issue) {
            WakeUpPermissionIssue.NOTIFICATIONS -> RestAlarmSettingsScreens.openNotifications(context)
            WakeUpPermissionIssue.FULL_SCREEN -> RestAlarmSettingsScreens.openFullScreen(context)
            WakeUpPermissionIssue.BATTERY -> openBatteryOptimisation(context)
            WakeUpPermissionIssue.DND -> start(context, Intent(Settings.ACTION_SOUND_SETTINGS))
        }
    }

    /**
     * The exemption dialog is allowed here: without it a push can reach a sleeping phone minutes late, and the
     * call exists to wake the driver (docs/25 U5).
     */
    @SuppressLint("BatteryLife")
    private fun openBatteryOptimisation(context: Context) {
        start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
