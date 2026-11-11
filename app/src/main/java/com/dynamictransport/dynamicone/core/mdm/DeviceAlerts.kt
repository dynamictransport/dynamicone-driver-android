package com.dynamictransport.dynamicone.core.mdm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

const val ACTION_DEVICE_RING_STOP = "com.dynamictransport.dynamicone.action.DEVICE_RING_STOP"

/** What the office's `ring` and `message` commands do on the phone (docs/29 §3.4). */
interface DeviceAlerts {
    fun ensureChannels()

    /** Alarm sound at full volume until dismissed or [seconds] passed. False when the phone cannot show it. */
    fun ring(seconds: Int): Boolean

    fun stopRing()

    /** A high-priority notification with the office's text. False when the phone cannot show it. */
    fun showMessage(commandId: String, title: String, body: String): Boolean
}

@Singleton
class AndroidDeviceAlerts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) : DeviceAlerts {

    private val manager = NotificationManagerCompat.from(context)
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val lock = Any()

    /** The driver's own alarm volume, kept while the phone rings. */
    private var previousVolume: Int? = null
    private var ringTimeout: Job? = null

    override fun ensureChannels() {
        val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(RING_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.mdm_channel_ring))
                .setDescription(context.getString(R.string.mdm_channel_ring_description))
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
            NotificationChannelCompat.Builder(MESSAGE_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.mdm_channel_message))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(SYNC_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.mdm_channel_sync))
                .build(),
        )
    }

    // canNotify() checks the permission right before posting. A tap anywhere on the notification silences the phone
    // through the receiver: nothing is opened, so there is no activity to launch.
    @SuppressLint("MissingPermission", "LaunchActivityFromNotification")
    override fun ring(seconds: Int): Boolean {
        if (!canNotify()) return false
        ensureChannels()
        val stop = PendingIntent.getBroadcast(
            context,
            REQUEST_RING_STOP,
            Intent(context, DeviceAlertReceiver::class.java).setAction(ACTION_DEVICE_RING_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = seconds * 1_000L
        val notification = NotificationCompat.Builder(context, RING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.mdm_ring_title))
            .setContentText(context.getString(R.string.mdm_ring_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .setTimeoutAfter(millis)
            .addAction(0, context.getString(R.string.mdm_ring_stop), stop)
            .build()
        // The alarm sound repeats until the phone is found or the time is up.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        synchronized(lock) {
            raiseVolume()
            ringTimeout?.cancel()
            ringTimeout = scope.launch {
                delay(millis)
                stopRing()
            }
        }
        manager.notify(RING_NOTIFICATION_ID, notification)
        return true
    }

    override fun stopRing() {
        manager.cancel(RING_NOTIFICATION_ID)
        synchronized(lock) {
            ringTimeout?.cancel()
            ringTimeout = null
            restoreVolume()
        }
    }

    @SuppressLint("MissingPermission") // canNotify() checks the permission right before posting.
    override fun showMessage(commandId: String, title: String, body: String): Boolean {
        if (!canNotify()) return false
        ensureChannels()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_MESSAGE_OPEN,
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shownTitle = title.ifBlank { context.getString(R.string.mdm_message_default_title) }
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // One notification per command: a later message does not replace an unread one.
        manager.notify(commandId, MESSAGE_NOTIFICATION_ID, notification)
        return true
    }

    private fun canNotify(): Boolean = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun raiseVolume() {
        val audio = audio ?: return
        if (previousVolume != null) return
        try {
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (current >= max) return
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            previousVolume = current
        } catch (_: SecurityException) {
            // Do Not Disturb in total silence refuses volume changes: the phone rings as loud as it is allowed to.
        }
    }

    private fun restoreVolume() {
        val level = previousVolume ?: return
        previousVolume = null
        try {
            audio?.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
        } catch (_: SecurityException) {
            // Nothing to do: the system keeps the level it allows.
        }
    }

    companion object {
        const val RING_CHANNEL_ID = "device_ring"
        const val MESSAGE_CHANNEL_ID = "device_message"

        /** The short notice of a check-in that Android (before 12) runs as a foreground service. */
        const val SYNC_CHANNEL_ID = "device_sync"
        const val RING_NOTIFICATION_ID = 4_901
        const val MESSAGE_NOTIFICATION_ID = 4_902
        const val SYNC_NOTIFICATION_ID = 4_903
        private const val REQUEST_RING_STOP = 4_904
        private const val REQUEST_MESSAGE_OPEN = 4_905
    }
}

/** "Stop" on the ring notification, or the notification swiped away. */
@AndroidEntryPoint
class DeviceAlertReceiver : BroadcastReceiver() {

    @Inject
    lateinit var alerts: DeviceAlerts

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DEVICE_RING_STOP) alerts.stopRing()
    }
}
