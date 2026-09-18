package com.dynamictransport.dynamicone.core.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.app.RestAlarmActivity
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.push.EXTRA_TYPE
import com.dynamictransport.dynamicone.core.push.TYPE_START_SHIFT
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.rules.RestAlarmMode
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class RestAlarmPermissionState(
    val notifications: Boolean = true,
    /** Android 12+: "Alarms & reminders". Without it the alarm can be late by several minutes. */
    val exactAlarms: Boolean = true,
    /** Android 14+: full screen over the lock screen. Without it the alarm is a heads-up notification. */
    val fullScreen: Boolean = true,
) {
    val allGranted: Boolean get() = notifications && exactAlarms && fullScreen
}

fun interface RestAlarmPermissions {
    fun read(): RestAlarmPermissionState
}

interface RestAlarmScheduler {
    /** Replaces the alarm set before. */
    fun schedule(triggerAt: Instant, rest: RestWindow, snooze: Boolean)

    fun cancel()
}

interface RestAlarmNotifier {
    fun ensureChannels()

    /** [test]: the driver tries the alarm from the settings; only "stop" is offered. */
    fun ring(rest: RestWindow, mode: RestAlarmMode, test: Boolean)

    fun cancel()
}

/** Intent extras and actions of the alarm, its notification and the full-screen activity. */
object RestAlarmIntents {
    const val ACTION_FIRE = "com.dynamictransport.dynamicone.action.REST_ALARM"
    const val ACTION_SNOOZE = "com.dynamictransport.dynamicone.action.REST_ALARM_SNOOZE"
    const val ACTION_STOP = "com.dynamictransport.dynamicone.action.REST_ALARM_STOP"
    const val EXTRA_REST_START = "restStart"
    const val EXTRA_REST_END = "restEnd"
    const val EXTRA_SNOOZE = "snooze"
    const val EXTRA_TEST = "test"

    fun Intent.putRest(rest: RestWindow): Intent =
        putExtra(EXTRA_REST_START, rest.start.toEpochMilli()).putExtra(EXTRA_REST_END, rest.end.toEpochMilli())

    fun Intent.rest(): RestWindow? {
        if (!hasExtra(EXTRA_REST_START) || !hasExtra(EXTRA_REST_END)) return null
        return RestWindow(Instant.ofEpochMilli(getLongExtra(EXTRA_REST_START, 0)), Instant.ofEpochMilli(getLongExtra(EXTRA_REST_END, 0)))
    }

