package com.dynamictransport.dynamicone.core.tar

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.push.EXTRA_TRIP_ID
import com.dynamictransport.dynamicone.core.push.EXTRA_TYPE
import com.dynamictransport.dynamicone.core.push.TYPE_TAR_WARNING
import com.dynamictransport.dynamicone.domain.model.TarWarning
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

object TarWarningIntents {
    const val ACTION_FIRE = "com.dynamictransport.dynamicone.action.TAR_WARNING"
    const val EXTRA_KEY = "tarWarningKey"
}

/** One exact device alarm per warning; without the exact-alarm permission an inexact one that still wakes the device. */
@Singleton
class AndroidTarWarningScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TarWarningScheduler {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(key: String, triggerAt: Instant) {
        val operation = checkNotNull(operation(key, PendingIntent.FLAG_UPDATE_CURRENT))
        val at = triggerAt.toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                return
            }
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the call.
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    override fun cancel(key: String) {
        val operation = operation(key, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    /** The data URI keeps the intents of different warnings apart even if two keys hash alike. */
    private fun operation(key: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        key.hashCode(),
        Intent(context, TarWarningReceiver::class.java)
            .setAction(TarWarningIntents.ACTION_FIRE)
            .setData("tar-warning:$key".toUri())
            .putExtra(TarWarningIntents.EXTRA_KEY, key),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** A heads-up notification with the alarm sound and vibration; tapping it opens the trip. */
@Singleton
class AndroidTarWarningNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TarWarningNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannel() {
        val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.tar_channel))
                .setDescription(context.getString(R.string.tar_channel_description))
                .setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setVibrationEnabled(true)
                .setVibrationPattern(longArrayOf(0, 600, 300, 600))
                .build(),
        )
    }

    @SuppressLint("MissingPermission") // Without the permission the system drops it; the home banner shows that.
    override fun notify(warning: TarWarning) {
        val endTime = DateTimeFormatter.ofPattern("HH:mm").withZone(warning.timeZone).format(warning.endAt)
        val zone = warning.timeZone.id.substringAfterLast('/').replace('_', ' ')
        val title = context.getString(R.string.tar_warning_title, warning.code, warning.leadMinutes)
        val place = warning.stopName ?: warning.tripNo
        val body = context.getString(R.string.tar_warning_body, place, endTime, zone)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // A code and a terminal name: readable on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openTrip(warning))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        // One notification per stop: the 30-minute warning replaces the 60-minute one.
        manager.notify(tag(warning), NOTIFICATION_ID, notification)
    }

    override fun cancelAll() {
        manager.activeNotifications.filter { it.id == NOTIFICATION_ID && it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun tag(warning: TarWarning) = "$TAG_PREFIX${warning.tripId}-${warning.stopId}"

    private fun openTrip(warning: TarWarning): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        return PendingIntent.getActivity(
            context,
            tag(warning).hashCode(),
            launch
                .putExtra(EXTRA_TYPE, TYPE_TAR_WARNING)
                .putExtra(EXTRA_TRIP_ID, warning.tripId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "tar_warning"
        const val NOTIFICATION_ID = 4_801
        private const val TAG_PREFIX = "tar-"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TarWarningEntryPoint {
    fun tarWarningController(): TarWarningController

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}

private fun BroadcastReceiver.runAsync(context: Context, block: suspend (TarWarningController) -> Unit) {
    val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, TarWarningEntryPoint::class.java)
    val pending = goAsync()
    entryPoint.applicationScope().launch {
        try {
            block(entryPoint.tarWarningController())
        } finally {
            pending.finish()
        }
    }
}

/** A warning's device alarm went off. */
class TarWarningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TarWarningIntents.ACTION_FIRE) return
        val key = intent.getStringExtra(TarWarningIntents.EXTRA_KEY)?.takeIf { it.isNotBlank() } ?: return
        runAsync(context) { it.onFired(key) }
    }
}

/** Device alarms are lost at a restart and follow clock changes: the stored warnings are set again. */
class TarWarningRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> runAsync(context) { it.rearm() }
        }
    }
}