    /** Opens the app on the daily shift start. */
    fun startShift(context: Context): Intent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        return launch
            .putExtra(EXTRA_TYPE, TYPE_START_SHIFT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@Singleton
class AndroidRestAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : RestAlarmScheduler {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(triggerAt: Instant, rest: RestWindow, snooze: Boolean) {
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RestAlarmReceiver::class.java)
                .setAction(RestAlarmIntents.ACTION_FIRE)
                .let { with(RestAlarmIntents) { it.putRest(rest) } }
                .putExtra(RestAlarmIntents.EXTRA_SNOOZE, snooze),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = triggerAt.toEpochMilli()
        try {
            if (canScheduleExact()) {
                // An alarm clock: exempt from Doze, shown in the status bar, like a wake-up alarm the driver sets.
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent()), operation)
                return
            }
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the call.
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    override fun cancel() {
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RestAlarmReceiver::class.java).setAction(RestAlarmIntents.ACTION_FIRE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    private fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE,
        RestAlarmIntents.startShift(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_CODE = 4_701
    }
}

@Singleton
class AndroidRestAlarmPermissions @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : RestAlarmPermissions {

    override fun read(): RestAlarmPermissionState {
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        return RestAlarmPermissionState(notifications, exact, fullScreen)
    }
}

@Singleton
class AndroidRestAlarmNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : RestAlarmNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannels() {
        val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(ALARM_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.rest_alarm_channel_alarm))
                .setDescription(context.getString(R.string.rest_alarm_channel_alarm_description))
                .setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setVibrationEnabled(true)
                .setVibrationPattern(longArrayOf(0, 800, 400, 800, 400, 800))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(REMINDER_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.rest_alarm_channel_reminder))
                .setDescription(context.getString(R.string.rest_alarm_channel_reminder_description))
                .build(),
        )
    }

    @SuppressLint("MissingPermission") // Without the permission the system drops it; the settings screen shows that.
    override fun ring(rest: RestWindow, mode: RestAlarmMode, test: Boolean) {
        val alarm = mode == RestAlarmMode.ALARM
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(rest.end)
        val before = Instant.now().isBefore(rest.end)
        val title = context.getString(
            when {
                test -> R.string.rest_alarm_test_title
                before -> R.string.rest_alarm_title_soon
                else -> R.string.rest_alarm_title
            },
        )
        val body = when {
            test -> context.getString(R.string.rest_alarm_test_body)
            before -> context.getString(R.string.rest_alarm_body_before, time)
            else -> context.getString(R.string.rest_alarm_body_ended, time)
        }
        val screen = alarmScreen(rest, test)
        val builder = NotificationCompat.Builder(context, if (alarm) ALARM_CHANNEL else REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (alarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            // Nothing personal in it: readable on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(if (test) screen else startShift(rest))
            .setDeleteIntent(action(RestAlarmIntents.ACTION_STOP, rest, test))
            .setAutoCancel(!alarm)
        if (!test) {
            builder.addAction(0, context.getString(R.string.rest_alarm_start_shift), startShift(rest))
            builder.addAction(0, context.getString(R.string.rest_alarm_snooze), action(RestAlarmIntents.ACTION_SNOOZE, rest, test))
        }
        builder.addAction(0, context.getString(R.string.rest_alarm_stop), action(RestAlarmIntents.ACTION_STOP, rest, test))
        if (alarm) {
            builder.setOngoing(true)
                .setFullScreenIntent(screen, true)
                .setTimeoutAfter(RING_TIMEOUT.toMillis())
        }
        val notification = builder.build()
        // The alarm sound repeats until the driver answers or the timeout ends it.
        if (alarm) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun alarmScreen(rest: RestWindow, test: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SCREEN,
        Intent(context, RestAlarmActivity::class.java)
            .let { with(RestAlarmIntents) { it.putRest(rest) } }
            .putExtra(RestAlarmIntents.EXTRA_TEST, test)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The app opens on the shift start; the activity stops the ringing (MainActivity). */
    private fun startShift(rest: RestWindow): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_START,
        with(RestAlarmIntents) { RestAlarmIntents.startShift(context).putRest(rest) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(action: String, rest: RestWindow, test: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, RestAlarmReceiver::class.java)
            .setAction(action)
            .let { with(RestAlarmIntents) { it.putRest(rest) } }
            .putExtra(RestAlarmIntents.EXTRA_TEST, test),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ALARM_CHANNEL = "rest_alarm"
        const val REMINDER_CHANNEL = "rest_reminder"
        const val NOTIFICATION_ID = 4_702
        private const val REQUEST_SCREEN = 4_703
        private const val REQUEST_START = 4_704

        /** Like an alarm clock, the sound ends by itself if nobody answers. */
        val RING_TIMEOUT: Duration = Duration.ofMinutes(20)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RestAlarmEntryPoint {
    fun restAlarmController(): RestAlarmController

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}

private fun BroadcastReceiver.runAsync(context: Context, block: suspend (RestAlarmController) -> Unit) {
    val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, RestAlarmEntryPoint::class.java)
    val pending = goAsync()
    entryPoint.applicationScope().launch {
        try {
            block(entryPoint.restAlarmController())
        } finally {
            pending.finish()
        }
    }
}

/** The alarm went off, or the driver chose snooze / stop on the notification. */
class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rest = with(RestAlarmIntents) { intent.rest() } ?: return
        val test = intent.getBooleanExtra(RestAlarmIntents.EXTRA_TEST, false)
        when (intent.action) {
            RestAlarmIntents.ACTION_FIRE -> runAsync(context) {
                it.onAlarmFired(rest, snooze = intent.getBooleanExtra(RestAlarmIntents.EXTRA_SNOOZE, false))
            }
            RestAlarmIntents.ACTION_SNOOZE -> runAsync(context) { it.snooze(rest) }
            RestAlarmIntents.ACTION_STOP -> runAsync(context) { if (test) it.stopTest() else it.stop(rest) }
        }
    }
}

/** Alarms are lost at a restart and follow clock and permission changes: set them again from stored data. */
class RestAlarmRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> runAsync(context) { it.reconcile(force = true) }
        }
    }
}

/** The system screens where the driver allows what the alarm needs. */
object RestAlarmSettingsScreens {
    fun openNotifications(context: Context) = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openExactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
    }

    fun openFullScreen(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()))
    }

    private fun open(context: Context, intent: Intent) {
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
